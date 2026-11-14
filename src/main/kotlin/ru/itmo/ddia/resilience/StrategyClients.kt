package ru.itmo.ddia.resilience

import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.RequestBudget

// [ResilientClient] driven by a single-server [ResilienceStrategy]. */
class StrategyClient(
    private val strategy: ResilienceStrategy,
    private val budgets: BudgetLimits,
) : ResilientClient {
    override suspend fun execute(sender: SubrequestSender): Response =
        withRequestBudgets(budgets) { budget -> strategy.execute(sender, budget) }
}

// [ResilientMultiClient] driven by [MultiResilienceStrategy]
class StrategyMultiClient(
    private val strategy: MultiResilienceStrategy,
    private val budgets: BudgetLimits,
) : ResilientMultiClient {
    override suspend fun execute(senders: List<SubrequestSender>): Response {
        require(senders.isNotEmpty()) { "A multiclient needs at least one subrequest sender" }
        return withRequestBudgets(budgets) { budget -> strategy.execute(senders, budget) }
    }
}

/**
 * Opens the budgets of one request: a fresh ledger, and the total latency budget as a deadline.
 *
 * The deadline is a cancellation scope rather than a value, so nested strategies inherit it without
 * carrying a parameter.
 */
private suspend fun withRequestBudgets(
    budgets: BudgetLimits,
    block: suspend (RequestBudget) -> Response,
): Response =
    withTimeoutOrNull(budgets.latency.total) { block(RequestBudget(budgets)) }
        ?: throw BudgetExhaustedException(BudgetKind.LATENCY)