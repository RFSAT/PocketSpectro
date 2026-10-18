package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.proc.FireDetector
import com.rfsat.pocketspectro.sim.SimFireConfig
import com.rfsat.pocketspectro.sim.SimFireInjector
import com.rfsat.pocketspectro.sim.SimFireMode
import com.rfsat.pocketspectro.sim.SimulatedSpectrometer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimFireTest {

    @Test fun manualBurstRampsHoldsAndEnds() {
        val f = SimFireInjector()
        f.config = SimFireConfig(mode = SimFireMode.MANUAL, durationSec = 10.0, strength = 2.0)
        assertEquals(0.0, f.level(0), 0.0)
        f.inject(1_000)
        assertTrue(f.level(1_300) in 0.01..1.99)       // ramping up
        assertEquals(2.0, f.level(6_000), 1e-9)          // plateau
        assertTrue(f.level(10_800) in 0.01..1.99)      // ramping down
        assertEquals(0.0, f.level(11_000), 0.0)          // over
        assertEquals(1, f.status(11_000).incidents)
    }

    @Test fun holdAndStop() {
        val f = SimFireInjector()
        f.config = SimFireConfig(mode = SimFireMode.MANUAL, strength = 0.5)
        f.hold = true
        assertEquals(0.5, f.level(0), 0.0)
        assertTrue(f.status(0).held)
        f.stop()
        assertEquals(0.0, f.level(1), 0.0)
    }

    @Test fun switchingOffClearsEverything() {
        val f = SimFireInjector()
        f.config = SimFireConfig(mode = SimFireMode.MANUAL)
        f.hold = true
        f.inject(0)
        f.config = SimFireConfig(mode = SimFireMode.OFF)
        assertEquals(0.0, f.level(2_000), 0.0)
        assertFalse(f.status(2_000).active)
    }

    @Test fun randomModeStartsIncidentsAtAboutTheMeanRate() {
        val f = SimFireInjector(seed = 3)
        f.config = SimFireConfig(mode = SimFireMode.RANDOM, meanIntervalSec = 30.0, durationSec = 5.0)
        var t = 0L
        var starts = 0
        var wasOn = false
        while (t < 3_600_000L) { // one simulated hour, 200 ms frames
            val on = f.level(t) > 0.0
            if (on && !wasOn) starts++
            wasOn = on
            t += 200
        }
        assertEquals(starts, f.status(t).incidents)
        // Mean cycle = 30 s gap + ~5 s burst: about 100 per hour.
        assertTrue("got $starts incidents", starts in 70..140)
        assertNotNull(f.status(t).nextAtMs)
    }

    @Test fun injectedBurstIsDetected() {
        val sim = SimulatedSpectrometer()
        sim.setIntegrationTimeMicros(200_000)
        val wl = sim.info.wavelengths()
        val cfg = FireDetector.Config()
        fun spectrum() = sim.acquire().map { it.toDouble() }.toDoubleArray()
        assertFalse(FireDetector.analyse(wl, spectrum(), cfg).fire)
        sim.fire.config = SimFireConfig(mode = SimFireMode.MANUAL, durationSec = 30.0, strength = 1.0)
        sim.fire.inject(System.currentTimeMillis() - 5_000) // already past the ramp
        val r = FireDetector.analyse(wl, spectrum(), cfg)
        assertTrue(r.fire)
        assertTrue(r.window1.percentAboveFloor > 15.0)
    }
}
