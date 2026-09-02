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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust boundary from the trusted side, over real HTTP.
 *
 * <p>The other half of {@link ProxyTrustBoundaryIT}. Ignoring forwarding headers is easy;
 * the point of the boundary is that a real client behind a real load balancer is still
 * identified correctly, or the rate limiter would put every customer of the platform into
 * one bucket keyed on the ALB and the first busy firm would lock everyone else out.
 *
 * <p>Here loopback <em>is</em> configured as an internal proxy, so the test client stands
 * where the ALB stands. {@code RemoteIpValve} then walks {@code X-Forwarded-For} from the
 * right, discarding entries that match {@code internal-proxies}, and stops at the first
 * entry that does not:
 *
 * <pre>
 *   X-Forwarded-For: 1.2.3.4, 203.0.113.7, 127.0.0.1
 *                    ^^^^^^^  ^^^^^^^^^^^  ^^^^^^^^^
 *                    injected  appended by  the proxy
 *                    by the    the trusted  itself:
 *                    caller    proxy:       trusted,
 *                              THE CLIENT   skipped
 * </pre>
 *
 * <p>That direction of travel is the whole security property. An AWS ALB <em>appends</em>
 * to this header rather than replacing it, so the leftmost entry always belongs to whoever
 * sent the request — which is exactly what the old {@code framework} strategy believed.
 * Anchoring on the right means the attacker's entries sit behind the one the proxy wrote
 * and are never reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyTrustBoundaryTrustedProxyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProxyTrustBoundaryTrustedProxyIT.class);

    private static final String KEY_PATTERN = "ratelimit:*";

    /** Stands in for the address an ALB would append: the real client. */
    private static final String CLIENT = "203.0.113.7";

    /** What an attacker prepends, hoping it will be believed. */
    private static final String INJECTED = "1.2.3.4";

    private static final String LOGIN_BODY = """
            {"email": "nobody@example.test", "password": "Wr0ng!Password123"}
            """;

    /**
     * Puts the test client inside the trust boundary, where the load balancer sits.
     *
     * <p>Both loopback spellings, because whether the connection presents as 127.0.0.1 or
     * as ::1 depends on the host's stack and has nothing to do with what is being tested.
     */
    @DynamicPropertySource
    static void loopbackIsATrustedProxy(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.remoteip.internal-proxies",
                () -> "127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|0:0:0:0:0:0:0:1|::1");
        registry.add("juriscore.rate-limit.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
    @DisplayName("a trusted proxy's chain resolves to the client it appended, and the bucket follows it")
    void resolvesTheClientAddressBehindATrustedProxy() {
        Set<String> keys = bucketsFor("X-Forwarded-For: " + CLIENT + ", 127.0.0.1",
                CLIENT + ", 127.0.0.1");

        assertThat(keys)
                .as("the trailing 127.0.0.1 is the proxy and is skipped; %s is the client it appended, "
                        + "and the rate-limit bucket must be keyed on that", CLIENT)
                .containsExactly("ratelimit:auth:ip:" + CLIENT);
    }

    @Test
    @DisplayName("an address injected to the left of a trusted chain is never reached")
    void anInjectedLeftmostAddressIsNotBelieved() {
        // The attack the previous configuration fell to, run against the new one. A caller
        // sends X-Forwarded-For: 1.2.3.4; the ALB appends the caller's real address, so the
        // application receives "1.2.3.4, <real client>, <alb>". Reading left to right, as
        // the framework strategy did, believes 1.2.3.4. Reading right to left stops at the
        // first entry the proxy chain did not vouch for, which is the real client.
        Set<String> keys = bucketsFor(
                "X-Forwarded-For: " + INJECTED + ", " + CLIENT + ", 127.0.0.1",
                INJECTED + ", " + CLIENT + ", 127.0.0.1");

        assertThat(keys)
                .as("bucketed as %s. The address the trusted proxy appended is %s; %s was injected by "
                        + "the caller and must never be reached", keys, CLIENT, INJECTED)
                .containsExactly("ratelimit:auth:ip:" + CLIENT);
        assertThat(keys.iterator().next()).doesNotContain(INJECTED);
    }

    @Test
    @DisplayName("two clients behind the same proxy get two buckets, not one shared with the proxy")
    void clientsBehindOneProxyAreBudgetedSeparately() {
        // Without this, "ignore the header" would be a perfectly secure and completely
        // unusable rate limiter: every request through the ALB would share one bucket.
        flushBuckets();
        signIn(CLIENT + ", 127.0.0.1");
        signIn("198.51.100.4, 127.0.0.1");

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        log.info("[trust boundary] two clients behind one trusted proxy -> {}", keys);

        assertThat(keys)
                .as("each client behind the proxy must have its own budget")
                .containsExactlyInAnyOrder(
                        "ratelimit:auth:ip:" + CLIENT,
                        "ratelimit:auth:ip:198.51.100.4");
    }

    @Test
    @DisplayName("a trusted proxy that sends no chain leaves the peer address alone")
    void noHeaderFromATrustedProxyChangesNothing() {
        // RemoteIpValve only calls setRemoteAddr when the header yielded something, so a
        // trusted peer with no X-Forwarded-For is still bucketed as itself.
        Set<String> keys = bucketsFor("no forwarding headers", null);

        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next())
                .as("with nothing to resolve, the connection's own address stands")
                .startsWith("ratelimit:auth:ip:")
                .doesNotContain(CLIENT);
    }

    // ------------------------------------------------------------------------ helpers

    private Set<String> bucketsFor(String label, String forwardedFor) {
        flushBuckets();
        ResponseEntity<String> response = signIn(forwardedFor);
        assertThat(response.getStatusCode().value())
                .as("the probe must reach the application, not be rejected before it")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        log.info("[trust boundary] {} -> {}", label, keys);
        return keys;
    }

    private ResponseEntity<String> signIn(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(LOGIN_BODY, headers), String.class);
    }
}
