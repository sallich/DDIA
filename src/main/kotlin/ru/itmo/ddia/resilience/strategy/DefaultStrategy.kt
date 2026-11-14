package ru.itmo.ddia.resilience.strategy

import ru.itmo.ddia.resilience.ResilienceStrategy

/**
 * Exactly one subrequest, bounded by the subrequest latency budget.
 *
 * The degenerate case of [RetryStrategy], implemented as exactly that so the two cannot drift
 * apart. The cap lives here rather than in the caller's budget because hedging runs one of these
 * per server out of a budget that allows one subrequest per server
 */
class DefaultStrategy : ResilienceStrategy by RetryStrategy(maxAttempts = 1)