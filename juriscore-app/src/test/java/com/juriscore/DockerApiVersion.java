package com.juriscore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agrees a Docker Engine API version with the daemon before Testcontainers talks to it.
 *
 * <h2>The failure this fixes</h2>
 *
 * <p>Testcontainers 1.21.3 does not negotiate. {@code DockerClientProviderStrategy}
 * builds every client through one method, and that method contains:
 *
 * <pre>{@code
 * if (configBuilder.build().getApiVersion() == RemoteApiVersion.UNKNOWN_VERSION) {
 *     configBuilder.withApiVersion(RemoteApiVersion.VERSION_1_32);
 * }
 * }</pre>
 *
 * <p>So unless docker-java has already resolved a version of its own, the client is
 * pinned to API 1.32. docker-java then prefixes that onto every path
 * ({@code DefaultDockerCmdExecFactory} does {@code resource = "/" + apiVersion.asWebPathPart() + resource}),
 * so the first call Testcontainers makes is literally {@code GET /v1.32/info}.
 *
 * <p>Docker Engine 29 raised its minimum supported API version to 1.44 and rejects
 * anything below it:
 *
 * <pre>
 * HTTP/1.1 400 Bad Request
 * {"message":"client version 1.32 is too old. Minimum supported API version is 1.44,
 *             please upgrade your client to a newer version"}
 * </pre>
 *
 * <p>which surfaces as {@code BadRequestException (Status 400)}. Every strategy reports
 * it, because every strategy is handed a client from that same method — the socket was
 * found and connected to in all three cases. The failure was never discovery.
 *
 * <h2>Why {@code TESTCONTAINERS_API_VERSION} changed nothing</h2>
 *
 * <p>Because Testcontainers 1.21.3 has no {@code api.version} setting. Its configuration
 * keys are read through {@code TestcontainersConfiguration}, which derives the
 * environment variable name by upper-casing a key it knows about; {@code api.version} is
 * not one of them, so {@code TESTCONTAINERS_API_VERSION} is read by nothing at all.
 *
 * <p>The key that <em>is</em> live is docker-java's own {@code api.version}, and
 * docker-java reads it from {@link System#getProperties()} — see
 * {@code DefaultDockerClientConfig.CONFIG_KEYS} and
 * {@code overrideDockerPropertiesWithSystemProperties}. Setting that property is
 * therefore the one lever that reaches the client Testcontainers is about to build:
 * with it set, {@code getApiVersion()} is no longer {@code UNKNOWN_VERSION} and the
 * hardcoded 1.32 above is never applied.
 *
 * <h2>Why it asks the daemon rather than hardcoding a number</h2>
 *
 * <p>1.44 is only the right answer for Docker Engine 29. It is wrong for an engine whose
 * floor moves again, and it is wrong in the other direction for an old daemon whose
 * maximum is below 1.32 — that rejects 1.32 too, as "too new". The daemon publishes both
 * bounds on its unversioned {@code /version} endpoint, which every engine answers
 * regardless of client version (the version middleware substitutes its own default when
 * a request carries no {@code /vX.Y} prefix), so the bounds can simply be read.
 *
 * <p>The rule is a clamp: take Testcontainers' 1.32 and move it the shortest distance
 * needed to land inside {@code [MinAPIVersion, ApiVersion]}.
 *
 * <ul>
 *   <li>1.32 already inside the window — do nothing. Testcontainers' own default works,
 *       and an unchanged JVM is the smallest possible footprint.</li>
 *   <li>1.32 below the floor (Docker Engine 29: 1.44..1.52) — take the floor. It is the
 *       oldest dialect the daemon still accepts, and therefore the one closest to what
 *       this docker-java was written against. Reaching for the daemon's newest would ask
 *       a 3.4.2 client to speak a dialect that did not exist when it was built.</li>
 *   <li>1.32 above the ceiling (an old daemon capped below it) — take the ceiling. Docker
 *       rejects "too new" with the same 400 as "too old", so this direction needs
 *       correcting too, and the ceiling is the newest the daemon will take.</li>
 * </ul>
 *
 * <p>In both directions the answer is the supported version nearest to 1.32, which keeps
 * the client as close as possible to the configuration Testcontainers was tested with.
 *
 * <h2>Ordering</h2>
 *
 * <p>The property has to be set before the first Docker API request, because
 * {@code DockerClientFactory} resolves a strategy once and caches it for the life of the
 * JVM. {@link DockerEnvironmentInitializer} — a {@code LauncherSessionListener} — is the
 * hook that guarantees that, and it calls this after {@link DockerEnvironment} has
 * settled which endpoint to talk to.
 */
final class DockerApiVersion {

    /**
     * docker-java's configuration key, and the only one that reaches the client
     * Testcontainers builds. Deliberately not {@code TESTCONTAINERS_API_VERSION}, which
     * nothing reads.
     */
    static final String API_VERSION_PROPERTY = "api.version";

    /** What Testcontainers 1.21.3 pins the client to when docker-java resolved nothing. */
    static final String TESTCONTAINERS_DEFAULT_VERSION = "1.32";

    /**
     * Overrides honoured ahead of negotiation, most specific first.
     *
     * <p>{@code DOCKER_API_VERSION} is the Docker CLI's own override, so a developer who
     * has already pinned a version for {@code docker} gets the same version here.
     * {@code TESTCONTAINERS_API_VERSION} is honoured because it is the name people reach
     * for — this is what makes it mean something.
     */
    private static final List<String> OVERRIDE_ENV_VARS = List.of("DOCKER_API_VERSION", "TESTCONTAINERS_API_VERSION");

    private static final long PROBE_TIMEOUT_SECONDS = 5;

    /** A {@code /version} payload is a couple of kilobytes; this is a runaway guard. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final String PREFIX = "[juriscore] ";

    private static final byte[] CRLF_CRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * Matches {@code "ApiVersion": "1.52"} but not {@code "MinAPIVersion"} — the negative
     * lookbehind on {@code Min} matters because Docker's casing differs between the two
     * ({@code ApiVersion} against {@code MinAPIVersion}) and a careless pattern reads one
     * as the other.
     */
    private static final Pattern API_VERSION = Pattern.compile("\"ApiVersion\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern MIN_API_VERSION = Pattern.compile("\"MinAPIVersion\"\\s*:\\s*\"([^\"]+)\"");

    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)");

    private static boolean done;

    private DockerApiVersion() {
    }

    /** What the daemon says it can speak. Both bounds are inclusive. */
    record DaemonVersions(String apiVersion, String minApiVersion) {
    }

    /** Idempotent; the first caller decides and later calls are no-ops. */
    static synchronized void ensureNegotiated() {
        if (done) {
            return;
        }
        done = true;

        String override = applyExplicitOverride();
        if (override != null) {
            say("Docker API version fixed by " + override + "; skipping negotiation.");
            return;
        }

        Optional<URI> endpoint = DockerEnvironment.endpoint().flatMap(DockerApiVersion::toUri);
        if (endpoint.isEmpty()) {
            say("No Docker endpoint to negotiate against; leaving the API version to Testcontainers.");
            return;
        }

        Optional<String> body = fetchVersionJson(endpoint.get());
        if (body.isEmpty()) {
            say("Could not read GET /version from " + endpoint.get()
                    + "; leaving the API version to Testcontainers.");
            return;
        }

        Optional<DaemonVersions> versions = parseVersions(body.get());
        if (versions.isEmpty()) {
            say("GET /version answered but named no ApiVersion; leaving the API version to Testcontainers.");
            return;
        }

        DaemonVersions daemon = versions.get();
        say("Docker daemon speaks API " + describe(daemon.minApiVersion()) + " .. " + daemon.apiVersion());

        Optional<String> chosen = chooseApiVersion(daemon);
        if (chosen.isEmpty()) {
            say("Testcontainers' default of " + TESTCONTAINERS_DEFAULT_VERSION
                    + " is accepted by this daemon; leaving it alone.");
            return;
        }

        System.setProperty(API_VERSION_PROPERTY, chosen.get());
        say("Negotiated Docker API version: " + chosen.get());
        say("Published as system property '" + API_VERSION_PROPERTY
                + "'; Testcontainers would otherwise have pinned " + TESTCONTAINERS_DEFAULT_VERSION
                + ", which this daemon rejects with HTTP 400.");
    }

    // ------------------------------------------------------------------- decisions

    /**
     * Clamps Testcontainers' default into the window the daemon accepts, or empty to leave
     * it alone.
     *
     * <p>Pure, so the rule can be tested without a daemon. Every path that cannot be
     * reasoned about returns empty rather than a guess: an override that is wrong is worse
     * than no override, because it replaces Testcontainers' failure with ours.
     */
    static Optional<String> chooseApiVersion(DaemonVersions daemon) {
        if (daemon == null || !isVersion(daemon.apiVersion())) {
            // No ceiling, or one we cannot read. Nothing to clamp against.
            return Optional.empty();
        }
        if (!isVersion(daemon.minApiVersion())) {
            // Pre-1.25 daemons omit MinAPIVersion. Without a floor there is nothing to
            // reason about, and 1.32 has always worked on engines that old.
            return Optional.empty();
        }
        if (compareVersions(daemon.minApiVersion(), daemon.apiVersion()) > 0) {
            // A daemon whose floor is above its ceiling is telling us something we cannot
            // act on. Refusing to guess beats inventing a version.
            return Optional.empty();
        }
        if (compareVersions(TESTCONTAINERS_DEFAULT_VERSION, daemon.minApiVersion()) < 0) {
            return Optional.of(normalise(daemon.minApiVersion()));
        }
        if (compareVersions(TESTCONTAINERS_DEFAULT_VERSION, daemon.apiVersion()) > 0) {
            return Optional.of(normalise(daemon.apiVersion()));
        }
        return Optional.empty();
    }

    /** Numeric, not lexicographic: 1.9 is older than 1.10, which string order gets wrong. */
    static int compareVersions(String left, String right) {
        Matcher a = VERSION.matcher(left.trim());
        Matcher b = VERSION.matcher(right.trim());
        if (!a.matches() || !b.matches()) {
            throw new IllegalArgumentException("not a Docker API version: '" + left + "' or '" + right + "'");
        }
        int major = Integer.compare(Integer.parseInt(a.group(1)), Integer.parseInt(b.group(1)));
        return major != 0 ? major : Integer.compare(Integer.parseInt(a.group(2)), Integer.parseInt(b.group(2)));
    }

    // --------------------------------------------------------------------- parsing

    /**
     * Pulls the two bounds out of a {@code /version} payload.
     *
     * <p>Deliberately regex rather than a JSON parser: this runs before the test
     * classpath's Jackson is a safe thing to touch, and the two fields are flat strings.
     * Docker repeats the same pair inside {@code Components[].Details} for the Engine
     * component, with identical values, so the first match is the right one either way.
     */
    static Optional<DaemonVersions> parseVersions(String json) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher api = API_VERSION.matcher(json);
        if (!api.find()) {
            return Optional.empty();
        }
        Matcher min = MIN_API_VERSION.matcher(json);
        return Optional.of(new DaemonVersions(api.group(1), min.find() ? min.group(1) : null));
    }

    /**
     * Returns the body of a 200 response, or empty for anything else.
     *
     * <p>Chunked framing is decoded on the raw bytes rather than on a decoded string:
     * chunk sizes count bytes, and slicing a {@code String} by those counts corrupts the
     * payload the moment a field (a Docker Desktop platform name, say) is not ASCII.
     */
    static Optional<String> httpBody(byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int split = indexOf(raw, CRLF_CRLF, 0);
        if (split < 0) {
            return Optional.empty();
        }
        String head = new String(raw, 0, split, StandardCharsets.US_ASCII);
        String statusLine = head.lines().findFirst().orElse("");
        if (!statusLine.startsWith("HTTP/") || !statusLine.contains(" 200")) {
            say("GET /version answered: " + statusLine);
            return Optional.empty();
        }
        byte[] body = new byte[raw.length - split - CRLF_CRLF.length];
        System.arraycopy(raw, split + CRLF_CRLF.length, body, 0, body.length);
        if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
            body = dechunk(body);
        }
        return Optional.of(new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        int cursor = 0;
        while (cursor < body.length) {
            int eol = indexOf(body, crlf, cursor);
            if (eol < 0) {
                break;
            }
            String token = new String(body, cursor, eol - cursor, StandardCharsets.US_ASCII).trim();
            int extension = token.indexOf(';');
            if (extension >= 0) {
                token = token.substring(0, extension).trim();
            }
            int size;
            try {
                size = Integer.parseInt(token, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size <= 0) {
                break;
            }
            int start = eol + crlf.length;
            int end = Math.min(start + size, body.length);
            out.write(body, start, end - start);
            cursor = end + crlf.length;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------- transport

    /**
     * Asks the daemon what it speaks, over whichever endpoint was resolved.
     *
     * <p>The request carries no {@code /vX.Y} prefix on purpose. That is the one form of
     * request no daemon can reject on version grounds, which is what makes this usable as
     * the thing that decides the version.
     */
    static Optional<String> fetchVersionJson(URI endpoint) {
        String request = "GET /version HTTP/1.1\r\n"
                + "Host: docker\r\n"
                + "Accept: application/json\r\n"
                + "User-Agent: juriscore-api-version-probe\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] raw = switch (String.valueOf(endpoint.getScheme())) {
                case "unix" -> overUnixSocket(Path.of(endpoint.getPath()), bytes);
                case "tcp", "http" -> overTcp(endpoint, bytes);
                default -> {
                    // https/npipe would need TLS or Windows named pipes; neither is worth
                    // reimplementing here. Testcontainers keeps its default and the build
                    // behaves exactly as it did before this class existed.
                    say("Not probing " + endpoint.getScheme() + ":// endpoints for their API version.");
                    yield null;
                }
            };
            return httpBody(raw);
        } catch (IOException | RuntimeException e) {
            say("GET /version failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] overUnixSocket(Path socket, byte[] request) throws IOException {
        if (!Files.exists(socket)) {
            return null;
        }
        SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket));
        // A blocking SocketChannel has no read timeout of its own, so the read runs on a
        // daemon thread and the channel is closed from here if it overruns. A wedged
        // daemon must not be able to hang the build before a single test has run.
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "juriscore-docker-version-probe");
            thread.setDaemon(true);
            return thread;
        });
        try (channel) {
            Future<byte[]> response = worker.submit(() -> {
                channel.write(ByteBuffer.wrap(request));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                int read;
                while ((read = channel.read(buffer)) > 0) {
                    out.write(buffer.array(), 0, read);
                    buffer.clear();
                    if (out.size() >= MAX_RESPONSE_BYTES) {
                        break;
                    }
                }
                return out.toByteArray();
            });
            try {
                return response.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                response.cancel(true);
                return null;
            } catch (ExecutionException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static byte[] overTcp(URI endpoint, byte[] request) throws IOException {
        if (isSet(System.getenv("DOCKER_TLS_VERIFY")) || isSet(System.getProperty("DOCKER_TLS_VERIFY"))) {
            say("DOCKER_TLS_VERIFY is set; not probing the daemon's API version over plain TCP.");
            return null;
        }
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 2375;
        int timeout = (int) TimeUnit.SECONDS.toMillis(PROBE_TIMEOUT_SECONDS);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHost(), port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            return socket.getInputStream().readNBytes(MAX_RESPONSE_BYTES);
        }
    }

    // --------------------------------------------------------------------- helpers

    /**
     * Honours a version the developer or CI has already chosen, and publishes it under the
     * key docker-java actually reads.
     *
     * @return a description of the source that won, or null if nothing was set
     */
    private static String applyExplicitOverride() {
        String fromProperty = System.getProperty(API_VERSION_PROPERTY);
        if (isSet(fromProperty)) {
            return "system property " + API_VERSION_PROPERTY + "=" + fromProperty.trim();
        }
        for (String name : OVERRIDE_ENV_VARS) {
            String value = System.getenv(name);
            if (isSet(value)) {
                System.setProperty(API_VERSION_PROPERTY, value.trim());
                return "environment variable " + name + "=" + value.trim();
            }
        }
        return null;
    }

    private static Optional<URI> toUri(String endpoint) {
        try {
            return Optional.of(new URI(endpoint));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String normalise(String version) {
        Matcher matcher = VERSION.matcher(version.trim());
        return matcher.matches() ? matcher.group(1) + "." + matcher.group(2) : version.trim();
    }

    private static boolean isVersion(String value) {
        return value != null && VERSION.matcher(value.trim()).matches();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String describe(String value) {
        return value == null ? "<unstated>" : value;
    }

    private static void say(String message) {
        // Printed, not logged: this runs before any test logging is configured.
        System.out.println(PREFIX + message);
    }
}
