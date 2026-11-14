package ru.itmo.ddia.resilience

/**
 * Client with a resilience strategy.
 *
 * Strategy and budgets belong to the client, the sender to the call - which is the one-per-request lifecycle [SubrequestSender] asks for
 */
interface ResilientClient {
    /** @throws BudgetExhaustedException when the strategy ran out of budget without a response */
    suspend fun execute(sender: SubrequestSender): Response
}

/** Multiclient with a resilience strategy over an ordered set of servers */
interface ResilientMultiClient {
    /** @throws BudgetExhaustedException when the strategy ran out of budget without a response */
    suspend fun execute(senders: List<SubrequestSender>): Response
}