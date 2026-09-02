package com.juriscore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Points Testcontainers at the Docker endpoint the Docker CLI is actually using.
 *
 * <p>Testcontainers finds Docker by probing a fixed list of locations. Its Docker Desktop
 * strategy does know about {@code ~/.docker/run/docker.sock} on macOS, so on a stock
 * Docker Desktop install this class has nothing to correct. What its list does <em>not</em>
 * cover is a daemon reached through a non-default Docker <em>context</em> — Colima,
 * Podman, Rancher Desktop, a remote context — where the CLI works and Testcontainers
 * reports "Could not find a valid Docker environment".
 *
 * <p>Rather than guessing where Docker put its socket, this asks Docker. The active
 * context is the same source of truth {@code docker run} uses, so if the CLI works, the
 * tests work.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It is not the fix for an HTTP 400 during Testcontainers start-up. A 400 means the
 * socket was found, connected to, and answered — the client asked for a Docker API
 * version the daemon refuses. That is {@link DockerApiVersion}'s job, and the two are
 * separate on purpose: this one decides <em>where</em> to talk, that one decides
 * <em>how</em>.
 *
 * <h2>Why it does not check {@code /var/run/docker.sock} first</h2>
 *
 * <p>An earlier version returned early when that file existed, on the assumption that its
 * presence meant Docker was reachable there. Presence of a file is not proof of a working
 * daemon: on macOS a stale path can outlive the daemon that made it, and a machine with an
 * active non-default context is not listening there at all. The only authority on where
 * Docker listens is Docker's own configuration, so that is what is consulted — on every
 * platform, including Linux, where it returns {@code unix:///var/run/docker.sock} and
 * behaviour is therefore unchanged.
 *
 * <h2>Order of authority</h2>
 * <ol>
 *   <li>An explicit endpoint — the {@code DOCKER_HOST} environment variable, or a
 *       {@code -Ddocker.host} / {@code -DDOCKER_HOST} system property. A developer or CI
 *       pipeline that has said where Docker is must never be second-guessed.</li>
 *   <li>{@code docker context inspect} on the active context.</li>
 *   <li>The context metadata Docker writes under {@code ~/.docker/contexts}, for when the
 *       CLI is not on the PATH of a forked test JVM.</li>
 *   <li>The sockets Docker is known to publish, newest layout first.</li>
 * </ol>
 *
 * <p>Every outcome is printed, including the ones where nothing is done. The previous
 * version had four silent exits and one message, which is why a failure here produced no
 * evidence at all.
 */
final class DockerEnvironment {

    /**
     * Testcontainers' own configuration key. This is the one that matters: its Java
     * configuration is keyed on {@code docker.host}, and a {@code DOCKER_HOST} system
     * property is <em>not</em> a substitute for the {@code DOCKER_HOST} environment
     * variable — the JVM cannot set an environment variable for itself, so publishing
     * under the environment variable's name reaches nothing.
     */
    private static final String TESTCONTAINERS_DOCKER_HOST = "docker.host";

    /** docker-java's key, kept so the underlying client agrees with Testcontainers. */
    private static final String DOCKER_HOST = "DOCKER_HOST";

    private static final long CLI_TIMEOUT_SECONDS = 5;
    private static final String PREFIX = "[juriscore] ";

    /** Where Docker Desktop and Homebrew put the CLI when it is not on a forked JVM's PATH. */
    private static final List<String> CLI_CANDIDATES = List.of(
            "docker",
            System.getProperty("user.home") + "/.docker/bin/docker",
            "/usr/local/bin/docker",
            "/opt/homebrew/bin/docker",
            "/Applications/Docker.app/Contents/Resources/bin/docker");

    /** Sockets Docker publishes, most recent layout first. */
    private static final List<String> SOCKET_CANDIDATES = List.of(
            System.getProperty("user.home") + "/.docker/run/docker.sock",
            "/var/run/docker.sock");

    private static final Pattern HOST_IN_META =
            Pattern.compile("\"Host\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CURRENT_CONTEXT =
            Pattern.compile("\"currentContext\"\\s*:\\s*\"([^\"]+)\"");

    private static boolean done;

    /**
     * The endpoint this class settled on, however it was settled — including one it was
     * told rather than one it found.
     *
     * <p>Recorded so that {@link DockerApiVersion} can negotiate against the same daemon
     * Testcontainers will use. Deriving it a second time there would risk the two
     * disagreeing, which is the kind of divergence that produces a fix that works on the
     * machine it was written on.
     */
    private static String resolvedEndpoint;

    private DockerEnvironment() {
    }

    /**
     * The Docker endpoint in force, or empty if none could be determined.
     *
     * <p>Triggers resolution if it has not happened yet, so callers cannot accidentally
     * read this before it is decided.
     */
    static Optional<String> endpoint() {
        ensureConfigured();
        return Optional.ofNullable(resolvedEndpoint);
    }

    /** Idempotent; the first caller decides and later calls are no-ops. */
    static synchronized void ensureConfigured() {
        if (done) {
            return;
        }
        done = true;

        String explicit = explicitDockerHost();
        if (explicit != null) {
            say("Docker endpoint already configured by " + explicit + "; leaving it alone.");
            return;
        }

        String endpoint = fromDockerCli();
        if (endpoint == null) {
            endpoint = fromContextMetadata();
        }
        if (endpoint == null) {
            endpoint = fromKnownSockets();
        }

        if (endpoint == null) {
            say("Could not determine a Docker endpoint from the CLI, the context metadata, "
                    + "or the usual socket locations. Letting Testcontainers try its own discovery.");
            return;
        }

        publish(endpoint);
    }

    /**
     * Hands the endpoint to Testcontainers.
     *
     * <p>Under {@code docker.host} first, because that is the key Testcontainers' own
     * configuration is keyed on. An earlier version published only under
     * {@code DOCKER_HOST}, which was wrong twice over: Testcontainers does not read that
     * name as a Java property, and a system property is not a substitute for the
     * environment variable of the same name — a JVM cannot set an environment variable
     * for itself, so nothing was listening. The resolver printed the right endpoint and
     * Testcontainers went on probing sockets, which is exactly what was observed.
     *
     * <p>{@code DOCKER_HOST} is still set so docker-java's own defaulting agrees with
     * Testcontainers rather than contradicting it.
     */
    private static void publish(String endpoint) {
        resolvedEndpoint = endpoint;
        System.setProperty(TESTCONTAINERS_DOCKER_HOST, endpoint);
        System.setProperty(DOCKER_HOST, endpoint);
        String alsoInjected = injectIntoTestcontainersConfiguration(endpoint);

        say("Docker endpoint: " + endpoint);
        say("Published as system properties '" + TESTCONTAINERS_DOCKER_HOST + "' and '"
                + DOCKER_HOST + "'" + (alsoInjected == null ? "." : ", " + alsoInjected));
    }

    /**
     * Also places {@code docker.host} into the in-memory configuration Testcontainers
     * consults, for the case where it reads its properties sources but not system
     * properties.
     *
     * <p>Done reflectively and defensively on purpose: it adds no compile-time coupling to
     * Testcontainers internals, so a change in that library can never break this build —
     * the worst case is that this returns null and the system properties above carry the
     * configuration alone. It mutates the loaded configuration in memory only; nothing is
     * written to {@code ~/.testcontainers.properties} or anywhere else on disk, so no
     * machine-level state is created.
     */
    private static String injectIntoTestcontainersConfiguration(String endpoint) {
        try {
            Class<?> type = Class.forName("org.testcontainers.utility.TestcontainersConfiguration");
            Object configuration = type.getMethod("getInstance").invoke(null);
            var reached = new java.util.ArrayList<String>();
            for (String source : new String[] {"userProperties", "classpathProperties"}) {
                java.util.Properties properties = propertiesOf(type, configuration, source);
                if (properties != null) {
                    properties.setProperty(TESTCONTAINERS_DOCKER_HOST, endpoint);
                    reached.add(source);
                }
            }
            return reached.isEmpty() ? null : "and into Testcontainers' " + reached + ".";
        } catch (Throwable ignored) {
            // A best-effort assist must never be able to fail a build.
            return null;
        }
    }

    /**
     * Prints what Testcontainers <em>itself</em> resolves for {@code docker.host}, read back
     * through its own configuration object rather than through {@link System#getProperty}.
     *
     * <p>This is the check that was missing. Publishing was write-only: nothing ever asked
     * Testcontainers what it had ended up with, so a value that never landed looked exactly
     * like a value that did. Its own lookup consults, in order, the environment variable
     * {@code TESTCONTAINERS_DOCKER_HOST}, then the properties loaded from
     * {@code ~/.testcontainers.properties} — and notably <em>not</em> system properties, so
     * both {@code -Ddocker.host=...} and {@code System.setProperty("docker.host", ...)} are
     * invisible to it. Reading back through the same API it uses is the only way to know.
     */
    static void reportTestcontainersView() {
        try {
            Class<?> type = Class.forName("org.testcontainers.utility.TestcontainersConfiguration");
            Object configuration = type.getMethod("getInstance").invoke(null);

            say("What Testcontainers itself resolves:");
            say("  env TESTCONTAINERS_DOCKER_HOST      = "
                    + describe(System.getenv("TESTCONTAINERS_DOCKER_HOST")));
            Path userProperties = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
            say("  ~/.testcontainers.properties        = "
                    + (Files.exists(userProperties) ? "present" : "absent"));
            say("  getEnvVarOrUserProperty(docker.host) = "
                    + describe(lookup(type, configuration, "getEnvVarOrUserProperty")));
            say("  getEnvVarOrProperty(docker.host)     = "
                    + describe(lookup(type, configuration, "getEnvVarOrProperty")));
            say("  (the first of those two is the one the client strategy calls)");
        } catch (Throwable t) {
            say("Could not read Testcontainers' configuration: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    private static String lookup(Class<?> type, Object configuration, String method) {
        try {
            Object value = type.getMethod(method, String.class, String.class)
                    .invoke(configuration, TESTCONTAINERS_DOCKER_HOST, null);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<" + method + " unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String describe(String value) {
        return isSet(value) ? value : "<not set>";
    }

    private static java.util.Properties propertiesOf(Class<?> type, Object instance, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Object value = type.getMethod(getter).invoke(instance);
            if (value instanceof java.util.Properties properties) {
                return properties;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through to the field
        }
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(instance);
            return value instanceof java.util.Properties properties ? properties : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Honours either name, so {@code -Ddocker.host=...} works as well as the environment
     * variable. Returns a description naming the source, so the log says which one won
     * rather than attributing every case to DOCKER_HOST.
     */
    private static String explicitDockerHost() {
        String fromEnv = System.getenv(DOCKER_HOST);
        if (isSet(fromEnv)) {
            resolvedEndpoint = fromEnv.trim();
            return "environment variable " + DOCKER_HOST + "=" + fromEnv;
        }
        String fromTestcontainers = System.getProperty(TESTCONTAINERS_DOCKER_HOST);
        if (isSet(fromTestcontainers)) {
            resolvedEndpoint = fromTestcontainers.trim();
            return "system property " + TESTCONTAINERS_DOCKER_HOST + "=" + fromTestcontainers;
        }
        String fromDockerJava = System.getProperty(DOCKER_HOST);
        if (isSet(fromDockerJava)) {
            resolvedEndpoint = fromDockerJava.trim();
            return "system property " + DOCKER_HOST + "=" + fromDockerJava;
        }
        return null;
    }

    // ------------------------------------------------------------------ resolution

    /** The authoritative answer, when the CLI can be found and run. */
    private static String fromDockerCli() {
        for (String cli : CLI_CANDIDATES) {
            if (!cli.equals("docker") && !new File(cli).canExecute()) {
                continue;
            }
            String endpoint = runContextInspect(cli);
            if (endpoint != null) {
                return usable(endpoint, "docker context inspect (" + cli + ")");
            }
        }
        return null;
    }

    /**
     * Reads what {@code docker context inspect} would have told us, straight off disk.
     *
     * <p>A forked test JVM does not always inherit the PATH that the developer's shell
     * has, and Docker Desktop installs its CLI outside the system directories. The
     * metadata is the same data the CLI reads, so this stays authoritative rather than
     * becoming a guess.
     */
    private static String fromContextMetadata() {
        Path dockerConfigDir = Path.of(System.getProperty("user.home"), ".docker");
        String currentContext = readCurrentContextName(dockerConfigDir.resolve("config.json"));
        if (currentContext == null || currentContext.equals("default")) {
            // "default" has no metadata directory; it means the built-in endpoint.
            return null;
        }
        Path metaRoot = dockerConfigDir.resolve("contexts").resolve("meta");
        if (!Files.isDirectory(metaRoot)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(metaRoot)) {
            for (Path dir : dirs.toList()) {
                Path meta = dir.resolve("meta.json");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                String json = Files.readString(meta, StandardCharsets.UTF_8);
                if (!json.contains("\"" + currentContext + "\"")) {
                    continue;
                }
                Matcher host = HOST_IN_META.matcher(json);
                if (host.find()) {
                    return usable(host.group(1), "context metadata for '" + currentContext + "'");
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Last resort: the sockets Docker is known to publish. */
    private static String fromKnownSockets() {
        for (String socket : SOCKET_CANDIDATES) {
            if (Files.exists(Path.of(socket))) {
                return usable("unix://" + socket, "known socket location");
            }
        }
        return null;
    }

    // --------------------------------------------------------------------- helpers

    private static String readCurrentContextName(Path configJson) {
        if (!Files.isRegularFile(configJson)) {
            return null;
        }
        try {
            Matcher matcher = CURRENT_CONTEXT.matcher(Files.readString(configJson, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Accepts an endpoint only if it could actually work — a unix endpoint naming a socket
     * that is not there would swap one confusing failure for another.
     */
    private static String usable(String endpoint, String source) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        if (endpoint.startsWith("unix://")) {
            Path socket = Path.of(endpoint.substring("unix://".length()));
            if (!Files.exists(socket)) {
                say("Ignoring " + endpoint + " from " + source + ": that socket does not exist.");
                return null;
            }
            return endpoint;
        }
        if (endpoint.startsWith("tcp://") || endpoint.startsWith("npipe://")) {
            return endpoint;
        }
        say("Ignoring unrecognised endpoint '" + endpoint + "' from " + source + ".");
        return null;
    }

    private static String runContextInspect(String cli) {
        try {
            Process process = new ProcessBuilder(
                    cli, "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
                    // Discarded rather than merged: stderr must not end up in the value we
                    // parse, and a full stderr pipe must not be able to block the process.
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();

            // Wait before reading. Draining the stream first would block until EOF, which
            // never arrives while the process is alive — the timeout below would then be
            // unreachable and a wedged Docker CLI (a paused VM, a daemon mid-start) would
            // hang the JVM before any test ran.
            if (!process.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (var stream = process.getInputStream()) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static void say(String message) {
        // Printed, not logged: this runs before any test logging is configured, and it is
        // the line that explains the run when Docker discovery goes wrong.
        System.out.println(PREFIX + message);
    }
}
