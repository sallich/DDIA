package ru.itmo.ddia.resilience.budget

import kotlin.time.Duration

/**
 * How many subrequests one request may spend.
 *
 * The three per-class limits refine [maxSubrequests], so each defaults to it:
 * class is bounded only by the total unless given a tighter limit of its own
 */
data class RetryBudget(
    val maxSubrequests: Int,
    val maxFastErrors: Int = maxSubrequests,
    val maxFailures: Int = maxSubrequests,
    val maxTimeouts: Int = maxSubrequests,
) {
    init {
        require(maxSubrequests >= 1) { "maxSubrequests must be at least 1, got $maxSubrequests" }
        require(maxFastErrors >= 0) { "maxFastErrors must not be negative, got $maxFastErrors" }
        require(maxFailures >= 0) { "maxFailures must not be negative, got $maxFailures" }
        require(maxTimeouts >= 0) { "maxTimeouts must not be negative, got $maxTimeouts" }
    }

    companion object {
        // retry budget of the default strategy
        val SINGLE_SUBREQUEST = RetryBudget(maxSubrequests = 1)
    }
}

/**
 * How long one request may wait, in total and per subrequest.
 *
 * Neither has to be smaller than the other: whichever is tighter binds first
 */
data class LatencyBudget(
    val total: Duration,
    val perSubrequest: Duration = total,
) {
    init {
        require(total > Duration.ZERO) { "total latency budget must be positive, got $total" }
        require(perSubrequest > Duration.ZERO) {
            "perSubrequest latency budget must be positive, got $perSubrequest"
        }
    }
}

/** Every budget one request is given */
data class BudgetLimits(
    val retries: RetryBudget,
    val latency: LatencyBudget,
)