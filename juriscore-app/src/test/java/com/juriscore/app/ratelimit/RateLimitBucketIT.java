package com.juriscore.app.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A caller must not be able to choose their own rate-limit bucket.
 *
 * <h2>What this used to prove, and what it proves now</h2>
 *
 * <p>The first version of this class was an investigation, and it found a live bypass:
 * {@code X-Forwarded-For: 203.0.113.7} produced {@code ratelimit:auth:ip:203.0.113.7},
 * {@code Forwarded: for=192.0.2.55} produced {@code ratelimit:auth:ip:192.0.2.55}, and
 * twelve requests carrying twelve different values produced twelve buckets and not one
 * HTTP 429. Two independent mechanisms were responsible — Spring's
 * {@code ForwardedHeaderFilter}, enabled by {@code forward-headers-strategy: framework},
 * rewriting {@code getRemoteAddr()} from the leftmost entry; and
 * {@code RateLimitFilter.clientIp()} parsing the same header itself. Neither asked whether
 * the request had come from a proxy worth believing.
 *
 * <p>The same probes are kept, with their assertions inverted: the bucket must now stay on
 * the peer address whatever the caller puts in a header. They are the regression test.
 *
 * <h2>What this class covers, and what it does not</h2>
 *
 * <p>It covers the application's half of the guarantee: no JurisCore code reads a
 * forwarding header, so with no trusted proxy in front, headers are inert and the bucket
 * follows the connection. {@link #theBucketFollowsTheConnectionNotTheHeaders()} pins that
 * directly by moving the peer address and watching the bucket move with it.
 *
 * <p>It does <em>not</em> cover Tomcat's {@code RemoteIpValve} resolving a real client
 * address from behind a trusted proxy, because MockMvc has no valve — there is no servlet
 * container in this context at all. That half is configuration
 * ({@code server.tomcat.remoteip.internal-proxies}) plus well-tested upstream code, and
 * pinning it needs a {@code webEnvironment = RANDOM_PORT} test that drives real HTTP. That
 * gap is stated here rather than papered over: a test that passes because the mechanism it
 * targets was absent is exactly the failure mode that let the account-lockout bug survive.
 */
@TestPropertySource(properties = "juriscore.rate-limit.enabled=true")
class RateLimitBucketIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitBucketIT.class);

    private static final String KEY_PATTERN = "ratelimit:*";

    /** An address that does not exist, so every probe costs one dummy hash and nothing else. */
    private static final String LOGIN_BODY = """
            {"email": "nobody@example.test", "password": "Wr0ng!Password123"}
            """;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void flushBuckets() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("Spring's unconditionally-trusting forwarded-header filter is no longer registered")
    void forwardedHeaderFilterIsNotRegistered() {
        List<String> found = forwardedHeaderFilterRegistrations();
        log.info("[bucket probe] ForwardedHeaderFilter registrations: {}", found.isEmpty() ? "NONE" : found);

        assertThat(found)
                .as("forward-headers-strategy is now `native`, so the client address is resolved by "
                        + "Tomcat's RemoteIpValve behind the internal-proxies trust boundary. If "
                        + "ForwardedHeaderFilter is back, the strategy has been reverted to `framework` "
                        + "and every assertion below is protecting nothing: that filter has no concept "
                        + "of a trusted proxy and takes the leftmost X-Forwarded-For entry, which is the "
                        + "one a caller writes")
                .isEmpty();
    }

    @Test
    @DisplayName("baseline: with no forwarding headers, one request creates one bucket")
    void baselineBucketIsCreated() throws Exception {
        Set<String> keys = bucketsFor("no forwarding headers", request -> { });

        assertThat(keys)
                .as("no ratelimit:* key was created at all, which means RateLimitFilter never ran. "
                        + "Nothing else in this class can be concluded until that is fixed")
                .hasSize(1);
        assertThat(keys.iterator().next()).startsWith("ratelimit:auth:ip:");
    }

    @Test
    @DisplayName("a spoofed single-value X-Forwarded-For does not move the bucket")
    void singleValueXForwardedForDoesNotMoveTheBucket() throws Exception {
        Set<String> baseline = bucketsFor("no forwarding headers", request -> { });
        Set<String> spoofed = bucketsFor("X-Forwarded-For: 203.0.113.7",
                request -> request.header("X-Forwarded-For", "203.0.113.7"));

        assertThat(spoofed)
                .as("the caller sent X-Forwarded-For: 203.0.113.7 and was bucketed as %s instead of %s. "
                        + "If those differ the header is choosing the bucket again", spoofed, baseline)
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("a multi-value X-Forwarded-For does not move the bucket")
    void multiValueXForwardedForDoesNotMoveTheBucket() throws Exception {
        Set<String> baseline = bucketsFor("no forwarding headers", request -> { });
        Set<String> spoofed = bucketsFor("X-Forwarded-For: 203.0.113.7, 198.51.100.4",
                request -> request.header("X-Forwarded-For", "203.0.113.7, 198.51.100.4"));

        assertThat(spoofed)
                .as("bucketed as %s rather than %s. A load balancer appends to this header rather than "
                        + "replacing it, so the leftmost element is always the caller's to write — "
                        + "which is why no element of it may be believed here", spoofed, baseline)
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("an RFC 7239 Forwarded header does not move the bucket")
    void rfc7239ForwardedHeaderDoesNotMoveTheBucket() throws Exception {
        Set<String> baseline = bucketsFor("no forwarding headers", request -> { });
        Set<String> forwarded = bucketsFor("Forwarded: for=192.0.2.55",
                request -> request.header("Forwarded", "for=192.0.2.55"));

        assertThat(forwarded)
                .as("bucketed as %s rather than %s. No JurisCore code reads the Forwarded header, so if "
                        + "this moved the bucket then Spring's ForwardedHeaderFilter is back in the chain",
                        forwarded, baseline)
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("the bucket follows the connection: move the peer, and the bucket moves with it")
    void theBucketFollowsTheConnectionNotTheHeaders() throws Exception {
        // The positive half of the guarantee. The tests above show headers cannot move the
        // bucket; without this one they would all still pass if the bucket were a constant.
        Set<String> keys = bucketsFor("peer 198.51.100.9, plus a contradictory X-Forwarded-For",
                request -> request
                        .header("X-Forwarded-For", "203.0.113.7")
                        .with(raw -> {
                            raw.setRemoteAddr("198.51.100.9");
                            return raw;
                        }));

        assertThat(keys)
                .as("the bucket must be keyed on the address that opened the connection")
                .containsExactly("ratelimit:auth:ip:198.51.100.9");
    }

    @Test
    @DisplayName("rotating X-Forwarded-For no longer buys a fresh budget")
    void rotatingTheHeaderDoesNotMintFreshBudgets() throws Exception {
        int limit = rateLimitProperties.getAuthRequestsPerWindow();
        int attempts = limit + 2;

        List<Integer> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < attempts; attempt++) {
            statuses.add(mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Forwarded-For", "203.0.113." + attempt)
                            .content(LOGIN_BODY))
                    .andReturn().getResponse().getStatus());
        }

        Set<String> buckets = redisTemplate.keys(KEY_PATTERN);
        log.info("[bucket probe] {} requests, a different X-Forwarded-For on each -> statuses {}",
                attempts, statuses);
        log.info("[bucket probe] buckets created: {}", buckets);

        assertThat(buckets)
                .as("%d requests carrying %d different X-Forwarded-For values must all land in one "
                        + "bucket; %s were created", attempts, attempts, buckets.size())
                .hasSize(1);
        assertThat(statuses)
                .as("%d sign-in attempts against a limit of %d per window, each with a different "
                        + "X-Forwarded-For. Without a 429 among them, varying the header still buys an "
                        + "unlimited budget", attempts, limit)
                .contains(429);
    }

    @Test
    @DisplayName("an authenticated caller is budgeted by user id, never by address")
    void anAuthenticatedCallerIsBudgetedByUserId() throws Exception {
        // The counterpart to everything above. Anonymous traffic falls back to the address
        // because there is nothing better; an authenticated caller must not, or a whole firm
        // behind one office NAT would share a single budget and the first busy user would
        // lock out their colleagues. Nothing covered this until now.
        String accessToken = objectMapper.readTree(
                        mockMvc.perform(post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                  "firmName": "Sharma & Associates",
                                                  "firstName": "Asha",
                                                  "lastName": "Menon",
                                                  "email": "asha@sharma-legal.test",
                                                  "password": "Adv0cate!Chamber",
                                                  "timezone": "Asia/Kolkata"
                                                }
                                                """))
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        String userId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM identity.users WHERE email = ?",
                String.class, "asha@sharma-legal.test");

        // Registration itself was rate limited on the anonymous bucket; start clean.
        flushBuckets();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        // A forwarding header must not matter either way for an authenticated call.
                        .header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk());

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        log.info("[bucket probe] authenticated caller -> {}", keys);

        assertThat(keys)
                .as("an authenticated request must be bucketed on the caller's stable user id")
                .containsExactly("ratelimit:api:user:" + userId);
        assertThat(keys.iterator().next())
                .as("it must not fall back to an address bucket")
                .doesNotContain(":ip:");
    }

    // ------------------------------------------------------------------------ helpers

    @SuppressWarnings("rawtypes")
    private List<String> forwardedHeaderFilterRegistrations() {
        List<String> found = new ArrayList<>(
                List.of(applicationContext.getBeanNamesForType(ForwardedHeaderFilter.class)));
        applicationContext.getBeansOfType(FilterRegistrationBean.class)
                .forEach((name, registration) -> {
                    if (registration.getFilter() instanceof ForwardedHeaderFilter) {
                        found.add(name + " (registration, enabled=" + registration.isEnabled() + ")");
                    }
                });
        return found;
    }

    /** Flushes Redis, sends exactly one sign-in attempt, and returns the buckets it created. */
    private Set<String> bucketsFor(String label, Consumer<MockHttpServletRequestBuilder> customiser)
            throws Exception {
        flushBuckets();

        MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY);
        customiser.accept(request);

        mockMvc.perform(request).andExpect(status().isUnauthorized());

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        log.info("[bucket probe] {} -> {}", label, keys);
        return keys;
    }
}
