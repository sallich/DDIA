package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.StrategyMultiClient
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.LatencyBudget
import ru.itmo.ddia.resilience.budget.RequestBudget
import ru.itmo.ddia.resilience.budget.RetryBudget
import ru.itmo.ddia.resilience.testing.FakeSubrequestSender
import ru.itmo.ddia.resilience.testing.ok
import ru.itmo.ddia.resilience.testing.serverError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CircuitBreakingStrategyTest {
    private val latency = LatencyBudget(total = 10.seconds, perSubrequest = 100.milliseconds)
    private val budgets = BudgetLimits(RetryBudget(maxSubrequests = 4, maxFailures = 10), latency)

    private fun TestScope.strategy(
        window: Int,
        threshold: Double,
        openFor: Duration = 1.minutes,
        subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
    ) = CircuitBreakingStrategy(
        window = window,
        failureThreshold = threshold,
        openFor = openFor,
        subrequestStrategy = subrequestStrategy,
        time = MonotonicTime { currentTime.milliseconds },
    )

    private fun TestScope.client(
        window: Int,
        threshold: Double,
        openFor: Duration = 1.minutes,
        subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
    ) = StrategyMultiClient(strategy(window, threshold, openFor, subrequestStrategy), budgets)

    private fun failing() = FakeSubrequestSender.always(serverError())

    private fun answering(payload: String = "pong") = FakeSubrequestSender.always(ok(payload))

    @Test
    fun `rotates like plain round-robin while every circuit is closed`() =
        runTest {
            val servers = List(3) { failing() }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(window = 10, threshold = 0.5).execute(servers)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            // 4 subrequests over 3 servers, in order, wrapping around once
            assertEquals(listOf(2, 1, 1), servers.map { it.subrequestCount })
        }

    @Test
    fun `takes a server out of the rotation once it fails too often`() =
        runTest {
            val client = client(window = 2, threshold = 0.0)

            // 2 requests fill the window of the first server with failures.
            repeat(2) { round ->
                val bad = failing()
                assertEquals("pong", client.execute(listOf(bad, answering())).payload)
                assertEquals(1, bad.subrequestCount, "round $round should still have asked it")
            }

            val bad = failing()
            val good = answering()

            assertEquals("pong", client.execute(listOf(bad, good)).payload)

            assertEquals(0, bad.subrequestCount, "its circuit is open, so it is skipped entirely")
            assertEquals(1, good.subrequestCount)
        }

    @Test
    fun `puts a server back into rotation once its open period is over`() =
        runTest {
            val openFor = 30.seconds
            val client = client(window = 2, threshold = 0.0, openFor = openFor)

            repeat(2) { client.execute(listOf(failing(), answering())) }

            val whileOpen = failing()
            client.execute(listOf(whileOpen, answering()))
            assertEquals(0, whileOpen.subrequestCount, "still inside the open period")

            delay(openFor + 1.milliseconds)

            val afterOpen = failing()
            client.execute(listOf(afterOpen, answering()))
            assertEquals(1, afterOpen.subrequestCount, "the open period expired, so it is tried again")
        }

    @Test
    fun `fails fast when every server is out of the rotation`() =
        runTest {
            // A window of one sample: a single failure is enough to open a circuit
            val client = client(window = 1, threshold = 0.0)

            val first = List(2) { failing() }
            val tripped = assertFailsWith<BudgetExhaustedException> { client.execute(first) }

            // Both circuits opened part way through this very request, which is why availability
            // has to be re-checked at every step of the rotation rather than filtered once
            assertEquals(BudgetKind.AVAILABLE_SERVERS, tripped.budget)
            assertEquals(listOf(1, 1), first.map { it.subrequestCount })

            val second = List(2) { failing() }
            val shedded = assertFailsWith<BudgetExhaustedException> { client.execute(second) }

            assertEquals(BudgetKind.AVAILABLE_SERVERS, shedded.budget)
            assertEquals(null, shedded.lastFailure, "nothing was sent, so there is no failure to report")
            assertEquals(listOf(0, 0), second.map { it.subrequestCount })
        }

    @Test
    fun `counts one sample per delegation, not per subrequest`() =
        runTest {
            val client = client(window = 2, threshold = 0.0, subrequestStrategy = RetryStrategy(maxAttempts = 2))

            val firstRound = failing()
            client.execute(listOf(firstRound, answering()))
            assertEquals(2, firstRound.subrequestCount, "two attempts, one verdict")

            // Had those 2 subrequests counted as 2 samples, the window would already be full
            // and the circuit open. 1 sample per delegation means the server gets another turn
            val secondRound = failing()
            client.execute(listOf(secondRound, answering()))
            assertEquals(2, secondRound.subrequestCount)
        }

    @Test
    fun `a success in the window keeps the circuit closed`() =
        runTest {
            // Half of a window of 2 is tolerated; more than half is not
            val client = client(window = 2, threshold = 0.5)

            client.execute(listOf(failing(), answering()))
            client.execute(listOf(answering(), answering("unused")))

            // The first server's window now reads failure, success: 1 of 2, not above half
            val stillTried = failing()
            client.execute(listOf(stillTried, answering()))

            assertEquals(1, stillTried.subrequestCount, "a failure rate of one half does not open the circuit")
        }

    @Test
    fun `rejects nonsensical configuration`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { strategy(window = 0, threshold = 0.5) }
            assertFailsWith<IllegalArgumentException> { strategy(window = 2, threshold = 1.5) }
            assertFailsWith<IllegalArgumentException> {
                strategy(window = 2, threshold = 0.5, openFor = Duration.ZERO)
            }

            val budget = RequestBudget(budgets)
            assertFailsWith<IllegalArgumentException> {
                strategy(window = 2, threshold = 0.5).execute(emptyList(), budget)
            }
        }
}