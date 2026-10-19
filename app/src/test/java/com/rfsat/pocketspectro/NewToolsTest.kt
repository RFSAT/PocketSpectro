package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.sim.SimClock
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimFilter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimModel
import com.rfsat.pocketspectro.sim.SimOptics
import com.rfsat.pocketspectro.sim.SimSample
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.sim.SimulatedSpectrometer
import com.rfsat.pocketspectro.tools.Chemometrics
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.Drinks
import com.rfsat.pocketspectro.tools.FilmThickness
import com.rfsat.pocketspectro.tools.FilterTest
import com.rfsat.pocketspectro.tools.Fluorescence
import com.rfsat.pocketspectro.tools.HealthCheck
import com.rfsat.pocketspectro.tools.LampClassifier
import com.rfsat.pocketspectro.tools.LightHealth
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.OliveOil
import com.rfsat.pocketspectro.tools.Pyrometry
import com.rfsat.pocketspectro.tools.Raman
import com.rfsat.pocketspectro.tools.Solar
import com.rfsat.pocketspectro.tools.SunPhotometer
import com.rfsat.pocketspectro.tools.UvIndex
import com.rfsat.pocketspectro.tools.WaterUv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/** The new tools, each run on the simulator's scenes, as a user without hardware would try them. */
class NewToolsTest {

    private class Bench(model: SimModel, us: Long = 50_000) {
        val sim = SimulatedSpectrometer(model = model).apply { setIntegrationTimeMicros(us) }
        val wl = sim.info.wavelengths()
        fun raw(n: Int = 4): DoubleArray {
            val acc = DoubleArray(wl.size)
            repeat(n) { val s = sim.acquire(); for (i in acc.indices) acc[i] += s[i] / n.toDouble() }
            return acc
        }
        val dark: DoubleArray = run { sim.setShutter(false); val d = raw(); sim.setShutter(true); d }
        fun counts(scene: SimScene, n: Int = 4): DoubleArray { sim.scene = scene; val m = raw(n); return DoubleArray(m.size) { m[it] - dark[it] } }
        fun ratio(scene: SimScene, blank: SimScene, n: Int = 4): DoubleArray {
            val r = counts(blank, n); val s = counts(scene, n)
            return DoubleArray(s.size) { if (r[it] > 1) s[it] / r[it] else Double.NaN }
        }
        fun absorbance(scene: SimScene, blank: SimScene, n: Int = 4) = ratio(scene, blank, n).map { if (it > 0) -log10(it) else Double.NaN }.toDoubleArray()
    }

    // 15 June 2026, Dublin.
    private val day = 1_781_481_600_000L
    private fun solar(hour: Double) = Solar.solarHourToUtcMs(day, -6.26, hour)
    private fun frozen(hour: Double) = SimClock(0L, solar(hour), 0.0)

    @Test fun sunPositionIsSensible() {
        val noon = Solar.position(53.35, -6.26, solar(12.0))
        assertEquals(53.35 - 23.3, noon.zenithDeg, 0.5)
        assertEquals(180.0, noon.azimuthDeg, 3.0)
        assertTrue(Solar.position(53.35, -6.26, solar(0.0)).zenithDeg > 90)
    }

    @Test fun sunPhotometerRecoversAerosolAndWater() {
        val b = Bench(SimModel.NIR, 20_000)
        val atm = SunPhotometer.Atmosphere()
        fun sig(sc: SimScene.Sun) = SunPhotometer.signals(b.wl, b.counts(sc, 8), 20_000)
        fun m(h: Double) = Solar.airmass(Solar.position(53.35, -6.26, solar(h)).zenithDeg)
        // Calibrate on a known clear morning, then measure a hazier, drier afternoon.
        val cal = SimScene.Sun(0.10, 1.4, 1.8, frozen(10.0))
        val v0 = SunPhotometer.calibrateFromKnown(sig(cal), m(10.0), Solar.distanceFactor(solar(10.0)), atm, 0.10, 1.4, 1.8)
        val r = SunPhotometer.analyse(sig(SimScene.Sun(0.30, 1.2, 1.0, frozen(15.0))), v0, m(15.0), Solar.distanceFactor(solar(15.0)), atm)
        println("sun: aod=${r.aod} a=${r.angstrom} aod500=${r.aod500} pwv=${r.pwvCm}")
        assertEquals(0.30, r.aod500!!, 0.03)
        assertEquals(1.2, r.angstrom!!, 0.2)
        assertEquals(1.0, r.pwvCm!!, 0.15)
        // Langley over a morning gives the same calibration.
        val pts = (0 until 40).map { k ->
            val h = 5.6 + k * 0.06
            SunPhotometer.LangleyPoint(m(h), sig(SimScene.Sun(0.10, 1.4, 1.8, frozen(h))), Solar.distanceFactor(solar(h)))
        }
        val lf = SunPhotometer.langley(pts, atm)!!
        println("langley: m=${lf.mRange} v0=${lf.v0} cal=$v0")
        for (nm in listOf(675.0, 870.0, 1020.0)) assertEquals(1.0, lf.v0.getValue(nm) / v0.getValue(nm), 0.03)
        assertEquals(1.0, lf.v0.getValue(940.0) / v0.getValue(940.0), 0.08)
    }

    @Test fun pyrometerReadsGlowingBodies() {
        val b = Bench(SimModel.NIR)
        val resp = LightMetrics.responseFromLamp(b.wl, b.counts(SimScene.Emit(SimEmitter.LAMP_2856K)), 2856.0)
        for ((e, k) in listOf(SimEmitter.BODY_1100K to 1100.0, SimEmitter.BODY_1500K to 1500.0, SimEmitter.BODY_1900K to 1900.0)) {
            val r = Pyrometry.fit(b.wl, b.counts(SimScene.Emit(e)), resp)!!
            println("pyro $e: ${r.kelvin} r2=${r.r2}")
            assertEquals(k, r.kelvin, k * 0.04)
        }
    }

    @Test fun flameTemperatureAndSunGlint() {
        val b = Bench(SimModel.NIR, 100_000)
        val resp = LightMetrics.responseFromLamp(b.wl, b.counts(SimScene.Emit(SimEmitter.LAMP_2856K)), 2856.0)
        b.sim.scene = SimScene.Field
        val bg = b.raw(8)
        b.sim.fireOn = true
        val fire = b.raw(8)
        b.sim.fireOn = false
        val excess = DoubleArray(bg.size) { fire[it] - bg[it] }
        val r = Pyrometry.fit(b.wl, excess, resp, 700.0, 1080.0, Pyrometry.FIRE_EXCLUDE)!!
        println("flame: ${r.kelvin} r2=${r.r2}")
        assertEquals(1250.0, r.kelvin, 150.0)
        assertFalse(r.sunLike)
        val glint = Pyrometry.fit(b.wl, b.counts(SimScene.Reflect(SimTarget.WHITE, SimLight.DAYLIGHT)), resp, 700.0, 1080.0, Pyrometry.FIRE_EXCLUDE)!!
        println("glint: ${glint.kelvin}")
        assertTrue(glint.sunLike)
    }

    @Test fun teachAndPredictSugar() {
        val b = Bench(SimModel.NIR)
        val white = SimScene.Reflect(SimTarget.WHITE)
        val ref = b.counts(white, 8)
        fun x(seed: Long): DoubleArray {
            val s = b.counts(SimScene.Fruit(seed), 2)
            return Chemometrics.spectrum(b.wl, DoubleArray(s.size) { s[it] / ref[it] }, 700.0, 1050.0)
        }
        val samples = (1L..30L).map { Chemometrics.Sample("f$it", SimOptics.fruit(it).brix, x(it)) }
        val model = Chemometrics.Model("Brix", "°Bx", 700.0, 1050.0, Chemometrics.Pre.SNV, samples)
        val fit = Chemometrics.build(model)!!
        val err = (100L..111L).map { Chemometrics.predict(fit, model, x(it)) - SimOptics.fruit(it).brix }
        val rmsep = kotlin.math.sqrt(err.sumOf { it * it } / err.size)
        println("PLS: LV=${fit.pls.components} RMSECV=${fit.rmsecv} Q2=${fit.q2} RMSEP=$rmsep")
        assertTrue("RMSEP $rmsep", rmsep < 0.8)
        assertTrue(fit.q2 > 0.85)
    }

    @Test fun fluorescenceHealthyAboveStressed() {
        val b = Bench(SimModel.NIR, 50_000)
        val white = b.counts(SimScene.Reflect(SimTarget.WHITE, SimLight.DAYLIGHT), 8)
        fun f(t: SimTarget) = Fluorescence.measure(b.wl, b.counts(SimScene.Reflect(t, SimLight.DAYLIGHT), 8), white, Fluorescence.Band.O2A)!!
        val h = f(SimTarget.LEAF_HEALTHY); val s = f(SimTarget.LEAF_STRESSED); val soil = f(SimTarget.SOIL)
        println("SIF760 healthy ${h.percentOfIrradiance} stressed ${s.percentOfIrradiance} soil ${soil.percentOfIrradiance} depth ${h.referenceDepth}")
        assertTrue(h.percentOfIrradiance > 0.4)
        assertTrue(h.percentOfIrradiance > s.percentOfIrradiance + 0.2)
        assertTrue(abs(soil.percentOfIrradiance) < 0.25)
    }

    @Test fun ramanIdentifiesMaterials() {
        val b = Bench(SimModel.NIR, 200_000)
        for (name in listOf("Polystyrene (PS)", "Polyethylene (PE)", "PET", "Calcite (CaCO₃)", "Ethanol", "Cyclohexane", "Paracetamol")) {
            val c = b.counts(SimScene.Raman(name), 4)
            val laser = Raman.laserLine(b.wl, c, 785.0) ?: 785.0
            val sp = Raman.prepare(b.wl, c, laser)!!
            val pk = Raman.peaks(sp)
            val m = Raman.match(pk, sp.shift.first()..sp.shift.last(), Raman.LIBRARY)
            println("raman $name: laser $laser peaks ${pk.take(6).map { "%.0f".format(it.shift) }} -> ${m.take(3).map { it.ref.name + " %.2f".format(it.score) }}")
            assertEquals(name, m.first().ref.name)
        }
    }

    @Test fun lampTypesAreRecognised() {
        val b = Bench(SimModel.VIS)
        val expect = mapOf(
            SimEmitter.LAMP_LPS to LampClassifier.Type.LOW_PRESSURE_SODIUM,
            SimEmitter.LAMP_HPS to LampClassifier.Type.HIGH_PRESSURE_SODIUM,
            SimEmitter.LAMP_MERCURY to LampClassifier.Type.MERCURY,
            SimEmitter.LAMP_METAL_HALIDE to LampClassifier.Type.METAL_HALIDE,
            SimEmitter.LAMP_FLUORESCENT to LampClassifier.Type.FLUORESCENT,
            SimEmitter.LED_WHITE_COOL to LampClassifier.Type.LED,
            SimEmitter.LED_WHITE_WARM to LampClassifier.Type.LED,
            SimEmitter.LAMP_2856K to LampClassifier.Type.INCANDESCENT,
        )
        for ((e, t) in expect) {
            val r = LampClassifier.classify(b.wl, b.counts(SimScene.Emit(e)))!!
            println("lamp $e -> ${r.type} ${r.features}")
            assertEquals(e.name, t, r.type)
        }
    }

    @Test fun melanopicMatchesCie() {
        val g = (380..780).map { it.toDouble() }.toDoubleArray()
        val d65 = LightHealth.analyse(g, DoubleArray(g.size) { Colorimetry.d65(g[it]) })!!
        val e = LightHealth.analyse(g, DoubleArray(g.size) { 1.0 })!!
        println("mel D65 ${d65.melanopicDer} ${d65.melanopicElr} E ${e.melanopicDer} BLH D65 ${d65.blueHazardElr}")
        assertEquals(1.0, d65.melanopicDer, 1e-6)
        assertEquals(1.3262, d65.melanopicElr, 0.01)
        assertEquals(0.9058, e.melanopicDer, 0.006)
        assertEquals(490.0, g[g.indices.maxBy { LightHealth.melanopic(g[it]) }], 2.0)
    }

    @Test fun drinkColours() {
        val b = Bench(SimModel.VIS)
        val blank = SimScene.Cuvette(0.0)
        fun a(s: SimSample) = b.absorbance(SimScene.Cuvette(0.0, sample = s), blank, 6)
        val lager = Drinks.beer(b.wl, a(SimSample.BEER_LAGER))!!
        val ale = Drinks.beer(b.wl, a(SimSample.BEER_ALE))!!
        val stout = Drinks.beer(b.wl, a(SimSample.BEER_STOUT_DILUTED), dilution = 10.0)!!
        println("beer ${lager.ebc} ${ale.ebc} ${stout.ebc}")
        assertEquals(8.0, lager.ebc, 0.6); assertEquals(30.0, ale.ebc, 1.5); assertEquals(120.0, stout.ebc, 6.0)
        assertFalse(lager.turbid)
        val wine = Drinks.wine(b.wl, a(SimSample.WINE_RED_1MM), pathMm = 1.0)!!
        println("wine CI ${wine.intensity} hue ${wine.hue}")
        assertTrue(wine.intensity in 6.0..12.0); assertTrue(wine.hue in 0.3..0.8)
        val light = Drinks.honey(b.wl, a(SimSample.HONEY_LIGHT))!!
        val amber = Drinks.honey(b.wl, a(SimSample.HONEY_AMBER))!!
        println("honey ${light.pfundMm} ${amber.pfundMm}")
        assertEquals(30.0, light.pfundMm, 3.0); assertEquals(Drinks.HoneyGrade.WHITE, light.grade)
        assertEquals(100.0, amber.pfundMm, 5.0); assertEquals(Drinks.HoneyGrade.AMBER, amber.grade)
    }

    @Test fun oliveOilGrades() {
        val b = Bench(SimModel.UV, 100_000)
        val blank = SimScene.Cuvette(0.0)
        fun r(s: SimSample) = OliveOil.analyse(b.wl, b.absorbance(SimScene.Cuvette(0.0, sample = s), blank, 16))!!
        val e = r(SimSample.OLIVE_EXTRA_VIRGIN); val v = r(SimSample.OLIVE_VIRGIN); val o = r(SimSample.OLIVE_OXIDISED)
        println("olive EVOO $e\n virgin $v\n oxidised $o")
        assertEquals(OliveOil.Category.EXTRA_VIRGIN, e.category)
        assertEquals(OliveOil.Category.VIRGIN, v.category)
        assertEquals(OliveOil.Category.ABOVE_VIRGIN_LIMITS, o.category)
        assertTrue(o.deltaK > 0.01)
    }

    @Test fun waterNitrateAndOrganics() {
        val b = Bench(SimModel.UV, 100_000)
        val blank = SimScene.Cuvette(0.0)
        fun r(s: SimSample) = WaterUv.analyse(b.wl, b.absorbance(SimScene.Cuvette(0.0, sample = s), blank, 8), docMgL = 10.0)!!
        val n = r(SimSample.WATER_NITRATE); val p = r(SimSample.WATER_PEATY); val c = r(SimSample.WATER_CLEAN)
        println("water nitrate ${n.nitrateN} peaty ${p.uv254} ${p.nitrateN} clean ${c.nitrateN}")
        assertEquals(8.0, n.nitrateN, 0.6); assertEquals(1.0, p.nitrateN, 0.5); assertEquals(0.5, c.nitrateN, 0.4)
        assertEquals(40.0, p.uv254, 3.0)
    }

    @Test fun uvIndexAfterOneReference() {
        val b = Bench(SimModel.UV, 100_000)
        fun state(h: Double, du: Double): Pair<UvIndex.Result, Double> {
            val sc = SimScene.SunUv(du, frozen(h))
            val t = solar(h); val z = Solar.position(53.35, -6.26, t).zenithDeg
            val truth = SimOptics.trueUvIndex(kotlin.math.cos(Math.toRadians(z)), Solar.airmass(z), Solar.distanceFactor(t), sc)
            return UvIndex.analyse(b.wl, b.counts(sc, 8), null)!! to truth
        }
        val (r0, t0) = state(12.0, 300.0)
        val scale = UvIndex.scaleFrom(r0, t0)!!
        for ((h, du) in listOf(10.0 to 300.0, 14.0 to 260.0, 9.0 to 340.0)) {
            val (r, truth) = state(h, du)
            println("UVI at $h h, $du DU: ${scale * r.weighted} (true $truth) noon true $t0")
            assertEquals(truth, scale * r.weighted, maxOf(0.12 * truth, 0.3))
        }
        assertTrue(t0 in 5.0..9.5)
    }

    @Test fun filmThickness() {
        val b = Bench(SimModel.NIR)
        val white = SimScene.Reflect(SimTarget.WHITE)
        for ((d, ns, sub) in listOf(Triple(5000.0, 3.7, FilmThickness.Substrate.SILICON), Triple(20000.0, 1.52, FilmThickness.Substrate.GLASS),
            Triple(700.0, 3.7, FilmThickness.Substrate.SILICON), Triple(2000.0, 1.52, FilmThickness.Substrate.UNKNOWN))) {
            val r = FilmThickness.measure(b.wl, b.ratio(SimScene.Film(d, 1.5, ns), white, 4), 1.5, sub)
            println("film $d on $sub: ${r?.nm} ${r?.method} fringes ${r?.fringes} r2 ${r?.fitR2}")
            assertNotNull(r); assertEquals(d, r!!.nm, d * 0.04)
        }
    }

    @Test fun filtersAndEyewear() {
        val b = Bench(SimModel.VIS)
        val open = SimScene.Transmit(SimFilter.OPEN)
        fun r(f: SimFilter) = FilterTest.analyse(b.wl, b.ratio(SimScene.Transmit(f), open, 4))
        val sun = r(SimFilter.SUNGLASS_CAT3); val blue = r(SimFilter.BLUE_BLOCK); val red = r(SimFilter.RED_LONGPASS)
        println("sunglass ${sun.luminousPercent} cat ${sun.sunglassCategory} uv ${sun.uv380to400Percent}; blue ${blue.bluePercent}; red cut ${red.cutOnNm}")
        assertEquals(3, sun.sunglassCategory)
        assertEquals(true, sun.uv400)
        assertEquals(54.0, blue.bluePercent!!, 6.0)
        assertEquals(610.0, red.cutOnNm!!, 3.0)
        val od = FilterTest.opticalDensity(b.wl, b.counts(SimScene.Transmit(SimFilter.LASER_GOGGLE_532)), b.counts(open), 532.0)!!
        println("goggle OD ${od.od} limit ${od.atLimit}")
        assertTrue(od.atLimit || od.od > 2.5)
        assertTrue(od.od > 1.8)
        val u = Bench(SimModel.UV)
        val uv = FilterTest.analyse(u.wl, u.ratio(SimScene.Transmit(SimFilter.UV400_CLEAR), open, 4))
        println("uv400 ${uv.uv380to400Percent} uva ${uv.uvaPercent}")
        assertEquals(true, uv.uv400)
    }

    @Test fun healthCheck() {
        val b = Bench(SimModel.NIR, 100_000)
        b.sim.setShutter(false)
        val frames = (0 until 8).map { b.sim.acquire().map { it.toDouble() }.toDoubleArray() }
        b.sim.setShutter(true)
        val d = HealthCheck.dark(frames, 100.0)!!
        println("dark ${d.level} noise ${d.noise} hot ${d.hotPixels}")
        assertTrue(137 in d.hotPixels && 702 in d.hotPixels)
        assertEquals(4.6, d.noise, 1.5)
        b.sim.scene = SimScene.Emit(SimEmitter.LAMP_2856K)
        val pts = listOf(25.0, 50.0, 100.0).map { ms ->
            b.sim.setIntegrationTimeMicros((ms * 1000).toLong())
            b.sim.setShutter(false); val dk = b.raw(2); b.sim.setShutter(true)
            val s = b.raw(2)
            HealthCheck.LinearityPoint(ms, (0 until s.size).filter { b.wl[it] in 800.0..900.0 }.map { s[it] - dk[it] }.average())
        }
        val lin = HealthCheck.linearity(pts)!!
        println("linearity ${lin.ratePerMs} dev ${lin.maxDeviationPercent}")
        assertTrue(lin.maxDeviationPercent < HealthCheck.MAX_LINEARITY_DEV)
        b.sim.setIntegrationTimeMicros(50_000)
        val w = HealthCheck.wavelength(b.wl, b.counts(SimScene.Emit(SimEmitter.HG_AR_LAMP)))!!
        println("wavelength ${w.lines} lines, mean ${w.meanShiftNm}, max ${w.maxShiftNm}")
        assertTrue(abs(w.meanShiftNm) < 0.3)
    }

    @Test fun alertMessageAndResultPhotos() {
        val j = com.rfsat.pocketspectro.core.AlertSender.json(com.rfsat.pocketspectro.core.AlertSender.Alert("fire", 1_781_481_600_000L, 37.93, 23.75, 4.0,
            90.0, 25.6, 1253.0, "STS-NIR \"x\"", "S1", true))
        println(j)
        assertTrue(j.startsWith("{") && j.endsWith("}"))
        assertTrue("\"flame_temperature_k\":1253" in j && "\"latitude\":37.9300000" in j && "\"simulated\":true" in j)
        assertEquals(2 * j.count { it == '{' }, j.count { it == '{' } + j.count { it == '}' })
        val dir = kotlin.io.path.createTempDirectory("ev").toFile()
        try {
            val jr = com.rfsat.pocketspectro.data.ToolEventJournal(dir)
            // An old file without the photo column is upgraded on the next write.
            jr.file.writeText("time_utc,epoch_ms,latitude,longitude,accuracy_m,tool,label,value,colour,detail,model\n" +
                "2026-01-01T00:00:00Z,1,1.0,2.0,,MATCH,\"Old\",1,#000000,\"d\",\"m\"\n")
            jr.record(com.rfsat.pocketspectro.data.ToolEvent(2, 3.0, 4.0, null, "SUN", "AOD 0.25", 0.25, "#FDD835", "x", "STS", "photo_1.jpg"))
            val all = jr.load()
            assertEquals(2, all.size); assertEquals("", all[0].photo); assertEquals("photo_1.jpg", all[1].photo); assertEquals("Old", all[0].label)
        } finally { dir.deleteRecursively() }
    }
}
