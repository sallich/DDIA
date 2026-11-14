package ru.itmo.ddia.resilience.strategy

import ru.itmo.ddia.resilience.SubrequestOutcome
import kotlin.math.pow
import kotlin.time.Duration

/**
 * Decides how long a retrying strategy pauses between subrequests.
 *
 * One instance serves every request of its strategy, so a policy holds no per-request state and the
 * attempt number is passed in.
 */
interface BackoffPolicy {
    /** @param attempt 1-based number of the attempt that just failed */
    fun delayAfter(
        failure: SubrequestOutcome.Failure,
        attempt: Int,
    ): Duration
}

/** Retries immediately, which is plain retries without backoff */
object NoBackoff : BackoffPolicy {
    override fun delayAfter(
        failure: SubrequestOutcome.Failure,
        attempt: Int,
    ): Duration = Duration.ZERO
}

/**
 * Grows the pause geometrically: [base], then multiplied by [factor] each time, capped at
 * [maxDelay]. A [factor] of 1.0 gives a constant pause.
 *
 * A 4xx is answered with no pause: backing off gives an overloaded server room to recover, and a
 * 4xx is the request being wrong, which waiting does not improve.
 */
class ExponentialBackoff(
    private val base: Duration,
    private val factor: Double = 2.0,
    private val maxDelay: Duration = Duration.INFINITE,
) : BackoffPolicy {
    init {
        require(base > Duration.ZERO) { "base must be positive, got $base" }
        require(factor >= 1.0) { "factor must be at least 1.0, got $factor" }
        require(maxDelay >= base) { "maxDelay $maxDelay must not be shorter than base $base" }
    }

    override fun delayAfter(
        failure: SubrequestOutcome.Failure,
        attempt: Int,
    ): Duration =
        when (failure) {
            SubrequestOutcome.ClientError -> Duration.ZERO
            SubrequestOutcome.ServerError,
            SubrequestOutcome.Timeout,
            -> (base * factor.pow(attempt - 1)).coerceAtMost(maxDelay)
        }
}