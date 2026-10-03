package com.greenaccesse.app.nfc

import com.greenaccesse.app.util.MonotonicClock

/**
 * Duplicate scan protection.
 *
 * A UID identical to the previous one is rejected while it keeps being seen within [windowMillis].
 * Every duplicate detection extends the window ("sliding"), so a card left resting on the phone is sent exactly once.
 * A different UID is always accepted immediately.
 */
class ScanDebouncer(
    @Volatile var windowMillis: Long = DEFAULT_WINDOW_MS,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
) {
    private var lastUid: String? = null
    private var lastSeenAt: Long = Long.MIN_VALUE

    @Synchronized
    fun shouldAccept(uid: String): Boolean {
        val now = clock.elapsedMillis()
        val duplicate = uid == lastUid && lastSeenAt != Long.MIN_VALUE && now - lastSeenAt < windowMillis
        lastUid = uid
        lastSeenAt = now
        return !duplicate
    }

    @Synchronized
    fun reset() {
        lastUid = null
        lastSeenAt = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1000L
        const val MIN_WINDOW_MS = 100L
        const val MAX_WINDOW_MS = 60_000L
        val PRESETS = listOf(500L, 1000L, 2000L)
    }
}
