package ru.itmo.ddia.resilience.strategy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.StrategyMultiClient
import ru.itmo.ddia.resilience.SubrequestOutcome
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RoundRobinStrategyTest {
    private val latency = LatencyBudget(total = 10.seconds, perSubrequest = 100.milliseconds)

    private fun client(
        retries: RetryBudget,
        subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
    ) = StrategyMultiClient(RoundRobinStrategy(subrequestStrategy), BudgetLimits(retries, latency))

    @Test
    fun `sends one subrequest to each server in turn`() =
        runTest {
            // One scripted entry per server: a second subrequest to the same one fails the test
            val servers = List(3) { FakeSubrequestSender(serverError()) }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 3, maxFailures = 10)).execute(servers)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            assertEquals(listOf(1, 1, 1), servers.map { it.subrequestCount })
        }

    @Test
    fun `wraps around to the first server after the last`() =
        runTest {
            val servers = List(2) { FakeSubrequestSender.always(serverError()) }

            assertFailsWith<BudgetExhaustedException> {
                client(RetryBudget(maxSubrequests = 5, maxFailures = 10)).execute(servers)
            }

            // 5 subrequests over 2 servers, starting at the first one
            assertEquals(listOf(3, 2), servers.map { it.subrequestCount })
        }

    @Test
    fun `returns the first success and stops rotating`() =
        runTest {
            val servers =
                listOf(
                    FakeSubrequestSender(serverError()),
                    FakeSubrequestSender(ok("pong")),
                    FakeSubrequestSender(ok("never asked")),
                )

            val response = client(RetryBudget(maxSubrequests = 10)).execute(servers)

            assertEquals("pong", response.payload)
            assertEquals(listOf(1, 1, 0), servers.map { it.subrequestCount })
        }

    @Test
    fun `stops when a failure class runs out, even with servers left to try`() =
        runTest {
            val servers = List(3) { FakeSubrequestSender.always(serverError()) }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(RetryBudget(maxSubrequests = 10, maxFailures = 2)).execute(servers)
                }

            // The budget belongs to the request, not to a server: a spent failure budget would be
            // just as spent on the third server, so rotating there would buy nothing
            assertEquals(BudgetKind.FAILURES, failure.budget)
            assertEquals(SubrequestOutcome.ServerError, failure.lastFailure)
            assertEquals(listOf(1, 1, 0), servers.map { it.subrequestCount })
        }

    @Test
    fun `gives each server a burst of retries out of the one shared budget`() =
        runTest {
            val servers = List(2) { FakeSubrequestSender.always(serverError()) }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(
                        retries = RetryBudget(maxSubrequests = 4, maxFailures = 10),
                        subrequestStrategy = RetryStrategy(maxAttempts = 2),
                    ).execute(servers)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            // 2 attempts per server, and all 4 are charged to the same ledger
            assertEquals(listOf(2, 2), servers.map { it.subrequestCount })
        }

    @Test
    fun `composes with the exponential backoff strategy of part 2`() =
        runTest {
            val servers = List(2) { FakeSubrequestSender.always(serverError()) }
            val backoffRetries =
                RetryStrategy(
                    backoff = ExponentialBackoff(base = 50.milliseconds, factor = 2.0),
                    maxAttempts = 2,
                )

            assertFailsWith<BudgetExhaustedException> {
                client(RetryBudget(maxSubrequests = 4, maxFailures = 10), backoffRetries).execute(servers)
            }

            assertEquals(listOf(2, 2), servers.map { it.subrequestCount })
            // One 50ms pause inside each server's burst. The rotation itself never pauses, and the
            // exponent restarts per server because the attempt number is the inner strategy's
            assertEquals(100L, currentTime)
        }

    @Test
    fun `never rotates when the subrequest strategy has no cap of its own`() =
        runTest {
            val servers = List(3) { FakeSubrequestSender.always(serverError()) }

            val failure =
                assertFailsWith<BudgetExhaustedException> {
                    client(
                        retries = RetryBudget(maxSubrequests = 4, maxFailures = 10),
                        subrequestStrategy = RetryStrategy(),
                    ).execute(servers)
                }

            assertEquals(BudgetKind.SUBREQUESTS, failure.budget)
            // uncapped retry strategy spends the whole request budget on the first server and never hands the rotation back
            assertEquals(listOf(4, 0, 0), servers.map { it.subrequestCount })
        }

    @Test
    fun `rejects a request with no servers`() =
        runTest {
            val budget = RequestBudget(BudgetLimits(RetryBudget(maxSubrequests = 1), latency))

            assertFailsWith<IllegalArgumentException> {
                RoundRobinStrategy().execute(emptyList(), budget)
            }
        }
}