package com.greenaccesse.app.nfc

import com.greenaccesse.app.testing.FakeMonotonicClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebouncerTest {
    private val clock = FakeMonotonicClock(10_000)
    private val debouncer = ScanDebouncer(1000, clock)

    @Test fun `first scan is accepted`() = assertTrue(debouncer.shouldAccept("04A1"))

    @Test fun `same card within window is ignored`() {
        assertTrue(debouncer.shouldAccept("04A1"))
        clock.now += 500
        assertFalse(debouncer.shouldAccept("04A1"))
    }

    @Test fun `same card after window is accepted again`() {
        assertTrue(debouncer.shouldAccept("04A1"))
        clock.now += 1000
        assertTrue(debouncer.shouldAccept("04A1"))
    }

    @Test fun `card resting on the phone is sent only once`() {
        assertTrue(debouncer.shouldAccept("04A1"))
        repeat(10) {
            clock.now += 800 // re-detected repeatedly, each time inside the sliding window
            assertFalse(debouncer.shouldAccept("04A1"))
        }
    }

    @Test fun `different card is accepted immediately`() {
        assertTrue(debouncer.shouldAccept("04A1"))
        clock.now += 10
        assertTrue(debouncer.shouldAccept("04B2"))
        clock.now += 10
        assertTrue(debouncer.shouldAccept("04A1"))
    }

    @Test fun `window is configurable at runtime`() {
        debouncer.windowMillis = 2000
        assertTrue(debouncer.shouldAccept("04A1"))
        clock.now += 1500
        assertFalse(debouncer.shouldAccept("04A1"))
        debouncer.windowMillis = 500
        clock.now += 600
        assertTrue(debouncer.shouldAccept("04A1"))
    }

    @Test fun `reset clears memory`() {
        assertTrue(debouncer.shouldAccept("04A1"))
        debouncer.reset()
        assertTrue(debouncer.shouldAccept("04A1"))
    }
}
