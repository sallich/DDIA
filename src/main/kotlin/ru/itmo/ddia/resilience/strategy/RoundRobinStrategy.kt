package ru.itmo.ddia.resilience.strategy

import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.MultiResilienceStrategy
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.Response
import ru.itmo.ddia.resilience.SubrequestSender
import ru.itmo.ddia.resilience.budget.RequestBudget

/**
 * Walks the servers in order, wrapping around at the end, handing the per-server work to
 * [subrequestStrategy] along with the request budget.
 *
 * @param subrequestStrategy what to do with one server before moving on. The default sends exactly
 *   one subrequest per server. Anything bounded works, but a strategy with no cap of its own never
 *   hands the rotation back: it spends the whole request budget on the first server
 */
class RoundRobinStrategy(
    private val subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
) : MultiResilienceStrategy {
    override suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response {
        require(targets.isNotEmpty()) { "Round-robin needs at least one subrequest sender" }
        var next = 0
        while (true) {
            val target = targets[next]
            next = (next + 1) % targets.size
            try {
                return subrequestStrategy.execute(target, budget)
            } catch (gaveUp: BudgetExhaustedException) {
                // Moving on is worth something only if the request can still send at all, and only
                // if what ran out was the inner strategy's own allowance: a spent failure class
                // would be just as spent on the next server. A cancellation from the latency budget
                // is not this exception and travels straight through
                if (gaveUp.budget != BudgetKind.SUBREQUESTS || !budget.hasSubrequestsLeft) {
                    throw gaveUp
                }
            }
        }
    }
}