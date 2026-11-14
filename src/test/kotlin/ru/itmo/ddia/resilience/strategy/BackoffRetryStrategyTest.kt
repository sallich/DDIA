package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.StrategyClient
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

class BackoffRetryStrategyTest {
    private val latency = LatencyBudget(total = 10.seconds, perSubrequest = 100.milliseconds)
    private val backoff = ExponentialBackoff(base = 50.milliseconds, factor = 2.0)

    private fun client(retries: RetryBudget) = StrategyClient(RetryStrategy(backoff), BudgetLimits(retries, latency))

    @Test
    fun `waits longer before each next subrequest`() =
        runTest {
            val server = FakeSubrequestSender(serverError(), serverError(), serverError(), ok("pong"))

            val response = client(RetryBudget(maxSubrequests = 10)).execute(server)

            assertEquals("pong", response.payload)
            assertEquals(4, server.subrequestCount)
            // The server answers instantly, so the clock shows the pauses alone: 50 + 100 + 200.
            assertEquals(350L, currentTime)
        }

    @Test
    fun `does not wait after a client error`() =
        runTest {
            val server = FakeSubrequestSender(clientError(), clientError(), ok("pong"))

            val response = client(RetryBudget(maxSubrequests = 10, maxFastErrors = 5)).execute(server)

            assertEquals("pong", response.payload)
            assertEquals(3, server.subrequestCount)
            assertEquals(0L, currentTime, "a 4xx is retried at once, with no exponential pause")
        }

    @Test
    fun `backs off from a timeout as well`() =
        runTest {
            val server = FakeSubrequestSender(hangs(), hangs(), ok("pong"))

            val response = client(RetryBudget(maxSubrequests = 10, maxTimeouts = 5)).execute(server)

            assertEquals("pong", response.payload)
            assertEquals(3, server.subrequestCount)
            // 100 waiting out the first hang, 50 backoff, 100 second hang, 100 backoff
            assertEquals(350L, currentTime)
        }

    @Test
    fun `pays no backoff for an attempt it is not allowed to make`() =
        runTest {
            val server = FakeSubrequestSender.always(serverError())

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 2, maxFailures = 10)).execute(server)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(2, server.subrequestCount)
            // Exactly one pause, between the two attempts. Nothing is slept away after last
            assertEquals(50L, currentTime)
        }

    @Test
    fun `lets the latency budget cut a backoff pause short`() =
        runTest {
            val tight =
                StrategyClient(
                    RetryStrategy(ExponentialBackoff(base = 200.milliseconds, factor = 2.0)),
                    BudgetLimits(
                        retries = RetryBudget(maxSubrequests = 10),
                        latency = LatencyBudget(total = 250.milliseconds, perSubrequest = 100.milliseconds),
                    ),
                )
            val server = FakeSubrequestSender.always(serverError())

            val failure = assertFailsWith<BudgetExhaustedException> { tight.execute(server) }

            // First pause of 200ms fits, the second one of 400ms does not: the deadline cancels the
            // sleep itself, which is only possible because the pause is a cancellable delay.
            assertEquals(BudgetKind.LATENCY, failure.budget)
            assertEquals(2, server.subrequestCount)
            assertEquals(250L, currentTime)
        }

    @Test
    fun `grows the exponent by attempt number, so a client error moves it along`() =
        runTest {
            val server = FakeSubrequestSender(clientError(), serverError(), ok("pong"))

            client(RetryBudget(maxSubrequests = 10, maxFastErrors = 5)).execute(server)

            // Attempt 1 was a 4xx and paused for nothing; attempt 2 was a 5xx and paused for base * factor rather than base
            assertEquals(100L, currentTime)
        }
}