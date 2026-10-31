package io.github.ravenor907.muninn.net

/**
 * The wait after wrong tries at the lock. It runs on the phone's time since start-up, which can't be
 * changed in Settings, so moving the clock forward doesn't skip it. A restart sets that time back to
 * zero, so the boot count is kept too: after a restart the wait starts again in full.
 */
object LockWait {
    /** Every 5 wrong tries the wait doubles: 30 s, 1 min, 2 min ... up to an hour. 0 when no wait is due. */
    fun after(fails: Int): Long = if (fails <= 0 || fails % 5 != 0) 0L else minOf(3_600_000L, 30_000L shl minOf(fails / 5 - 1, 7))

    /** A wait: ends at [until] (time since start-up), is [wait] long, began in start-up number [boot]. */
    data class State(val until: Long, val wait: Long, val boot: Int)

    /** How long is left, in ms. Another start-up since: the whole wait again. */
    fun left(s: State, now: Long, boot: Int): Long = when {
        s.wait <= 0L -> 0L
        boot != s.boot -> s.wait
        else -> (s.until - now).coerceIn(0L, s.wait)
    }
}
