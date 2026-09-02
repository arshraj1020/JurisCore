package com.juriscore.app.ratelimit;

import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust boundary, from the untrusted side, over real HTTP.
 *
 * <h2>The invariant</h2>
 *
 * <p><strong>A caller may influence the client address only if the machine that opened the
 * connection is itself a trusted proxy.</strong> Not "if the header looks well-formed", not
 * "if the chain ends in a private address" — the question is asked of the TCP peer, which
 * is the one thing a remote attacker cannot choose.
 *
 * <p>Tomcat's {@code RemoteIpValve} asks it first and asks it of the peer:
 *
 * <pre>{@code
 * boolean isInternal = internalProxies.matcher(originalRemoteAddr).matches();
 * if (isInternal || trustedProxies.matcher(originalRemoteAddr).matches()) {
 *     // ... only here does it look at X-Forwarded-For at all
 * }
 * }</pre>
 *
 * <p>If that test fails the header is never read and {@code getRemoteAddr()} stays the
 * peer. This class configures {@code internal-proxies} so that loopback does <em>not</em>
 * match, which puts the test client on the outside of the boundary — the position of any
 * client on the internet — and shows that nothing it can put in a header moves it inside.
 *
 * <h2>Why this cannot be a MockMvc test</h2>
 *
 * <p>{@code RateLimitBucketIT} covers the application half: no JurisCore code parses a
 * forwarding header. It cannot cover this half, because MockMvc has no servlet container
 * and therefore no valve — the mechanism under test would simply be absent, and the test
 * would pass for the wrong reason. So this runs a real embedded Tomcat on a real port and
 * speaks real HTTP to it. Nothing is mocked and nothing is reflected: the address is
 * resolved by the production pipeline, and read back from the rate-limit key that
 * production code wrote into Redis.
 *
 * @see ProxyTrustBoundaryTrustedProxyIT the same headers from inside the boundary
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyTrustBoundaryIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProxyTrustBoundaryIT.class);

    private static final String KEY_PATTERN = "ratelimit:*";

    private static final String SPOOFED = "203.0.113.7";

    private static final String LOGIN_BODY = """
            {"email": "nobody@example.test", "password": "Wr0ng!Password123"}
            """;

    /**
     * Puts the test client outside the trust boundary.
     *
     * <p>Only 10/8 is trusted here, so the loopback address this test connects from is not
     * a proxy as far as the valve is concerned. Production's own regex is untouched — this
     * narrows trust for the test rather than widening it, so nothing here can make the
     * application more permissive than it ships.
     */
    @DynamicPropertySource
    static void loopbackIsNotATrustedProxy(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.remoteip.internal-proxies",
                () -> "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        registry.add("juriscore.rate-limit.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    /**
     * Swaps the HTTP client before any probe runs. Test harness only; nothing about the
     * application changes.
     *
     * <p>{@code TestRestTemplate} defaults to {@code SimpleClientHttpRequestFactory}, which
     * is backed by the JDK's {@code HttpURLConnection}, because no other HTTP client is on
     * this project's test classpath. As of Spring Framework 6.1 that factory <em>always</em>
     * streams the request body — {@code setBufferRequestBody} and {@code setOutputStreaming}
     * are both deprecated no-ops now, documented as "requests are never buffered" and
     * "requests are always streamed", so there is no longer a switch to turn this off.
     *
     * <p>{@code HttpURLConnection} reacts to a 401 by trying to authenticate and replay the
     * request. A streamed body cannot be replayed, so instead of returning the response it
     * throws {@code HttpRetryException: cannot retry due to server authentication, in
     * streaming mode} — which is what every probe here hit, before a single assertion ran.
     * The sign-in endpoint answers 401 by design, so every POST tripped it.
     *
     * <p>{@code JdkClientHttpRequestFactory} wraps {@code java.net.http.HttpClient}, which has
     * no automatic authenticate-and-retry behaviour and hands the 401 back as an ordinary
     * response. It needs no new dependency, and it does not touch the headers this class
     * exists to test: the JDK client refuses only {@code connection}, {@code content-length},
     * {@code expect}, {@code host} and {@code upgrade}, none of which are set here.
     *
     * <p>Worth knowing that no other integration test in this project could have hit this,
     * because every one of them uses MockMvc and never opens a socket. These are the first
     * tests that speak real HTTP, which is the entire point of them — the valve under test
     * lives in Tomcat, and MockMvc has no Tomcat.
     */
    @BeforeEach
    void useAnHttpClientThatDoesNotRetryOn401() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @BeforeEach
    void flushBuckets() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("baseline: the bucket is the address that opened the connection")
    void baselineIsThePeerAddress() {
        Set<String> keys = bucketsFor("no forwarding headers");

        assertThat(keys).hasSize(1);
        log.info("[trust boundary] peer bucket is {}", keys);
        // Not asserted as a literal: loopback presents as 127.0.0.1 or 0:0:0:0:0:0:0:1
        // depending on how the stack resolves it, and which one it is does not matter.
        assertThat(keys.iterator().next()).startsWith("ratelimit:auth:ip:");
    }

    @Test
    @DisplayName("a spoofed X-Forwarded-For is ignored when the peer is not a trusted proxy")
    void spoofedHeaderIsIgnoredFromAnUntrustedPeer() {
        Set<String> baseline = bucketsFor("no forwarding headers");
        Set<String> spoofed = bucketsFor("X-Forwarded-For: " + SPOOFED, "X-Forwarded-For", SPOOFED);

        assertThat(spoofed)
                .as("the caller claimed to be %s and was bucketed as %s; the peer bucket is %s. "
                        + "An untrusted caller must not be able to name its own address",
                        SPOOFED, spoofed, baseline)
                .isEqualTo(baseline);
        assertThat(spoofed.iterator().next())
                .as("the spoofed address must appear in no bucket name")
                .doesNotContain(SPOOFED);
    }

    @Test
    @DisplayName("even a well-formed proxy chain is ignored when the peer is not a trusted proxy")
    void wellFormedChainIsIgnoredFromAnUntrustedPeer() {
        // This is the sharp end of the invariant. The very same header resolves to
        // 203.0.113.7 in ProxyTrustBoundaryTrustedProxyIT. The header has not changed and
        // the request has not changed; the only difference between the two outcomes is
        // whether the peer was configured as a trusted proxy. That is what makes the
        // boundary the boundary, rather than the header's shape.
        Set<String> baseline = bucketsFor("no forwarding headers");
        Set<String> chained = bucketsFor("X-Forwarded-For: " + SPOOFED + ", 127.0.0.1",
                "X-Forwarded-For", SPOOFED + ", 127.0.0.1");

        assertThat(chained)
                .as("a chain ending in a private address still proves nothing when it arrives from an "
                        + "untrusted peer — anyone can write one. Bucketed as %s, peer bucket is %s",
                        chained, baseline)
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("an RFC 7239 Forwarded header is ignored: the valve does not read it at all")
    void rfc7239ForwardedHeaderIsIgnored() {
        Set<String> baseline = bucketsFor("no forwarding headers");
        Set<String> forwarded = bucketsFor("Forwarded: for=192.0.2.55", "Forwarded", "for=192.0.2.55");

        assertThat(forwarded)
                .as("RemoteIpValve reads only the configured remote-ip-header, and no JurisCore code "
                        + "reads Forwarded. Bucketed as %s, peer bucket is %s", forwarded, baseline)
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("rotating the header buys no extra budget: one bucket, and the limit still bites")
    void rotatingTheHeaderCannotEscapeTheLimit() {
        int limit = rateLimitProperties.getAuthRequestsPerWindow();
        int attempts = limit + 2;

        List<Integer> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < attempts; attempt++) {
            statuses.add(signIn("X-Forwarded-For", "203.0.113." + attempt).getStatusCode().value());
        }

        Set<String> buckets = redisTemplate.keys(KEY_PATTERN);
        log.info("[trust boundary] {} requests over real HTTP, one X-Forwarded-For each -> {}",
                attempts, statuses);
        log.info("[trust boundary] buckets created: {}", buckets);

        assertThat(buckets)
                .as("%d different claimed addresses must all land in the one real peer's bucket", attempts)
                .hasSize(1);
        assertThat(statuses)
                .as("%d attempts against a limit of %d, each claiming a different address. Without a 429 "
                        + "the sign-in limit can still be walked past", attempts, limit)
                .contains(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    // ------------------------------------------------------------------------ helpers

    private Set<String> bucketsFor(String label, String... header) {
        flushBuckets();
        ResponseEntity<String> response = header.length == 0 ? signIn() : signIn(header[0], header[1]);
        assertThat(response.getStatusCode().value())
                .as("the probe must reach the application, not be rejected before it")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        log.info("[trust boundary] {} -> {}", label, keys);
        return keys;
    }

    private ResponseEntity<String> signIn(String... header) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (header.length == 2) {
            headers.add(header[0], header[1]);
        }
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(LOGIN_BODY, headers), String.class);
    }
}
