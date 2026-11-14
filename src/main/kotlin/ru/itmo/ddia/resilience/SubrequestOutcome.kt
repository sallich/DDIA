package ru.itmo.ddia.resilience

// Payload of a successful (2xx) response
data class Response(
    val payload: String,
)

/**
 * How one subrequest ended.
 *
 * [FromServer] is what a sender may report. [Timeout] sits outside it because a sender is never
 * told how long anyone is willing to wait and so cannot decide that it timed out. [Failure] is
 * everything charged to a retry sub-budget, which lets a strategy branch on [Success] / [Failure]
 */
sealed interface SubrequestOutcome {
    sealed interface FromServer : SubrequestOutcome

    sealed interface Failure : SubrequestOutcome

    data class Success(
        val response: Response,
    ) : FromServer

    // 4xx
    data object ClientError : FromServer, Failure

    // 5xx
    data object ServerError : FromServer, Failure

    // No verdict ever arrived and we stopped waiting
    data object Timeout : Failure
}