package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.StrategyMultiClient
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.budget.BudgetLimits
import ru.itmo.ddia.resilience.budget.LatencyBudget
import ru.itmo.ddia.resilience.budget.RequestBudget
import ru.itmo.ddia.resilience.budget.RetryBudget
import ru.itmo.ddia.resilience.testing.FakeSubrequestSender
import ru.itmo.ddia.resilience.testing.hangs
import ru.itmo.ddia.resilience.testing.ok
import ru.itmo.ddia.resilience.testing.serverError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HedgingStrategyTest {
    // subrequest timeout is deliberately generous: these tests are about the hedging delay and the total latency budget, not about one subrequest timing out on its own
    private val latency = LatencyBudget(total = 10.seconds, perSubrequest = 5.seconds)
    private val hedgingDelay = 50.milliseconds

    private fun client(
        servers: Int,
        delay: Duration = hedgingDelay,
        total: Duration = latency.total,
    ) = StrategyMultiClient(
        HedgingStrategy(delay),
        BudgetLimits(
            retries = RetryBudget(maxSubrequests = servers),
            latency = latency.copy(total = total),
        ),
    )

    @Test
    fun `returns the answer of the leader and never bothers anyone else`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(ok("pong", takes = 20.milliseconds)),
                    FakeSubrequestSender(ok("never asked")),
                )

            val response = client(servers = 2).execute(servers)

            assertEquals("pong", response.payload)
            assertEquals(listOf(1, 0), servers.map { it.subrequestCount })
            assertEquals(20L, currentTime, "the hedge never went out")
        }

    @Test
    fun `hedges onto the other servers once the hedging delay runs out`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(hangs()),
                    FakeSubrequestSender(ok("pong", takes = 10.milliseconds)),
                )

            val response = client(servers = 2).execute(servers)

            assertEquals("pong", response.payload)
            assertEquals(listOf(1, 1), servers.map { it.subrequestCount })
            assertEquals(60L, currentTime, "50ms of hedging delay, then 10ms on the second server")
            assertEquals(1, servers[0].cancelledSubrequests, "the hanging leader was cut off")
        }

    @Test
    fun `asks the remaining servers all at once, not one after another`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(hangs()),
                    FakeSubrequestSender(ok("from the second", takes = 30.milliseconds)),
                    FakeSubrequestSender(ok("from the third", takes = 10.milliseconds)),
                )

            val response = client(servers = 3).execute(servers)

            // Hedging one server after another would have answered from the second at 80ms and only reached the third at 90ms. In parallel the third wins at 60ms
            assertEquals("from the third", response.payload)
            assertEquals(60L, currentTime)
            assertEquals(listOf(1, 1, 1), servers.map { it.subrequestCount })
            assertEquals(listOf(1, 1, 0), servers.map { it.cancelledSubrequests })
        }

    @Test
    fun `hedges as soon as the leader fails, without idling out the delay`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(serverError(takes = 10.milliseconds)),
                    FakeSubrequestSender(ok("pong", takes = 10.milliseconds)),
                )

            val response = client(servers = 2, delay = 1.seconds).execute(servers)

            assertEquals("pong", response.payload)
            assertEquals(20L, currentTime, "10ms to fail and 10ms to succeed; the 1s delay is not waited out")
        }

    @Test
    fun `fails once every server has been asked and none answered`() =
        runTest {
            val servers = List(3) { FakeSubrequestSender(serverError()) }

            val failure = assertFailsWith<BudgetExhaustedException> { client(servers = 3).execute(servers) }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
            assertEquals(listOf(1, 1, 1), servers.map { it.subrequestCount })
            assertEquals(0L, currentTime, "nothing is left in flight, so there is nothing to wait for")
        }

    @Test
    fun `fails on the latency budget while every subrequest is still in flight`() =
        runTest {
            val servers = List(3) { FakeSubrequestSender(hangs()) }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(servers = 3, total = 200.milliseconds).execute(servers)
                }

            assertEquals(BudgetKind.LATENCY, failure.budget)
            assertEquals(200L, currentTime)
            assertEquals(listOf(1, 1, 1), servers.map { it.subrequestCount })
            assertEquals(listOf(1, 1, 1), servers.map { it.cancelledSubrequests })
        }

    @Test
    fun `a hedging delay of zero asks everyone immediately`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(hangs()),
                    FakeSubrequestSender(ok("from the second", takes = 80.milliseconds)),
                    FakeSubrequestSender(ok("from the third", takes = 10.milliseconds)),
                )

            val response = client(servers = 3, delay = Duration.ZERO).execute(servers)

            assertEquals("from the third", response.payload)
            assertEquals(10L, currentTime)
            assertEquals(listOf(1, 1, 1), servers.map { it.subrequestCount })
        }

    @Test
    fun `asks only as many servers as the subrequest budget pays for`() =
        runTest {
            val servers = List(3) { FakeSubrequestSender(serverError()) }
            val stingy =
                StrategyMultiClient(
                    HedgingStrategy(hedgingDelay),
                    BudgetLimits(RetryBudget(maxSubrequests = 2, maxFailures = 10), latency),
                )

            val failure = assertFailsWith<BudgetExhaustedException> { stingy.execute(servers) }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            // Two servers were paid for. One branch could not reserve a subrequest at all, so it never touched its server
            assertEquals(2, servers.sumOf { it.subrequestCount })
            assertEquals(1, servers[0].subrequestCount, "the leader is always asked first")
        }

    @Test
    fun `rejects nonsensical configuration`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { HedgingStrategy((-1).milliseconds) }

            val budget = RequestBudget(BudgetLimits(RetryBudget(maxSubrequests = 1), latency))
            assertFailsWith<IllegalArgumentException> {
                HedgingStrategy(hedgingDelay).execute(emptyList(), budget)
            }
        }
}