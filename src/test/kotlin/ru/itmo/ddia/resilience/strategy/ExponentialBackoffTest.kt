package ru.itmo.ddia.resilience.strategy

import ru.itmo.ddia.resilience.SubrequestOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffTest {
    private val backoff = ExponentialBackoff(base = 50.milliseconds, factor = 2.0)

    @Test
    fun `doubles the pause with every attempt`() {
        assertEquals(50.milliseconds, backoff.delayAfter(SubrequestOutcome.ServerError, attempt = 1))
        assertEquals(100.milliseconds, backoff.delayAfter(SubrequestOutcome.ServerError, attempt = 2))
        assertEquals(200.milliseconds, backoff.delayAfter(SubrequestOutcome.ServerError, attempt = 3))
        assertEquals(400.milliseconds, backoff.delayAfter(SubrequestOutcome.ServerError, attempt = 4))
    }

    @Test
    fun `treats a timeout like a server error`() {
        assertEquals(
            backoff.delayAfter(SubrequestOutcome.ServerError, attempt = 3),
            backoff.delayAfter(SubrequestOutcome.Timeout, attempt = 3),
        )
    }

    @Test
    fun `never pauses after a client error`() {
        assertEquals(Duration.ZERO, backoff.delayAfter(SubrequestOutcome.ClientError, attempt = 1))
        assertEquals(Duration.ZERO, backoff.delayAfter(SubrequestOutcome.ClientError, attempt = 7))
    }

    @Test
    fun `stops growing at the cap`() {
        val capped = ExponentialBackoff(base = 50.milliseconds, factor = 2.0, maxDelay = 120.milliseconds)

        assertEquals(50.milliseconds, capped.delayAfter(SubrequestOutcome.ServerError, attempt = 1))
        assertEquals(100.milliseconds, capped.delayAfter(SubrequestOutcome.ServerError, attempt = 2))
        assertEquals(120.milliseconds, capped.delayAfter(SubrequestOutcome.ServerError, attempt = 3))
        assertEquals(120.milliseconds, capped.delayAfter(SubrequestOutcome.ServerError, attempt = 30))
    }

    @Test
    fun `survives an attempt number that overflows the exponent`() {
        val capped = ExponentialBackoff(base = 50.milliseconds, factor = 2.0, maxDelay = 1.seconds)

        // 2.0.pow(9999) is Double.POSITIVE_INFINITY, which saturates the duration instead of throwing, and the cap then brings it back down.
        assertEquals(1.seconds, capped.delayAfter(SubrequestOutcome.ServerError, attempt = 10_000))
    }

    @Test
    fun `a factor of one gives a constant pause`() {
        val constant = ExponentialBackoff(base = 30.milliseconds, factor = 1.0)

        assertEquals(30.milliseconds, constant.delayAfter(SubrequestOutcome.ServerError, attempt = 1))
        assertEquals(30.milliseconds, constant.delayAfter(SubrequestOutcome.ServerError, attempt = 5))
    }

    @Test
    fun `rejects nonsensical parameters`() {
        assertFailsWith<IllegalArgumentException> { ExponentialBackoff(base = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ExponentialBackoff(base = 1.seconds, factor = 0.5) }
        assertFailsWith<IllegalArgumentException> {
            ExponentialBackoff(base = 1.seconds, maxDelay = 500.milliseconds)
        }
    }
}