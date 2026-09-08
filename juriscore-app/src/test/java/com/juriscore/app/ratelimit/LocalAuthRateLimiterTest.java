package com.juriscore.app.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standby limiter that keeps sign-in bounded while Redis is down.
 *
 * <p>Two properties matter and they pull against each other: it has to actually limit
 * (otherwise a Redis outage is a brute-force window), and it has to stay bounded
 * (otherwise the fix for a rate-limit bypass is a memory-exhaustion bug, reachable by the
 * same anonymous caller). Both are pinned below.
 */
class LocalAuthRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private RateLimitProperties properties;
    private LocalAuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        limiter = new LocalAuthRateLimiter(properties);
    }

    @Nested
    @DisplayName("limiting")
    class Limiting {

        @Test
        @DisplayName("allows up to the limit and refuses the next attempt")
        void enforcesTheLimit() {
            for (int attempt = 1; attempt <= 10; attempt++) {
                assertThat(limiter.tryAcquire("auth:ip:198.51.100.7", 10, WINDOW))
                        .as("attempt %d of 10 should be allowed", attempt)
                        .isTrue();
            }

            assertThat(limiter.tryAcquire("auth:ip:198.51.100.7", 10, WINDOW))
                    .as("the eleventh attempt in the window is over budget")
                    .isFalse();
        }

        @Test
        @DisplayName("budgets each caller separately")
        void bucketsAreIndependent() {
            for (int i = 0; i < 10; i++) {
                limiter.tryAcquire("auth:ip:198.51.100.7", 10, WINDOW);
            }

            // One address exhausting its budget must not lock out the rest of the internet.
            assertThat(limiter.tryAcquire("auth:ip:203.0.113.9", 10, WINDOW)).isTrue();
        }

        @Test
        @DisplayName("starts a fresh budget once the window has passed")
        void windowRolls() {
            Duration shortWindow = Duration.ofMillis(60);
            for (int i = 0; i < 3; i++) {
                limiter.tryAcquire("auth:ip:198.51.100.7", 3, shortWindow);
            }
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.7", 3, shortWindow)).isFalse();

            await(80);

            assertThat(limiter.tryAcquire("auth:ip:198.51.100.7", 3, shortWindow))
                    .as("a fixed window resets; a locked-out caller must recover on its own")
                    .isTrue();
        }

        @Test
        @DisplayName("counts atomically when the same caller arrives in parallel")
        void isAtomicUnderConcurrency() throws Exception {
            int limit = 50;
            int attempts = 400;

            try (var pool = Executors.newFixedThreadPool(16)) {
                List<Callable<Boolean>> tasks = IntStream.range(0, attempts)
                        .<Callable<Boolean>>mapToObj(i ->
                                () -> limiter.tryAcquire("auth:ip:198.51.100.7", limit, WINDOW))
                        .toList();

                long allowed = 0;
                for (Future<Boolean> future : pool.invokeAll(tasks)) {
                    if (future.get()) {
                        allowed++;
                    }
                }

                // A read-modify-write on an unsynchronised counter lets more than `limit`
                // through here — which is precisely the budget an attacker would want.
                assertThat(allowed).isEqualTo(limit);
            }
        }
    }

    @Nested
    @DisplayName("bounded storage")
    class Bounded {

        @Test
        @DisplayName("never tracks more buckets than the configured ceiling")
        void staysWithinTheCap() {
            properties.setFallbackMaxEntries(100);

            // Ten thousand distinct addresses, as a spraying attacker would produce.
            for (int i = 0; i < 10_000; i++) {
                limiter.tryAcquire("auth:ip:10.0." + (i / 256) + "." + (i % 256), 10, WINDOW);
            }

            assertThat(limiter.trackedBuckets())
                    .as("an unbounded map here would be a memory-exhaustion bug reachable "
                            + "by any anonymous caller")
                    .isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("refuses unseen callers at capacity rather than admitting them untracked")
        void failsClosedAtCapacity() {
            properties.setFallbackMaxEntries(2);

            assertThat(limiter.tryAcquire("auth:ip:198.51.100.1", 10, WINDOW)).isTrue();
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.2", 10, WINDOW)).isTrue();

            // Admitting this one untracked would mean the limiter stops limiting exactly
            // when it is under the most pressure — and an attacker could switch it off by
            // spraying addresses.
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.3", 10, WINDOW)).isFalse();
        }

        @Test
        @DisplayName("keeps serving callers it already knows when full")
        void alreadyTrackedCallersStillCounted() {
            properties.setFallbackMaxEntries(1);
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.1", 3, WINDOW)).isTrue();

            // Being at capacity must not freeze a known caller's counter, or the cap would
            // hand the first arrival an unlimited budget.
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.1", 3, WINDOW)).isTrue();
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.1", 3, WINDOW)).isTrue();
            assertThat(limiter.tryAcquire("auth:ip:198.51.100.1", 3, WINDOW)).isFalse();
        }

        @Test
        @DisplayName("reclaims expired buckets, so a quiet period costs nothing")
        void prunesExpiredEntries() {
            properties.setFallbackMaxEntries(50);
            Duration shortWindow = Duration.ofMillis(50);

            for (int i = 0; i < 50; i++) {
                limiter.tryAcquire("auth:ip:10.1.0." + i, 10, shortWindow);
            }
            assertThat(limiter.trackedBuckets()).isEqualTo(50);

            await(80);

            // At the cap, the sweep runs and finds every window expired — so a new caller
            // is admitted rather than refused, and the map does not grow.
            assertThat(limiter.tryAcquire("auth:ip:10.2.0.1", 10, shortWindow)).isTrue();
            assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(50);
        }
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
