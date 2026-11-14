package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.MultiResilienceStrategy
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.Response
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.SubrequestSender
import ru.itmo.ddia.resilience.budget.RequestBudget
import kotlin.time.Duration

/**
 * Asks the first server, and if it is slow, asks all the others at once. The first successful
 * answer wins and everything still in flight is cancelled.
 *
 * The hedge also goes out as soon as the leader fails, since waiting out the rest of [hedgingDelay] would leave nothing in flight;
 * and once every server has been asked and none succeeded, the strategy fails straight away rather than sitting out the latency budget.
 *
 * No budgets of its own for fast errors, failures or timeouts, because every server is asked at most once.
 * Those of the request are still charged, but under one subrequest per server they cannot bind before the subrequest budget does.
 *
 * @param hedgingDelay how long the first server gets to itself; zero asks everyone at once.
 * @param subrequestStrategy how one server is asked. [DefaultStrategy] is what its self-imposed cap
 *   of one attempt is for: the budget here allows one subrequest per server, so a strategy taking
 *   its cap from the budget would retry one server with another server's share.
 */
class HedgingStrategy(
    private val hedgingDelay: Duration,
    private val subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
) : MultiResilienceStrategy {
    init {
        require(hedgingDelay >= Duration.ZERO) { "hedgingDelay must not be negative, got $hedgingDelay" }
    }

    override suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response {
        require(targets.isNotEmpty()) { "Hedging needs at least one subrequest sender" }
        return coroutineScope {
            // Every branch posts exactly 1 reply, so the channel never makes a branch wait
            val replies = Channel<Reply>(capacity = targets.size)
            var inFlight = 0
            var lastFailure: SubrequestOutcome.Failure? = null

            try {
                launch { replies.send(subrequestStrategy.replyFor(targets.first(), budget)) }
                inFlight++

                withTimeoutOrNull(hedgingDelay) { replies.receive() }?.let { leader ->
                    inFlight--
                    when (leader) {
                        is Reply.Answered -> return@coroutineScope leader.response
                        is Reply.GaveUp -> lastFailure = leader.cause.lastFailure ?: lastFailure
                    }
                }

                targets.drop(1).forEach { target ->
                    launch { replies.send(subrequestStrategy.replyFor(target, budget)) }
                    inFlight++
                }

                while (inFlight > 0) {
                    val reply = replies.receive()
                    inFlight--
                    when (reply) {
                        is Reply.Answered -> return@coroutineScope reply.response
                        is Reply.GaveUp -> lastFailure = reply.cause.lastFailure ?: lastFailure
                    }
                }

                throw BudgetExhaustedException(BudgetKind.SUBREQUESTS, lastFailure)
            } finally {
                // Without this, `coroutineScope` would wait for the losers before handing back the answer we already have
                coroutineContext.cancelChildren()
            }
        }
    }
}

private sealed interface Reply {
    data class Answered(
        val response: Response,
    ) : Reply

    data class GaveUp(
        val cause: BudgetExhaustedException,
    ) : Reply
}

/** Cancellation is deliberately not caught: a branch that was cut off has nothing to report */
private suspend fun ResilienceStrategy.replyFor(
    target: SubrequestSender,
    budget: RequestBudget,
): Reply =
    try {
        Reply.Answered(execute(target, budget))
    } catch (gaveUp: BudgetExhaustedException) {
        Reply.GaveUp(gaveUp)
    }