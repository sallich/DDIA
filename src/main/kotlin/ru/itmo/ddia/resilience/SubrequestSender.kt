package ru.itmo.ddia.resilience

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Sends the subrequests of one logical request to one imaginary server.
 *
 * Takes no timeout and no set of outcomes: how long to wait is the strategy's decision. [send] may
 * never answer, and the only way to stop waiting is to cancel the caller.
 * One instance serves one request, and calls on it are sequential.
 */
interface SubrequestSender {
    suspend fun send(): SubrequestOutcome.FromServer
}

/**
 * Sends one subrequest, waiting at most [timeout].
 *
 * The one place [SubrequestOutcome.Timeout] comes from: a sender reports what the server said, and
 * giving up on waiting is the caller's verdict
 */
internal suspend fun SubrequestSender.sendWithin(timeout: Duration): SubrequestOutcome =
    withTimeoutOrNull(timeout) { send() } ?: SubrequestOutcome.Timeout