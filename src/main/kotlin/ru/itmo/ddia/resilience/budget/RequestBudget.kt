package ru.itmo.ddia.resilience.budget

import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.SubrequestOutcome
import java.util.concurrent.atomic.AtomicInteger

/**
 * The budgets of one request while they are being spent.
 *
 * One instance per request, shared by every strategy taking part in it: that sharing is what keeps
 * a retry strategy nested in a multiclient from loading a server past what the request paid for.
 * Safe to use from several coroutines at once, because hedging spends the budget in parallel.
 */
class RequestBudget(
    val limits: BudgetLimits,
) {
    private val subrequests = Quota(limits.retries.maxSubrequests)
    private val fastErrors = Quota(limits.retries.maxFastErrors)
    private val failures = Quota(limits.retries.maxFailures)
    private val timeouts = Quota(limits.retries.maxTimeouts)

    val latency: LatencyBudget get() = limits.latency

    // Takes one subrequest, before it is sent, so a budget of N hands out exactly N
    fun tryReserveSubrequest(): Boolean = subrequests.tryTake()

    // read without a reservation, for deciding whether another server is worth trying
    val hasSubrequestsLeft: Boolean get() = subrequests.hasAnyLeft

    /**
     * Charges a failed subrequest to the sub-budget of its class.
     *
     * @return `null` when the charge fit, or the [BudgetKind] that is now used up, which is the reason the strategy has to stop
     */
    fun charge(failure: SubrequestOutcome.Failure): BudgetKind? =
        when (failure) {
            SubrequestOutcome.ClientError -> if (fastErrors.record()) BudgetKind.FAST_ERRORS else null
            SubrequestOutcome.ServerError -> if (failures.record()) BudgetKind.FAILURES else null
            SubrequestOutcome.Timeout -> if (timeouts.record()) BudgetKind.TIMEOUTS else null
        }
}

private class Quota(
    private val limit: Int,
) {
    private val used = AtomicInteger()

    val hasAnyLeft: Boolean get() = used.get() < limit

    // Takes one unit if any is left, so exactly [limit] callers are served
    fun tryTake(): Boolean {
        while (true) {
            val taken = used.get()
            if (taken >= limit) return false
            if (used.compareAndSet(taken, taken + 1)) return true
        }
    }

    /**
     * Records one unit already spent, because the class of a failure is only known once the
     * subrequest has failed.
     *
     * @return `true` when the quota is used up, this unit included.
     */
    fun record(): Boolean = used.incrementAndGet() >= limit
}