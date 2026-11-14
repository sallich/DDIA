package ru.itmo.ddia.resilience

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.LatencyBudget
import ru.itmo.ddia.resilience.budget.RequestBudget
import ru.itmo.ddia.resilience.budget.RetryBudget
import ru.itmo.ddia.resilience.testing.FakeSubrequestSender
import ru.itmo.ddia.resilience.testing.ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class StrategyMultiClientTest {
    private val budgets =
        BudgetLimits(
            retries = RetryBudget(maxSubrequests = 3),
            latency = LatencyBudget(total = 1.seconds),
        )

    @Test
    fun `hands the strategy every sender and a single budget`() =
        runTest {
            val strategy = RecordingStrategy(Response("ok"))
            val senders = listOf(FakeSubrequestSender(ok()), FakeSubrequestSender(ok()))

            val response = StrategyMultiClient(strategy, budgets).execute(senders)

            assertEquals("ok", response.payload)
            assertEquals(senders, strategy.seenTargets)
            assertEquals(budgets, strategy.seenBudget?.limits)
        }

    @Test
    fun `opens a fresh budget for every request`() =
        runTest {
            val strategy = RecordingStrategy(Response("ok"))
            val client = StrategyMultiClient(strategy, budgets)

            client.execute(listOf(FakeSubrequestSender(ok())))
            val first = strategy.seenBudget
            client.execute(listOf(FakeSubrequestSender(ok())))

            assertEquals(false, first === strategy.seenBudget, "budgets must not leak between requests")
        }

    @Test
    fun `rejects a request with no senders`() =
        runTest {
            val client = StrategyMultiClient(RecordingStrategy(Response("ok")), budgets)

            assertFailsWith<IllegalArgumentException> { client.execute(emptyList()) }
        }

    @Test
    fun `fails on the latency budget when the strategy outlives it`() =
        runTest {
            val client = StrategyMultiClient(HangingStrategy, budgets)

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client.execute(listOf(FakeSubrequestSender(ok())))
                }

            assertEquals(BudgetKind.LATENCY, failure.budget)
            assertEquals(null, failure.lastFailure)
            assertEquals(1_000L, currentTime)
        }

    @Test
    fun `passes the very same budget instance the ledger was opened with`() =
        runTest {
            val strategy = RecordingStrategy(Response("ok"))
            val client = StrategyMultiClient(strategy, budgets)

            client.execute(listOf(FakeSubrequestSender(ok())))

            val budget = requireNotNull(strategy.seenBudget)
            assertSame(budgets.latency, budget.latency)
        }
}

private class RecordingStrategy(
    private val answer: Response,
) : MultiResilienceStrategy {
    var seenTargets: List<SubrequestSender>? = null
        private set
    var seenBudget: RequestBudget? = null
        private set

    override suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response {
        seenTargets = targets
        seenBudget = budget
        return answer
    }
}

private object HangingStrategy : MultiResilienceStrategy {
    override suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response = awaitCancellation()
}