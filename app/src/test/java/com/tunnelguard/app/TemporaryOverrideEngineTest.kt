package com.tunnelguard.app

import org.junit.Assert.*
import org.junit.Test

class TemporaryOverrideEngineTest {
    private class Clock(var wall: Long = 1_000_000, var elapsed: Long = 50_000) : OverrideClock {
        override fun wallTimeMillis() = wall
        override fun elapsedRealtime() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }

    @Test fun `supported duration boundaries expire exactly and not before`() {
        listOf(5, 15, 30, 60).forEach { minutes ->
            val clock = Clock()
            val engine = TemporaryOverrideEngine(clock)
            engine.startTimed("app.$minutes", minutes * 60_000L, "test")
            clock.advance(minutes * 60_000L - 1)
            assertNotNull(engine.active("app.$minutes", false))
            clock.advance(1)
            assertNull(engine.active("app.$minutes", false))
        }
    }

    @Test fun `manual cancel is idempotent`() {
        val engine = TemporaryOverrideEngine(Clock())
        engine.startTimed("app.one", 300_000, "test")
        assertTrue(engine.cancel("app.one"))
        assertFalse(engine.cancel("app.one"))
        assertNull(engine.active("app.one", false))
    }

    @Test fun `until close clears on switch and does not return`() {
        val engine = TemporaryOverrideEngine(Clock())
        engine.startUntilClosed("app.video", "test")
        engine.onForegroundChanged("app.video")
        assertNotNull(engine.active("app.video", false))
        assertTrue(engine.onForegroundChanged("app.youtube").contains("app.video"))
        engine.onForegroundChanged("app.video")
        assertNull(engine.active("app.video", false))
    }

    @Test fun `multiple packages remain independent`() {
        val clock = Clock()
        val engine = TemporaryOverrideEngine(clock)
        engine.startTimed("app.short", 300_000, "test")
        engine.startTimed("app.long", 900_000, "test")
        assertNull(engine.active("app.unrelated", false))
        clock.advance(300_000)
        assertNull(engine.active("app.short", false))
        assertNotNull(engine.active("app.long", false))
    }

    @Test fun `Emergency Lock suppresses without pausing expiration`() {
        val clock = Clock()
        val engine = TemporaryOverrideEngine(clock)
        engine.startTimed("app.video", 300_000, "test")
        assertNull(engine.active("app.video", true))
        clock.advance(299_999)
        assertNotNull(engine.active("app.video", false))
        clock.advance(1)
        assertNull(engine.active("app.video", true))
        assertNull(engine.active("app.video", false))
    }

    @Test fun `process restore retains timed but discards foreground session records`() {
        val clock = Clock()
        val first = TemporaryOverrideEngine(clock)
        val timed = first.startTimed("app.timed", 300_000, "test")
        first.startUntilClosed("app.session", "test")
        val restored = TemporaryOverrideEngine(clock)
        restored.restore(first.allStored())
        assertNotNull(restored.active(timed.packageName, false))
        assertNull(restored.active("app.session", false))
    }

    @Test fun `reboot monotonic reset expires restored timer conservatively`() {
        val clock = Clock()
        val record = TemporaryOverrideEngine(clock).startTimed("app.video", 300_000, "test")
        clock.elapsed = 1
        val restored = TemporaryOverrideEngine(clock)
        restored.restore(listOf(record))
        assertNull(restored.active("app.video", false))
    }

    @Test fun `wall clock forward change expires but backward change cannot extend monotonic timer`() {
        val clock = Clock()
        val engine = TemporaryOverrideEngine(clock)
        engine.startTimed("app.video", 300_000, "test")
        clock.wall += 300_000
        assertNull(engine.active("app.video", false))

        val second = TemporaryOverrideEngine(clock)
        second.startTimed("app.video", 300_000, "test")
        clock.wall -= 1_000_000
        clock.elapsed += 300_000
        assertNull(second.active("app.video", false))
    }

    @Test fun `cancel versus expiry and duplicate callbacks stay cleared`() {
        val clock = Clock()
        val engine = TemporaryOverrideEngine(clock)
        engine.startTimed("app.video", 1, "test")
        clock.advance(1)
        assertEquals(listOf("app.video"), engine.reconcile())
        assertTrue(engine.reconcile().isEmpty())
        assertFalse(engine.cancel("app.video"))
    }
}
