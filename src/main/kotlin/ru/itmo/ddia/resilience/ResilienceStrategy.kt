package ru.itmo.ddia.resilience

import ru.itmo.ddia.resilience.budget.RequestBudget

/**
 * A resilience strategy over a single server.
 *
 * The budget is a parameter rather than a field, which is what makes nesting work: a multiclient
 * hands its own request budget down to the subrequest strategy it was configured with, so
 * subrequests the inner strategy issues are charged once. Strategy parameters belong in the constructor.
 */
interface ResilienceStrategy {
    /** @throws BudgetExhaustedException when nothing is left to get a response with */
    suspend fun execute(
        target: SubrequestSender,
        budget: RequestBudget,
    ): Response
}

/**
 * A resilience strategy over a set of servers.
 *
 * A [List] rather than an unordered collection, because round-robin walks it in sequence and hedging has to start with the first one
 */
interface MultiResilienceStrategy {
    /** @throws BudgetExhaustedException when nothing is left to get a response with */
    suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response
}