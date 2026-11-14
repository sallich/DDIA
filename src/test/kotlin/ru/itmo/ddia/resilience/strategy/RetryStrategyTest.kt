package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.StrategyClient
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.LatencyBudget
import ru.itmo.ddia.resilience.budget.RetryBudget
import ru.itmo.ddia.resilience.testing.FakeSubrequestSender
import ru.itmo.ddia.resilience.testing.clientError
import ru.itmo.ddia.resilience.testing.hangs
import ru.itmo.ddia.resilience.testing.ok
import ru.itmo.ddia.resilience.testing.serverError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryStrategyTest {
    private val latency = LatencyBudget(total = 10.seconds, perSubrequest = 100.milliseconds)

    private fun client(retries: RetryBudget) = StrategyClient(RetryStrategy(), BudgetLimits(retries, latency))

    @Test
    fun `returns the first successful response`() =
        runTest {
            val server =
                FakeSubrequestSender(
                    serverError(takes = 10.milliseconds),
                    serverError(takes = 10.milliseconds),
                    ok("pong", takes = 10.milliseconds),
                )

            val response = client(RetryBudget(maxSubrequests = 5)).execute(server)

            assertEquals("pong", response.payload)
            assertEquals(3, server.subrequestCount)
            assertEquals(30L, currentTime, "retries are sequential, so the waits add up")
        }

    @Test
    fun `stops when the failures budget runs out`() =
        runTest {
            // The script ends with a success the strategy must never get to
            val server = FakeSubrequestSender(serverError(), serverError(), ok("unreachable"))

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 10, maxFailures = 2)).execute(server)
                }

            assertEquals(BudgetKind.FAILURES, failure.budget)
            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
            assertEquals(2, server.subrequestCount)
        }

    @Test
    fun `stops when the fast errors budget runs out`() =
        runTest {
            val server = FakeSubrequestSender(clientError(), ok("unreachable"))

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 10, maxFastErrors = 1)).execute(server)
                }

            assertEquals(BudgetKind.FAST_ERRORS, failure.budget)
            assertEquals(SubrequestOutcome.ClientError, failure.lastFailure)
            assertEquals(1, server.subrequestCount)
        }

    @Test
    fun `stops when the timeouts budget runs out`() =
        runTest {
            val server = FakeSubrequestSender.always(hangs())

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 10, maxTimeouts = 2)).execute(server)
                }

            assertEquals(BudgetKind.TIMEOUTS, failure.budget)
            assertEquals(SubrequestOutcome.Timeout, failure.lastFailure)
            assertEquals(2, server.subrequestCount)
            assertEquals(200L, currentTime, "every attempt waited out its own subrequest budget")
        }

    @Test
    fun `stops when the total subrequest budget runs out`() =
        runTest {
            val server = FakeSubrequestSender.always(serverError())
            val generousPerClass =
                RetryBudget(
                    maxSubrequests = 3,
                    maxFastErrors = 10,
                    maxFailures = 10,
                    maxTimeouts = 10,
                )

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(generousPerClass).execute(server)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
            assertEquals(3, server.subrequestCount)
        }

    @Test
    fun `counts each failure class against its own budget`() =
        runTest {
            val server =
                FakeSubrequestSender(
                    clientError(),
                    serverError(),
                    clientError(),
                    ok("unreachable"),
                )

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 10, maxFastErrors = 2, maxFailures = 2)).execute(server)
                }

            // The 5xx in the middle did not touch the fast-error budget, so the strategy got as far
            // as the second 4xx before that budget ran out.
            assertEquals(BudgetKind.FAST_ERRORS, failure.budget)
            assertEquals(3, server.subrequestCount)
        }

    @Test
    fun `stops when the total latency budget runs out mid-flight`() =
        runTest {
            val server = FakeSubrequestSender.always(serverError(takes = 80.milliseconds))
            val tight =
                StrategyClient(
                    RetryStrategy(),
                    BudgetLimits(
                        retries = RetryBudget(maxSubrequests = 10),
                        latency = LatencyBudget(total = 250.milliseconds, perSubrequest = 100.milliseconds),
                    ),
                )

            val failure = assertFailsWith<BudgetExhaustedException> { tight.execute(server) }

            assertEquals(BudgetKind.LATENCY, failure.budget)
            assertEquals(null, failure.lastFailure, "the deadline struck while a subrequest was in flight")
            assertEquals(4, server.subrequestCount, "three answered at 80ms each, the fourth was cut off")
            assertEquals(250L, currentTime)
        }

    @Test
    fun `honours its own attempt cap while the budget still has room`() =
        runTest {
            val server = FakeSubrequestSender.always(serverError())
            val capped =
                StrategyClient(
                    RetryStrategy(maxAttempts = 2),
                    BudgetLimits(retries = RetryBudget(maxSubrequests = 10), latency = latency),
                )

            val failure = assertFailsWith<BudgetExhaustedException> { capped.execute(server) }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(2, server.subrequestCount)
        }

    @Test
    fun `rejects an attempt cap below one`() {
        assertFailsWith<IllegalArgumentException> { RetryStrategy(maxAttempts = 0) }
    }
}