package io.github.ravenor907.muninn.ui

/**
 * When Muninn's screen must ask for the lock again. Kept for the whole process, not per screen: the
 * message service keeps Muninn running after its window is closed, so a window opened hours later is
 * a new MainActivity that must still know how long Muninn was away. Times are SystemClock.elapsedRealtime
 * (counts while the phone sleeps, can't be moved by changing the clock).
 */
object AppLock {
    /** When the last Muninn screen went to the background. 0 while one is on screen. */
    @Volatile var awaySince = 0L

    /**
     * True when the lock must show before anything else is drawn. [awaySince] 0 means a screen was on
     * until now (a rotation or a new window while one is up), so there's nothing to lock again.
     */
    fun due(hasPassword: Boolean, awaySince: Long, now: Long, lockAfter: Long): Boolean =
        hasPassword && awaySince > 0 && now - awaySince >= lockAfter
}
