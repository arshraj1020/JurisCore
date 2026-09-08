package com.juriscore.app.ratelimit;

/**
 * What the distributed limiter was able to determine about a request.
 *
 * <p>Three states rather than a boolean, because "allow this request" and "this caller is
 * within their budget" are different facts and the difference is the whole security
 * question: when Redis cannot answer, the caller's budget is <em>unknown</em>, and what to
 * do about that depends on what the endpoint protects.
 */
public enum RateLimitOutcome {

    /** Redis answered, and the caller is within the limit. */
    ALLOWED,

    /** Redis answered, and the caller has exceeded the limit. */
    LIMITED,

    /** Redis could not answer. The caller's usage is unknown, not zero. */
    UNAVAILABLE
}
