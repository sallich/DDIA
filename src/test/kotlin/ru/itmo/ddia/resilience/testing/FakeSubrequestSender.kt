package ru.itmo.ddia.resilience.testing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import ru.itmo.ddia.resilience.Response
import ru.itmo.ddia.resilience.SubrequestOutcome
import ru.itmo.ddia.resilience.SubrequestSender
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** What the imaginary server does about one subrequest */
sealed interface ServerBehaviour {
    // Answers with [outcome] after [takes] on the test clock
    data class Answers(
        val outcome: SubrequestOutcome.FromServer,
        val takes: Duration = Duration.ZERO,
    ) : ServerBehaviour

    // Never answers
    data object Hangs : ServerBehaviour
}

// 2xx with [payload] in the body
fun ok(
    payload: String = "ok",
    takes: Duration = Duration.ZERO,
): ServerBehaviour = ServerBehaviour.Answers(SubrequestOutcome.Success(Response(payload)), takes)

// 4xx
fun clientError(takes: Duration = Duration.ZERO): ServerBehaviour =
    ServerBehaviour.Answers(SubrequestOutcome.ClientError, takes)

//5xx
fun serverError(takes: Duration = Duration.ZERO): ServerBehaviour =
    ServerBehaviour.Answers(SubrequestOutcome.ServerError, takes)

// subrequest that is never answered, so only the caller can end it
fun hangs(): ServerBehaviour = ServerBehaviour.Hangs

/**
 * Replays [script], one entry per subrequest. A subrequest past its end fails the test unless
 * [thereafter] says otherwise, so a strategy that sends more than expected is caught.
 */
class FakeSubrequestSender(
    private val script: List<ServerBehaviour>,
    private val thereafter: ServerBehaviour? = null,
) : SubrequestSender {
    constructor(vararg script: ServerBehaviour) : this(script.toList())

    private val sent = AtomicInteger()
    private val cancelled = AtomicInteger()

    // How many subrequests this sender was asked for
    val subrequestCount: Int get() = sent.get()

    // How many subrequests ended because the caller stopped waiting
    val cancelledSubrequests: Int get() = cancelled.get()

    override suspend fun send(): SubrequestOutcome.FromServer {
        val index = sent.getAndIncrement()
        val behaviour =
            script.getOrNull(index)
                ?: thereafter
                ?: error("Unexpected subrequest #${index + 1}: the script has ${script.size} entries")
        try {
            return when (behaviour) {
                is ServerBehaviour.Answers -> {
                    delay(behaviour.takes)
                    behaviour.outcome
                }

                ServerBehaviour.Hangs -> awaitCancellation()
            }
        } catch (stoppedWaiting: CancellationException) {
            cancelled.incrementAndGet()
            throw stoppedWaiting
        }
    }

    companion object {
        // server that does the same thing to every subrequest, however many there are
        fun always(behaviour: ServerBehaviour): FakeSubrequestSender =
            FakeSubrequestSender(script = emptyList(), thereafter = behaviour)
    }
}