package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.StrategyClient
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.LatencyBudget
import ru.itmo.ddia.resilience.budget.RequestBudget
import ru.itmo.ddia.resilience.budget.RetryBudget
import ru.itmo.ddia.resilience.testing.FakeSubrequestSender
import ru.itmo.ddia.resilience.testing.clientError
import ru.itmo.ddia.resilience.testing.hangs
import ru.itmo.ddia.resilience.testing.ok
import ru.itmo.ddia.resilience.testing.serverError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultStrategyTest {
    private val budgets =
        BudgetLimits(
            retries = RetryBudget.SINGLE_SUBREQUEST,
            latency = LatencyBudget(total = 1.seconds, perSubrequest = 300.milliseconds),
        )

    private val client = StrategyClient(DefaultStrategy(), budgets)

    @Test
    fun `returns the payload of a successful subrequest`() =
        runTest {
            val server = FakeSubrequestSender(ok("pong", takes = 50.milliseconds))

            val response = client.execute(server)

            assertEquals("pong", response.payload)
            assertEquals(1, server.subrequestCount)
            assertEquals(50L, currentTime)
        }

    @Test
    fun `gives up on a client error without retrying`() =
        runTest {
            // script holds a single entry, so a second subrequest would fail this test rather than quietly succeed
            val server = FakeSubrequestSender(clientError())

            val failure = assertFailsWith<BudgetExhaustedException> { client.execute(server) }

            assertEquals(SubrequestOutcome.ClientError, failure.lastFailure)
            // SINGLE_SUBREQUEST leaves every class budget at 1, so the single 4xx both fits into the fast-error budget and uses it up
            assertEquals(BudgetKind.FAST_ERRORS, failure.budget)
            assertEquals(1, server.subrequestCount)
        }

    @Test
    fun `gives up on a server error without retrying`() =
        runTest {
            val server = FakeSubrequestSender(serverError(takes = 120.milliseconds))

            val failure = assertFailsWith<BudgetExhaustedException> { client.execute(server) }

            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
            assertEquals(1, server.subrequestCount)
            assertEquals(120L, currentTime)
        }

    @Test
    fun `stops waiting for a hanging server at the subrequest latency budget`() =
        runTest {
            val server = FakeSubrequestSender(hangs())

            val failure = assertFailsWith<BudgetExhaustedException> { client.execute(server) }

            assertEquals(SubrequestOutcome.Timeout, failure.lastFailure)
            assertEquals(300L, currentTime, "waiting must end at the subrequest budget, not the total one")
        }

    @Test
    fun `fails on the total latency budget when it is tighter than the subrequest one`() =
        runTest {
            val tight =
                StrategyClient(
                    DefaultStrategy(),
                    BudgetLimits(
                        retries = RetryBudget.SINGLE_SUBREQUEST,
                        latency = LatencyBudget(total = 100.milliseconds, perSubrequest = 5.seconds),
                    ),
                )
            val server = FakeSubrequestSender(hangs())

            val failure = assertFailsWith<BudgetExhaustedException> { tight.execute(server) }

            assertEquals(BudgetKind.LATENCY, failure.budget)
            assertEquals(100L, currentTime)
        }

    @Test
    fun `makes exactly one subrequest even when the budget allows more`() =
        runTest {
            // one attempt per server, out of a shared budget that allows one subrequest per server
            val generous =
                BudgetLimits(
                    retries = RetryBudget(maxSubrequests = 5),
                    latency = LatencyBudget(total = 1.seconds, perSubrequest = 300.milliseconds),
                )
            val server = FakeSubrequestSender(serverError())

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    StrategyClient(DefaultStrategy(), generous).execute(server)
                }

            assertEquals(1, server.subrequestCount)
            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
        }

    @Test
    fun `sends nothing when the shared subrequest budget is already spent`() =
        runTest {
            // budget belongs to the request, so a strategy nested in a multiclient inherits whatever the outer strategy already spent
            val budget = RequestBudget(budgets)
            assertTrue(budget.tryReserveSubrequest(), "the only subrequest of the budget")
            val server = FakeSubrequestSender()

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    DefaultStrategy().execute(server, budget)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(null, failure.lastFailure)
            assertEquals(0, server.subrequestCount)
        }
}