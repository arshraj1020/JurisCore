package com.juriscore.app.ratelimit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The standby limiter for authentication endpoints, used only while Redis is unreachable.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link RedisRateLimiter} fails open, and for ordinary API traffic that is the right
 * call: a cache outage should degrade protection, not take the platform down. Applied to
 * {@code /api/v1/auth/**} the same rule is a hole with a trigger attached — an attacker
 * who can make Redis unavailable, or who simply waits for an outage, gets an
 * <em>unlimited</em> credential-stuffing budget against the endpoints most worth attacking.
 * "Fail open" and "the sign-in limit can be switched off from outside" are the same
 * sentence, and only one of them sounds acceptable.
 *
 * <p>So the failure policy is split by what is at stake:
 *
 * <ul>
 *   <li><strong>Authenticated API traffic</strong> — Redis down means no limiting. The
 *       caller already holds a valid token; the limit is there for fairness and runaway
 *       clients, and denying real work during an infrastructure incident is worse than the
 *       abuse it would prevent.</li>
 *   <li><strong>Authentication endpoints</strong> — Redis down means <em>this</em>, a
 *       per-instance limiter that keeps brute force bounded until Redis returns.</li>
 * </ul>
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not a replacement for the distributed limiter and does not pretend to be. Each
 * instance counts on its own, so with <em>n</em> instances behind the load balancer the
 * effective ceiling during an outage is <em>n</em> × the configured limit. That is a
 * deliberate, bounded degradation: ten instances at ten attempts a minute is a hundred, not
 * the unlimited budget the previous behaviour handed out, and it turns a
 * bypass-by-causing-an-outage into a constant factor.
 *
 * <h2>Bounded, and bounded on purpose</h2>
 *
 * <p>The key is attacker-influenced — it is derived from the peer address for anonymous
 * traffic — so an unbounded map here would replace a rate-limit bypass with a memory
 * exhaustion bug, and a worse one, because it would survive Redis coming back. Two rules
 * keep it finite:
 *
 * <ol>
 *   <li>Entries expire with their window and are pruned lazily, so a quiet period costs
 *       nothing and the map tracks the <em>active</em> callers rather than every caller
 *       ever seen.</li>
 *   <li>The map never exceeds {@code maxEntries}. Once it is full, a key that is not
 *       already tracked is <strong>denied</strong> rather than admitted untracked.</li>
 * </ol>
 *
 * <p>That second rule is the one worth defending, because it is the only place this class
 * refuses a request it cannot prove is over the limit. Reaching the cap means tens of
 * thousands of distinct addresses are hitting sign-in within one window on a single
 * instance, while Redis is also down. That is not what a law firm signing in looks like;
 * it is what a distributed attack looks like. Admitting the overflow untracked would mean
 * the limiter stops limiting exactly when it is needed, and an attacker who can spray
 * addresses could switch it off at will. Refusing costs a 429 and a retry to anyone caught
 * in a genuine stampede, and the window is a minute.
 */
@Component
@RequiredArgsConstructor
public class LocalAuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthRateLimiter.class);

    private final RateLimitProperties properties;

    /**
     * Active fixed windows, keyed by the same bucket string Redis would have used.
     *
     * <p>Held in a plain map rather than a cache library because the semantics that matter
     * — expire with the window, hard cap, atomic increment — are three lines each here and
     * a dependency plus a configuration object there.
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Rate-limits the log line as well: an outage should not also produce a log flood. */
    private final AtomicLong lastCapacityWarning = new AtomicLong(0);

    /**
     * @return true when the caller is within the limit and the request may proceed
     */
    public boolean tryAcquire(String bucket, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        // Pruning is driven by pressure rather than by a timer: no scheduled thread, no
        // work at all while the map is small, and a bounded sweep exactly when the cap is
        // in sight. The sweep is O(size), which is O(maxEntries) — a few thousand entries
        // once per full window, not per request.
        if (windows.size() >= properties.getFallbackMaxEntries()) {
            prune(now, windowMillis);
        }

        Window counter = windows.get(bucket);
        if (counter == null) {
            if (windows.size() >= properties.getFallbackMaxEntries()) {
                warnAtCapacity(now);
                return false;
            }
            // Racy against another thread inserting the same key, which putIfAbsent
            // resolves; the loser reuses the winner's window rather than overwriting it.
            counter = windows.computeIfAbsent(bucket, key -> new Window(now));
        }

        return counter.increment(now, windowMillis) <= limit;
    }

    /** Drops every window that has already expired. */
    private void prune(long now, long windowMillis) {
        Iterator<Map.Entry<String, Window>> entries = windows.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().hasExpired(now, windowMillis)) {
                entries.remove();
            }
        }
    }

    private void warnAtCapacity(long now) {
        long previous = lastCapacityWarning.get();
        if (now - previous > 60_000 && lastCapacityWarning.compareAndSet(previous, now)) {
            log.warn("Local auth rate-limit fallback is at capacity ({} buckets); "
                            + "further unseen callers are refused until Redis returns",
                    properties.getFallbackMaxEntries());
        }
    }

    /** Visible for tests: how many buckets are currently being tracked. */
    public int trackedBuckets() {
        return windows.size();
    }

    /** Visible for tests: forget everything, as a restart would. */
    public void reset() {
        windows.clear();
    }

    /**
     * One caller's fixed window.
     *
     * <p>Synchronised rather than built from atomics because the count and the window start
     * have to move together: rolling the window is "reset the count <em>and</em> the start",
     * and two atomics cannot do that pair without a lock anyway. The critical section is a
     * comparison and two field writes, held per bucket, so contention exists only between
     * requests from the same caller.
     */
    private static final class Window {

        private long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        synchronized int increment(long now, long windowMillis) {
            if (now - startedAt >= windowMillis) {
                startedAt = now;
                count = 0;
            }
            count += 1;
            return count;
        }

        synchronized boolean hasExpired(long now, long windowMillis) {
            return now - startedAt >= windowMillis;
        }
    }
}
