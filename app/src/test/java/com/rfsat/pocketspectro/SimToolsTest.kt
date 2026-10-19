package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.proc.FireDetector
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimModel
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.sim.SimulatedSpectrometer
import com.rfsat.pocketspectro.tools.BeerLambert
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.EmissionLines
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.SpectralLibrary
import com.rfsat.pocketspectro.tools.Vegetation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every tool works on the simulator's scenes, so it can be tried without hardware. */
class SimToolsTest {

    private class Bench(model: SimModel) {
        val sim = SimulatedSpectrometer(model = model).apply { setIntegrationTimeMicros(50_000) }
        val wl = sim.info.wavelengths()
        private fun mean(n: Int = 4): DoubleArray {
            val acc = DoubleArray(wl.size)
            repeat(n) { val s = sim.acquire(); for (i in acc.indices) acc[i] += s[i] / n.toDouble() }
            return acc
        }
        val dark: DoubleArray = run { sim.setShutter(false); val d = mean(); sim.setShutter(true); d }
        fun counts(scene: SimScene): DoubleArray { sim.scene = scene; val m = mean(); return DoubleArray(m.size) { m[it] - dark[it] } }
        fun ratio(scene: SimScene, blank: SimScene): DoubleArray {
            val r = counts(blank); val s = counts(scene)
            return DoubleArray(s.size) { s[it] / r[it] }
        }
    }

    @Test fun vegetationOnNirSimulator() {
        val b = Bench(SimModel.NIR)
        val white = SimScene.Reflect(SimTarget.WHITE, SimLight.DAYLIGHT)
        val leaf = Vegetation.analyse(b.wl, b.ratio(SimScene.Reflect(SimTarget.LEAF_HEALTHY, SimLight.DAYLIGHT), white))!!
        val stressed = Vegetation.analyse(b.wl, b.ratio(SimScene.Reflect(SimTarget.LEAF_STRESSED, SimLight.DAYLIGHT), white))!!
        val soil = Vegetation.analyse(b.wl, b.ratio(SimScene.Reflect(SimTarget.SOIL, SimLight.DAYLIGHT), white))!!
        assertTrue("leaf ${leaf.ndvi}", leaf.ndvi > 0.7)
        assertTrue("stressed ${stressed.ndvi}", stressed.ndvi in 0.3..leaf.ndvi - 0.05)
        assertTrue("soil ${soil.ndvi}", soil.ndvi < 0.25)
        assertTrue(leaf.rep!! > stressed.rep!!)
    }

    @Test fun concentrationOnSimulatedCuvette() {
        val b = Bench(SimModel.NIR)
        val blank = b.counts(SimScene.Cuvette(0.0))
        val a = BeerLambert.absorbance(b.wl, b.counts(SimScene.Cuvette(5.0)), blank, 664.0, 2.0)
        assertEquals(0.40, a, 0.03)
        assertEquals(664.0, BeerLambert.peakAbsorbanceNm(b.wl, b.counts(SimScene.Cuvette(8.0)), blank)!!, 3.0)
    }

    @Test fun materialsAreTellApart() {
        val b = Bench(SimModel.NIR)
        val dir = kotlin.io.path.createTempDirectory("lib").toFile()
        try {
            val white = SimScene.Reflect(SimTarget.WHITE)
            val mats = listOf(SimTarget.PAPER, SimTarget.COTTON, SimTarget.POLYESTER, SimTarget.POLYETHYLENE, SimTarget.NYLON)
            for (m in mats) SimSpectra.save(dir, m.name, b.wl, DoubleArray(b.wl.size) { com.rfsat.pocketspectro.sim.SimOptics.reflectance(b.wl[it], m, 0L) })
            val lib = SpectralLibrary.load(dir)
            // Single noisy scans against a clean library must still clear the default 95 % threshold.
            b.sim.setIntegrationTimeMicros(20_000)
            for (m in mats) {
                val best = SpectralLibrary.match(b.wl, b.ratio(SimScene.Reflect(m), white), SpectralLibrary.Quantity.REFLECTANCE, lib)
                assertTrue("${m.name} score ${best.first().score}", best.first().score >= 0.95)
                assertEquals(m.name, best.first().entry.name)
                assertTrue("${m.name} margin", best.first().score - best[1].score > 0.03)
            }
        } finally { dir.deleteRecursively() }
    }

    private object SimSpectra {
        fun save(dir: java.io.File, name: String, wl: DoubleArray, y: DoubleArray) =
            SpectralLibrary.save(dir, name, SpectralLibrary.Quantity.REFLECTANCE, "sim", wl, y)
    }

    @Test fun colourOfTilesOnVisSimulator() {
        val b = Bench(SimModel.VIS)
        val white = SimScene.Reflect(SimTarget.WHITE)
        fun lab(t: SimTarget) = Colorimetry.lab(Colorimetry.reflectanceXYZ(b.wl, b.ratio(SimScene.Reflect(t), white)))
        val red = lab(SimTarget.TILE_RED); val grey = lab(SimTarget.TILE_GREY); val blue = lab(SimTarget.TILE_BLUE)
        assertTrue("red a* ${red.a}", red.a > 30)
        assertTrue("grey chroma ${grey.chroma}", grey.chroma < 4)
        assertTrue("blue b* ${blue.b}", blue.b < -20)
        assertTrue(Colorimetry.deltaE2000(lab(SimTarget.TOMATO_UNRIPE), lab(SimTarget.TOMATO_RIPE)) > 20)
    }

    @Test fun ledColourTemperatureAfterLampCalibration() {
        val b = Bench(SimModel.VIS)
        val resp = LightMetrics.responseFromLamp(b.wl, b.counts(SimScene.Emit(SimEmitter.LAMP_2856K)), 2856.0)
        val cool = LightMetrics.analyse(b.wl, LightMetrics.corrected(b.counts(SimScene.Emit(SimEmitter.LED_WHITE_COOL)), resp))
        val warm = LightMetrics.analyse(b.wl, LightMetrics.corrected(b.counts(SimScene.Emit(SimEmitter.LED_WHITE_WARM)), resp))
        assertTrue("cool ${cool.cct}", cool.cct!! in 5000.0..8000.0)
        assertTrue("warm ${warm.cct}", warm.cct!! in 2400.0..4000.0)
        val red = LightMetrics.analyse(b.wl, b.counts(SimScene.Emit(SimEmitter.LED_RED)))
        assertEquals(630.0, red.peak!!.nm, 2.0); assertEquals(18.0, red.peak!!.fwhmNm, 3.0)
    }

    @Test fun flameLinesOnNirSimulator() {
        val b = Bench(SimModel.NIR)
        val m = EmissionLines.measure(b.wl, b.counts(SimScene.Emit(SimEmitter.FLAME_MIX)), EmissionLines.Config(EmissionLines.Preset.FLAME))
        assertTrue(m.first { it.line.label == "Li" }.present)
        assertTrue(m.first { it.line.nm == 766.5 }.present)
        assertFalse(m.first { it.line.label == "Cs" }.present)
    }

    /** Real-world caution: the O2 A band (760 nm) in daylight sits next to the K I lines. */
    @Test fun daylightOxygenBandDoesNotFakeAFire() {
        val b = Bench(SimModel.NIR)
        b.sim.setIntegrationTimeMicros(200_000)
        b.sim.scene = SimScene.Reflect(SimTarget.LEAF_HEALTHY, SimLight.DAYLIGHT)
        val raw = b.sim.acquire().map { it.toDouble() }.toDoubleArray()
        val r = FireDetector.analyse(b.wl, raw, FireDetector.Config())
        println("daylight leaf: K1 %.1f %%, K2 %.1f %%, fire=${r.fire}".format(r.window1.percentAboveFloor, r.window2.percentAboveFloor))
        assertFalse(r.fire)
    }
}
