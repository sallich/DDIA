package ru.itmo.ddia.resilience

/** A resource one request can run out of */
enum class BudgetKind {
    SUBREQUESTS,
    FAST_ERRORS,
    FAILURES,
    TIMEOUTS,
    LATENCY,
    // No server the request is allowed to send to
    AVAILABLE_SERVERS,
}

/**
 * A request ended without a response because a resource ran out.
 *
 * [lastFailure] is absent when nothing was sent at all, and when the latency budget expired while a subrequest was still in flight
 */
class BudgetExhaustedException(
    val budget: BudgetKind,
    val lastFailure: SubrequestOutcome.Failure? = null,
) : Exception(
        "Request gave up: $budget exhausted" +
            (lastFailure?.let { ", last subrequest ended with $it" } ?: ""),
    )