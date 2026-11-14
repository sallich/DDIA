package ru.itmo.ddia.resilience.strategy

import ru.itmo.ddia.resilience.BudgetExhaustedException
import ru.itmo.ddia.resilience.BudgetKind
import ru.itmo.ddia.resilience.MultiResilienceStrategy
import ru.itmo.ddia.resilience.ResilienceStrategy
import ru.itmo.ddia.resilience.Response
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.SubrequestSender
import ru.itmo.ddia.resilience.budget.RequestBudget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Source of monotonic time, injected so the open period can be tested on the same virtual clock the
 * coroutines run on. Only differences between readings mean anything.
 */
fun interface MonotonicTime {
    fun elapsed(): Duration

    companion object {
        // The clock outside tests
        fun system(): MonotonicTime {
            val origin = TimeSource.Monotonic.markNow()
            return MonotonicTime { origin.elapsedNow() }
        }
    }
}

/**
 * Round-robin with a circuit breaker per server: once a server fails too often it leaves the
 * rotation for [openFor] and the rest carry the load.
 *
 * Health is keyed by position in the list, not by sender identity: a [SubrequestSender] serves one
 * request, while "the last [window] requests to this server" has to outlive it. So the caller must
 * pass the servers in the same order every time.
 *
 * @param window most recent delegations counted, one sample per delegation rather than per subrequest
 * @param failureThreshold tolerated share of failures, judged only on a full window
 * @param openFor how long a server stays out; it comes back with an empty window
 */
class CircuitBreakingStrategy(
    private val window: Int,
    private val failureThreshold: Double,
    private val openFor: Duration,
    private val subrequestStrategy: ResilienceStrategy = DefaultStrategy(),
    private val time: MonotonicTime = MonotonicTime.system(),
) : MultiResilienceStrategy {
    init {
        require(window >= 1) { "window must be at least 1, got $window" }
        require(failureThreshold in 0.0..1.0) {
            "failureThreshold must be a share between 0.0 and 1.0, got $failureThreshold"
        }
        require(openFor > Duration.ZERO) { "openFor must be positive, got $openFor" }
    }

    private val health = ConcurrentHashMap<Int, ServerHealth>()

    override suspend fun execute(
        targets: List<SubrequestSender>,
        budget: RequestBudget,
    ): Response {
        require(targets.isNotEmpty()) { "Circuit breaking needs at least one subrequest sender" }
        var next = 0
        var skippedInARow = 0
        var lastFailure: SubrequestOutcome.Failure? = null
        while (true) {
            val index = next
            next = (next + 1) % targets.size
            val server = healthOf(index)

            if (!server.isInRotation(time.elapsed())) {
                skippedInARow++
                if (skippedInARow == targets.size) {
                    throw BudgetExhaustedException(BudgetKind.AVAILABLE_SERVERS, lastFailure)
                }
                continue
            }
            skippedInARow = 0

            try {
                val response = subrequestStrategy.execute(targets[index], budget)
                server.record(failed = false, now = time.elapsed())
                return response
            } catch (gaveUp: BudgetExhaustedException) {
                server.record(failed = true, now = time.elapsed())
                lastFailure = gaveUp.lastFailure ?: lastFailure
                // Same rule as plain round-robin
                if (gaveUp.budget != BudgetKind.SUBREQUESTS || !budget.hasSubrequestsLeft) {
                    throw gaveUp
                }
            }
        }
    }

    private fun healthOf(index: Int): ServerHealth =
        health.computeIfAbsent(index) { ServerHealth(window, failureThreshold, openFor) }
}

/**
 * The sliding window and open period of one server.
 *
 * Locked because the strategy outlives a request, so two concurrent requests share it. The critical
 * sections never suspend, which is why a plain lock does instead of a coroutine mutex.
 */
private class ServerHealth(
    private val window: Int,
    private val failureThreshold: Double,
    private val openFor: Duration,
) {
    private val lock = ReentrantLock()

    // The last [window] delegations, oldest first; true is a failure
    private val recent = ArrayDeque<Boolean>(window)
    private var openedAt: Duration? = null

    // Closes the circuit again if the open period is over
    fun isInRotation(now: Duration): Boolean =
        lock.withLock {
            val since = openedAt ?: return@withLock true
            if (now - since < openFor) return@withLock false
            openedAt = null
            recent.clear()
            true
        }

    fun record(
        failed: Boolean,
        now: Duration,
    ) = lock.withLock {
        if (recent.size == window) {
            recent.removeFirst()
        }
        recent.addLast(failed)
        if (recent.size == window && recent.count { it } > failureThreshold * window) {
            openedAt = now
        }
    }
}