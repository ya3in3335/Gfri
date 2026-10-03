package com.greenaccesse.app.util

/** Injectable time source to keep time-dependent logic deterministic in tests. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Monotonic clock for durations (debounce, timeouts). */
fun interface MonotonicClock {
    fun elapsedMillis(): Long

    companion object {
        val SYSTEM = MonotonicClock { System.nanoTime() / 1_000_000 }
    }
}
