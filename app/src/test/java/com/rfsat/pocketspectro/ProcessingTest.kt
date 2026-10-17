package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.proc.Calibration
import com.rfsat.pocketspectro.proc.FireConfirmer
import com.rfsat.pocketspectro.proc.FireDetector
import com.rfsat.pocketspectro.proc.Processing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

class ProcessingTest {

    @Test fun boxcarUsesOceanHalfWidth() {
        val y = doubleArrayOf(0.0, 0.0, 9.0, 0.0, 0.0)
        val s = Processing.boxcar(y, 1)
        assertEquals(3.0, s[1], 1e-12); assertEquals(3.0, s[2], 1e-12); assertEquals(3.0, s[3], 1e-12)
        assertEquals(0.0, s[0], 1e-12)
        assertEquals(y.toList(), Processing.boxcar(y, 0).toList())
    }

    @Test fun absorbanceAndTransmittance() {
        val a = Processing.absorbance(doubleArrayOf(100.0, 10.0), doubleArrayOf(1000.0, 1000.0))
        assertEquals(1.0, a[0], 1e-9); assertEquals(2.0, a[1], 1e-9)
        val t = Processing.transmittance(doubleArrayOf(250.0), doubleArrayOf(1000.0))
        assertEquals(25.0, t[0], 1e-9)
    }

    @Test fun binnedWavelengthsAverageGroups() {
        val c = doubleArrayOf(600.0, 1.0)
        val w = Calibration.binnedWavelengths(c, 1024, 1)
        assertEquals(512, w.size)
        assertEquals(600.5, w[0], 1e-9) // mean of pixels 0 and 1
    }

    @Test fun nonlinearityDividesByPolynomial() {
        val out = Calibration.correctNonlinearity(doubleArrayOf(1000.0), doubleArrayOf(1.0, -1e-4))
        assertEquals(1000.0 / 0.9, out[0], 1e-9)
    }

    private val wl = Calibration.binnedWavelengths(doubleArrayOf(636.0, 0.473, -2.0e-5, 0.0), 1024, 0)

    private fun spectrum(fire: Boolean) = DoubleArray(wl.size) { i ->
        val base = 1500.0 + 800.0 * exp(-((wl[i] - 860) / 200).pow(2))
        val k = if (fire) 900.0 * (exp(-((wl[i] - 766.49) / 0.75).pow(2)) + 0.65 * exp(-((wl[i] - 769.9) / 0.75).pow(2))) else 0.0
        base + k
    }

    @Test fun fireDetectedOnKDoublet() {
        val r = FireDetector.analyse(wl, spectrum(true), FireDetector.Config())
        assertTrue(r.fire)
        assertEquals(766.49, r.window1.wavelengthNm, 0.6)
        assertEquals(769.9, r.window2.wavelengthNm, 0.6)
    }

    @Test fun noFireOnSmoothContinuum() {
        val r = FireDetector.analyse(wl, spectrum(false), FireDetector.Config())
        assertFalse(r.fire)
    }

    @Test fun globalMeanFlagsContinuumThatLocalBaselineRejects() {
        // Continuum only (broad hump near 780 nm), well above the spectrum mean at 766-770 nm.
        val y = DoubleArray(wl.size) { i -> 1000.0 + 1500.0 * exp(-((wl[i] - 780.0) / 60.0).pow(2)) } // broad hump, no lines
        val pi = FireDetector.analyse(wl, y, FireDetector.Config(baseline = FireDetector.Baseline.GLOBAL_MEAN))
        val local = FireDetector.analyse(wl, y, FireDetector.Config())
        assertTrue("Pi rule reports fire on a bare continuum", pi.fire)
        assertFalse(local.fire)
        assertEquals(0.0, local.window1.percentAboveFloor, 2.0)
    }

    @Test fun confidenceHighForDoubletLowForContinuum() {
        val rnd = java.util.Random(3)
        fun noisy(y: DoubleArray) = DoubleArray(y.size) { y[it] + rnd.nextGaussian() * 6.0 }
        val fire = FireDetector.analyse(wl, noisy(spectrum(true)), FireDetector.Config())
        val none = FireDetector.analyse(wl, noisy(spectrum(false)), FireDetector.Config())
        assertTrue("fire confidence ${fire.confidence.score}", fire.confidence.score > 60)
        assertTrue("continuum confidence ${none.confidence.score}", none.confidence.score < 20)
        assertTrue(fire.confidence.ratio in 1.0..2.5)
    }

    @Test fun otherMaximaUseLocalContinuum() {
        // Broad hump plus one narrow line at 900 nm: only the line rises above the local continuum.
        val y = DoubleArray(wl.size) { i -> 1000.0 + 1500.0 * exp(-((wl[i] - 780.0) / 60.0).pow(2)) + 300.0 * exp(-((wl[i] - 900.0) / 0.8).pow(2)) }
        val peaks = FireDetector.otherMaximaLocal(wl, y)
        assertEquals(1, peaks.size)
        assertEquals(900.0, peaks[0].wavelengthNm, 0.6)
    }

    @Test fun piIdenticalPicksLastCrossingInPixelWindow() {
        // Flat spectrum: every gradient is zero, so every pixel is a "crossing"; Pi keeps the last (largest wavelength).
        val y = DoubleArray(wl.size) { 1000.0 }
        val cfg = FireDetector.Config(windowMode = FireDetector.WindowMode.PIXEL, algorithm = FireDetector.Algorithm.PI_IDENTICAL,
            baseline = FireDetector.Baseline.GLOBAL_MEAN)
        val r = FireDetector.analyse(wl, y, cfg)
        assertEquals(283, r.window1.peakPixel) // i = 282 -> pixel 283
        assertEquals(292, r.window2.peakPixel)
        assertFalse(r.fire)
    }

    @Test fun piFloorMatchesPhp() {
        val y = doubleArrayOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(15.0, FireDetector.floorOf(y), 1e-9) // floor((10+20+30)/4)
    }

    @Test fun confirmerNeedsConsecutiveFrames() {
        val c = FireConfirmer(3)
        assertFalse(c.update(true)); assertFalse(c.update(true)); assertTrue(c.update(true))
        assertFalse(c.update(false)); assertFalse(c.update(true))
    }
}
