package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.delay
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.Response
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.SubrequestSender
import ru.itmo.ddia.resilience.budget.RequestBudget
import ru.itmo.ddia.resilience.sendWithin

/**
 * Sends subrequests one after another until one answers or a budget runs out.
 *
 * @param backoff how long to pause between subrequests; the default pauses not at all, which makes
 *   plain retries and backoff retries the same loop under different parameters.
 * @param maxAttempts a cap on this strategy's own attempts, on top of the request budget. Needed
 *   because the budget bounds the request while this bounds one strategy's share of it: hedging
 *   runs one attempt per server out of a budget that allows one subrequest per server.
 */
class RetryStrategy(
    private val backoff: BackoffPolicy = NoBackoff,
    private val maxAttempts: Int = UNLIMITED_ATTEMPTS,
) : ResilienceStrategy {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, got $maxAttempts" }
    }

    override suspend fun execute(
        target: SubrequestSender,
        budget: RequestBudget,
    ): Response {
        if (!budget.tryReserveSubrequest()) {
            throw BudgetExhaustedException(BudgetKind.SUBREQUESTS)
        }
        var attempt = 0
        while (true) {
            attempt++
            when (val outcome = target.sendWithin(budget.latency.perSubrequest)) {
                is SubrequestOutcome.Success -> return outcome.response
                is SubrequestOutcome.Failure -> {
                    budget.charge(outcome)?.let { spent ->
                        throw BudgetExhaustedException(spent, outcome)
                    }
                    // The next slot is taken before the pause, so a request never pays for a pause
                    // ahead of an attempt it is not allowed to make - and a strategy that is done
                    // never takes a slot out of a shared budget it will not use
                    if (attempt >= maxAttempts || !budget.tryReserveSubrequest()) {
                        throw BudgetExhaustedException(BudgetKind.SUBREQUESTS, outcome)
                    }
                    delay(backoff.delayAfter(outcome, attempt))
                }
            }
        }
    }

    companion object {
        // Only the request budget bounds the number of attempts
        const val UNLIMITED_ATTEMPTS = Int.MAX_VALUE
    }
}