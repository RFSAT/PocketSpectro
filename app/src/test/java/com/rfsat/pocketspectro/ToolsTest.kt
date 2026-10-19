package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.tools.BeerLambert
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.EmissionLines
import com.rfsat.pocketspectro.tools.Kinetics
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.SpectralLibrary
import com.rfsat.pocketspectro.tools.Spectra
import com.rfsat.pocketspectro.tools.Vegetation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

class ToolsTest {
    private val wl = Spectra.grid(340.0, 1100.0, 0.5)

    @Test fun d65WhitePointAndLab() {
        val w = Colorimetry.reflectanceXYZ(wl, DoubleArray(wl.size) { 1.0 })
        assertEquals(95.05, w.x, 1.5); assertEquals(100.0, w.y, 1e-9); assertEquals(108.88, w.z, 1.5)
        val lab = Colorimetry.lab(w)
        assertEquals(100.0, lab.l, 1e-6); assertEquals(0.0, lab.a, 1e-6); assertEquals(0.0, lab.b, 1e-6)
        val (x, y) = w.chromaticity
        assertEquals(6504.0, Colorimetry.cct(x, y), 150.0)
    }

    @Test fun ciede2000MatchesSharmaTestData() {
        assertEquals(2.0425, Colorimetry.deltaE2000(Colorimetry.Lab(50.0, 2.6772, -79.7751), Colorimetry.Lab(50.0, 0.0, -82.7485)), 1e-4)
        assertEquals(27.1492, Colorimetry.deltaE2000(Colorimetry.Lab(50.0, 2.5, 0.0), Colorimetry.Lab(73.0, 25.0, -18.0)), 1e-4)
        assertEquals(1.0000, Colorimetry.deltaE2000(Colorimetry.Lab(50.0, 2.5, 0.0), Colorimetry.Lab(50.0, 3.1736, 0.5854)), 1e-4)
    }

    @Test fun lampResponseCorrectionRecoversColourTemperature() {
        // Detector response peaking at 650 nm; a 3000 K lamp calibrates it, then a 5000 K source is measured.
        val resp = DoubleArray(wl.size) { exp(-((wl[it] - 650) / 220).pow(2)) }
        fun seen(t: Double) = DoubleArray(wl.size) { Spectra.planck(wl[it], t) * resp[it] }
        val cal = LightMetrics.responseFromLamp(wl, seen(3000.0), 3000.0)
        val raw = LightMetrics.analyse(wl, seen(5000.0)).cct!!
        val fixed = LightMetrics.analyse(wl, LightMetrics.corrected(seen(5000.0), cal)).cct!!
        assertEquals(5000.0, fixed, 120.0)
        assertTrue("uncorrected $raw should be off", kotlin.math.abs(raw - 5000.0) > 300)
    }

    @Test fun ndviAndRedEdgeOfALeaf() {
        val leaf = DoubleArray(wl.size) { i ->
            val l = wl[i]
            0.05 + 0.45 / (1 + exp(-(l - 718) / 9)) + 0.06 * exp(-((l - 550) / 25).pow(2)) - 0.08 * exp(-((l - 970) / 25).pow(2))
        }
        val r = Vegetation.analyse(wl, leaf)!!
        assertTrue(r.ndvi > 0.75)
        assertEquals(Vegetation.Health.DENSE, r.health)
        assertEquals(718.0, r.rep!!, 6.0)
        assertTrue(r.wbi!! > 1.1)
        val soil = DoubleArray(wl.size) { 0.12 + 0.00025 * (wl[it] - 400) }
        assertTrue(Vegetation.analyse(wl, soil)!!.ndvi < 0.2)
        assertFalse(Vegetation.supported(Spectra.grid(700.0, 1000.0, 1.0)))
    }

    @Test fun emissionLineFoundAboveContinuum() {
        val y = DoubleArray(wl.size) { i -> 1000 + 0.5 * (wl[i] - 340) + 600 * exp(-((wl[i] - 589.4) / 0.8).pow(2)) + (i % 3) }
        val m = EmissionLines.measure(wl, y, EmissionLines.Config(EmissionLines.Preset.FLAME))
        val na = m.first { it.line.label == "Na D" }
        assertTrue(na.present); assertEquals(0.1, na.shiftNm, 0.2); assertTrue(na.pctAbove > 40)
        assertTrue(na.fwhmNm in 1.0..2.0)
        assertFalse(m.first { it.line.label == "Li" }.present)
        assertEquals(listOf(EmissionLines.Line("Na", 589.3), EmissionLines.Line("670.8", 670.8)), EmissionLines.parseCustom("Na 589.3, 670.8 nm"))
    }

    @Test fun libraryRoundTripAndMatching() {
        val dir = kotlin.io.path.createTempDirectory("lib").toFile()
        try {
            fun shape(c: Double) = DoubleArray(wl.size) { 0.3 + 0.2 * exp(-((wl[it] - c) / 40).pow(2)) }
            SpectralLibrary.save(dir, "A", SpectralLibrary.Quantity.REFLECTANCE, "STS", wl, shape(700.0))
            SpectralLibrary.save(dir, "B", SpectralLibrary.Quantity.REFLECTANCE, "STS", wl, shape(900.0))
            val lib = SpectralLibrary.load(dir)
            assertEquals(2, lib.size)
            val unknown = DoubleArray(wl.size) { 1.7 * shape(702.0)[it] }
            val m = SpectralLibrary.match(wl, unknown, SpectralLibrary.Quantity.REFLECTANCE, lib)
            assertEquals("A", m.first().entry.name)
            assertTrue("scores ${m.map { it.score }}", m.first().score > 0.95 && m[1].score < 0.5)
        } finally { dir.deleteRecursively() }
    }

    @Test fun beerLambertCalibration() {
        val cal = BeerLambert.calibrate(listOf(0.0, 2.0, 4.0, 8.0).map { BeerLambert.Standard(it, 0.1 * it + 0.01) })!!
        assertEquals(0.1, cal.fit.slope, 1e-9); assertEquals(1.0, cal.fit.r2, 1e-9)
        assertEquals(5.0, cal.concentration(0.51), 1e-9)
        val blank = DoubleArray(wl.size) { 10000.0 }
        val sample = DoubleArray(wl.size) { 10000.0 * 10.0.pow(-0.8 * exp(-((wl[it] - 664) / 20).pow(2))) }
        assertEquals(0.8, BeerLambert.absorbance(wl, sample, blank, 664.0, 1.0), 0.01)
        assertEquals(664.0, BeerLambert.peakAbsorbanceNm(wl, sample, blank)!!, 0.6)
    }

    @Test fun firstOrderKinetics() {
        val pts = (0..60).map { Kinetics.Point(it * 5.0, 0.9 * exp(-0.01 * it * 5.0)) }
        val f = Kinetics.firstOrder(pts)
        assertNotNull(f)
        assertEquals(0.01, f!!.k, 1e-6); assertEquals(69.3, f.halfLifeSec, 0.1)
    }
}
