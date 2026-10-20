package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.sim.SimClock
import com.rfsat.pocketspectro.tools.availability
import com.rfsat.pocketspectro.sim.SimDisplay
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimFilter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimModel
import com.rfsat.pocketspectro.sim.SimSample
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.sim.SimWater
import com.rfsat.pocketspectro.sim.SimulatedSpectrometer
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.ColourQuality
import com.rfsat.pocketspectro.tools.DisplayCheck
import com.rfsat.pocketspectro.tools.Fluorimetry
import com.rfsat.pocketspectro.tools.ForelUle
import com.rfsat.pocketspectro.tools.GasColumns
import com.rfsat.pocketspectro.tools.Gems
import com.rfsat.pocketspectro.tools.GrowthCurve
import com.rfsat.pocketspectro.tools.Indicator
import com.rfsat.pocketspectro.tools.LeafClip
import com.rfsat.pocketspectro.tools.LightExposure
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.Munsell
import com.rfsat.pocketspectro.tools.NucleicAcids
import com.rfsat.pocketspectro.tools.PhysicsLab
import com.rfsat.pocketspectro.tools.Pigments
import com.rfsat.pocketspectro.tools.PlantLight
import com.rfsat.pocketspectro.tools.Pulse
import com.rfsat.pocketspectro.tools.SkinOptics
import com.rfsat.pocketspectro.tools.Solar
import com.rfsat.pocketspectro.tools.TestKits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/** The 1.8.0 tools on the simulator's scenes. */
class NewToolsTest2 {

    private class Bench(model: SimModel, us: Long = 50_000) {
        val sim = SimulatedSpectrometer(model = model).apply { setIntegrationTimeMicros(us) }
        val wl = sim.info.wavelengths()
        fun raw(n: Int = 4): DoubleArray { val acc = DoubleArray(wl.size); repeat(n) { val s = sim.acquire(); for (i in acc.indices) acc[i] += s[i] / n.toDouble() }; return acc }
        val dark: DoubleArray = run { sim.setShutter(false); val d = raw(); sim.setShutter(true); d }
        fun counts(scene: SimScene, n: Int = 4): DoubleArray { sim.scene = scene; val m = raw(n); return DoubleArray(m.size) { m[it] - dark[it] } }
        fun ratio(scene: SimScene, blank: SimScene, n: Int = 4): DoubleArray { val r = counts(blank, n); val s = counts(scene, n); return DoubleArray(s.size) { if (r[it] > 1) s[it] / r[it] else Double.NaN } }
        fun abs(scene: SimScene, blank: SimScene = SimScene.Cuvette(0.0), n: Int = 4) = ratio(scene, blank, n).map { if (it > 0) -log10(it) else Double.NaN }.toDoubleArray()
    }

    private val day = 1_781_481_600_000L
    private fun solar(h: Double) = Solar.solarHourToUtcMs(day, -6.26, h)
    private fun frozen(h: Double) = SimClock(0L, solar(h), 0.0)

    @Test fun growthCurve() {
        val b = Bench(SimModel.VIS)
        val blank = b.counts(SimScene.Cuvette(0.0))
        val now = System.currentTimeMillis()
        val pts = (0 until 40).map { k ->
            val h = k * 0.15
            val s = b.counts(SimScene.Culture(now - (h * 3_600_000 / 60).toLong()), 2)
            val a = DoubleArray(s.size) { if (s[it] > 0) -log10(s[it] / blank[it]) else Double.NaN }
            GrowthCurve.Point(h * 3600, GrowthCurve.od600(b.wl, a)!!)
        }
        val f = GrowthCurve.fit(pts)!!
        println("growth: mu ${f.muPerHour} /h doubling ${f.doublingMin} min lag ${f.lagMin} min max ${f.maxOd}")
        assertEquals(1.2, f.muPerHour, 0.2); assertEquals(36.0, f.lagMin, 15.0); assertTrue(f.maxOd > 1.0)
    }

    @Test fun dnaAndProtein() {
        val b = Bench(SimModel.UV, 100_000)
        fun r(s: SimSample, k: NucleicAcids.Kind = NucleicAcids.Kind.DS_DNA) = NucleicAcids.analyse(b.wl, b.abs(SimScene.Cuvette(0.0, sample = s), n = 8), k)!!
        val pure = r(SimSample.DNA_PURE); val prot = r(SimSample.DNA_PROTEIN); val gua = r(SimSample.DNA_GUANIDINE); val bsa = r(SimSample.PROTEIN_BSA, NucleicAcids.Kind.PROTEIN)
        println("DNA $pure\n +protein $prot\n +guanidine $gua\n BSA $bsa")
        assertEquals(1.85, pure.r260280, 0.08); assertEquals(2.2, pure.r260230, 0.25); assertEquals(50.0, pure.concentration, 4.0)
        assertTrue(prot.r260280 < 1.6); assertTrue(gua.r260230 < 1.3); assertEquals(0.57, bsa.r260280, 0.07)
    }

    @Test fun testKits() {
        val b = Bench(SimModel.VIS)
        for ((m, c) in listOf(TestKits.Method.CHLORINE_DPD to 2.0, TestKits.Method.AMMONIA to 0.5, TestKits.Method.NITRITE to 0.1, TestKits.Method.IRON to 2.0, TestKits.Method.PHOSPHATE to 0.8)) {
            val nm = TestKits.wavelength(m, b.wl)
            val a = TestKits.absorbance(b.wl, b.abs(SimScene.Kit(m.ordinal, c), n = 6), nm)!!
            val got = a / TestKits.defaultResponse(m, b.wl)
            println("kit $m at $nm: $got (set $c)")
            assertEquals(c, got, c * 0.08 + 0.01)
        }
    }

    @Test fun indicatorPh() {
        val b = Bench(SimModel.VIS)
        val d = Indicator.Dye.BROMOTHYMOL_BLUE
        val acid = b.abs(SimScene.Indicator(d.ordinal, 2.0)); val base = b.abs(SimScene.Indicator(d.ordinal, 12.0))
        val ends = Indicator.ends(b.wl, acid, base, d)
        for (ph in listOf(6.5, 7.1, 7.6)) {
            val a = b.abs(SimScene.Indicator(d.ordinal, ph))
            val r1 = Indicator.pH(b.wl, a, d, ends)!!; val r2 = Indicator.pH(b.wl, a, d, Indicator.defaultEnds(d))!!
            println("pH $ph: measured ends ${r1.pH}, default ends ${r2.pH}")
            assertEquals(ph, r1.pH, 0.05); assertEquals(ph, r2.pH, 0.08)
        }
    }

    @Test fun fluorimeter() {
        val b = Bench(SimModel.VIS)
        val p = Fluorimetry.Preset.FLUORESCEIN
        val blank = b.counts(SimScene.Fluor(p.ordinal, 0.0))
        fun sig(c: Double): Double { val s = b.counts(SimScene.Fluor(p.ordinal, c)); return Fluorimetry.measure(b.wl, DoubleArray(s.size) { s[it] - blank[it] }, 50_000, p)!!.signal }
        val k = sig(10.0) / 10.0
        val five = sig(5.0) / k
        println("fluorescein 5 µg/L reads $five")
        assertEquals(5.0, five, 0.3)
        val nb = Bench(SimModel.NIR)
        val cp = Fluorimetry.Preset.CHLOROPHYLL_A
        val cb = nb.counts(SimScene.Fluor(cp.ordinal, 0.0)); val cs = nb.counts(SimScene.Fluor(cp.ordinal, 20.0))
        val r = Fluorimetry.measure(nb.wl, DoubleArray(cs.size) { cs[it] - cb[it] }, 50_000, cp)!!
        println("chlorophyll peak ${r.peakNm}")
        assertEquals(683.0, r.peakNm!!, 3.0)
    }

    @Test fun physicsLab() {
        val b = Bench(SimModel.VIS)
        for ((e, name) in listOf(SimEmitter.H_TUBE to "Hydrogen", SimEmitter.HE_TUBE to "Helium", SimEmitter.NE_TUBE to "Neon", SimEmitter.HG_AR_LAMP to "Mercury")) {
            val l = PhysicsLab.lines(b.wl, b.counts(SimScene.Emit(e)))
            val id = PhysicsLab.identify(l, b.wl)
            println("tube $e -> ${id.take(3)}")
            assertEquals(name, id.first().element)
        }
        val ryd = PhysicsLab.rydberg(PhysicsLab.lines(b.wl, b.counts(SimScene.Emit(SimEmitter.H_TUBE))))!!
        println("Rydberg ${ryd.r} (${ryd.errorPercent} %) from ${ryd.lines}")
        assertEquals(0.0, ryd.errorPercent, 0.2)
        val leds = listOf(SimEmitter.LED_BLUE, SimEmitter.LED_GREEN, SimEmitter.LED_YELLOW, SimEmitter.LED_RED).map { e ->
            val pk = com.rfsat.pocketspectro.tools.Spectra.peak(b.wl, b.counts(SimScene.Emit(e)))!!.nm
            pk to 1239.84 / pk
        }
        val h = PhysicsLab.planck(leds)!!
        println("Planck ${h.h} (${h.errorPercent} %)")
        assertEquals(0.0, h.errorPercent, 1.5)
    }

    @Test fun waterColour() {
        val b = Bench(SimModel.VIS, 200_000)
        val white = SimScene.Reflect(SimTarget.WHITE, SimLight.DAYLIGHT)
        fun r(w: SimWater) = ForelUle.analyse(b.wl, b.ratio(SimScene.WaterSurface(w), white, 6))!!
        val ocean = r(SimWater.CLEAR_OCEAN); val coast = r(SimWater.COASTAL); val algae = r(SimWater.ALGAL_BLOOM); val cyano = r(SimWater.CYANO_BLOOM); val humic = r(SimWater.HUMIC_LAKE)
        println("FU ocean ${ocean.fu} coast ${coast.fu} algae ${algae.fu} chl ${algae.chlRatio} pc ${algae.pcIndex} cyano pc ${cyano.pcIndex} humic ${humic.fu}")
        assertTrue(ocean.fu <= 3); assertTrue(coast.fu in 5..11); assertTrue(humic.fu >= 15)
        assertTrue(algae.chlRatio!! > 1.0); assertTrue(cyano.pcIndex!! > 0.25 && algae.pcIndex!! < 0.15)
    }

    @Test fun leafClip() {
        val b = Bench(SimModel.NIR)
        val empty = SimScene.LeafClip(0.0)
        val hi = LeafClip.measure(b.wl, b.ratio(SimScene.LeafClip(45.0), empty))!!
        val lo = LeafClip.measure(b.wl, b.ratio(SimScene.LeafClip(20.0), empty))!!
        println("leaf SPAD-like ${hi.spadLike} (${hi.chlorophyll} µg/cm²) vs ${lo.spadLike}")
        assertEquals(45.0, hi.spadLike, 3.0); assertTrue(lo.spadLike < hi.spadLike - 10)
    }

    @Test fun greenhouseLight() {
        val b = Bench(SimModel.VIS)
        val resp = LightMetrics.responseFromLamp(b.wl, b.counts(SimScene.Emit(SimEmitter.LAMP_2856K)), 2856.0)
        fun r(e: SimEmitter) = PlantLight.analyse(b.wl, LightMetrics.corrected(b.counts(SimScene.Emit(e)), resp), null)!!
        val sun = r(SimEmitter.SUNLIGHT); val shade = r(SimEmitter.CANOPY_SHADE); val grow = r(SimEmitter.LED_GROW)
        println("R:FR sun ${sun.rfr} shade ${shade.rfr}; grow LED red ${grow.redPercent} % blue ${grow.bluePercent} %")
        assertEquals(1.2, sun.rfr!!, 0.25); assertTrue(shade.rfr!! < 0.5); assertTrue(grow.redPercent > 50)
    }

    @Test fun ozoneAndNo2() {
        val u = Bench(SimModel.UV, 100_000)
        fun m(h: Double) = Solar.airmass(Solar.position(53.35, -6.26, solar(h)).zenithDeg)
        val l1 = GasColumns.ozoneRatio(u.wl, u.counts(SimScene.Sun(0.1, 1.3, 1.5, frozen(12.0), 300.0), 8))!!
        val l0 = GasColumns.l0FromKnown(l1, m(12.0), 300.0)
        val l2 = GasColumns.ozoneRatio(u.wl, u.counts(SimScene.Sun(0.1, 1.3, 1.5, frozen(15.0), 350.0), 8))!!
        val du = GasColumns.ozone(l2, l0, m(15.0))
        println("ozone $du DU (set 350)")
        assertEquals(350.0, du, 15.0)
        val v = Bench(SimModel.VIS, 100_000)
        val ref = v.counts(SimScene.Sun(0.1, 1.3, 1.5, frozen(12.0)), 8)
        val pol = v.counts(SimScene.Sun(0.1, 1.3, 1.5, frozen(12.0), no2Du = 0.6), 8)
        val (scd, err) = GasColumns.no2Slant(v.wl, pol, ref) { GasColumns.syntheticNo2(it) }!!
        val expect = 0.6 * GasColumns.MOLEC_PER_DU * m(12.0)
        println("NO2 slant $scd ± $err (expected $expect)")
        assertEquals(expect, scd, expect * 0.12)
    }

    @Test fun pigments() {
        val b = Bench(SimModel.VIS)
        val white = SimScene.Reflect(SimTarget.WHITE)
        for (name in listOf("Ultramarine (lapis lazuli)", "Azurite", "Smalt or cobalt blue", "Indigo", "Malachite", "Vermilion (cinnabar)", "Red lead (minium)",
                "Cadmium yellow", "Lead-tin yellow", "Carmine / lac (red lake)", "Madder lake", "Yellow ochre (goethite)", "White (lead white, chalk, gypsum)", "Black (carbon, bone black)", "Prussian blue")) {
            val r = b.ratio(SimScene.Pigment(name), white)
            val ft = Pigments.features(b.wl, r)
            val m = Pigments.match(b.wl, ft)
            println("   features $ft")
            println("pigment $name -> ${m.take(3).map { it.pigment.name + " %.2f".format(it.score) }}")
            assertEquals(name, m.first().pigment.name)
        }
    }

    @Test fun gems() {
        val b = Bench(SimModel.VIS)
        val open = SimScene.Transmit(SimFilter.OPEN)
        for (name in listOf("Blue sapphire (Fe)", "Peridot (Fe)", "Almandine garnet (Fe)", "Cobalt glass / synthetic blue spinel (Co)", "Emerald (Cr)", "Zircon (U)", "Cape diamond (N3)")) {
            val t = b.ratio(SimScene.Gem(name), open, 6)
            val m = Gems.match(Gems.absorptionBands(b.wl, t), Gems.Mode.ABSORPTION, b.wl)
            println("gem $name -> ${m.take(3).map { it.gem.name + " %.2f".format(it.score) }}")
            assertEquals(name, m.first().gem.name)
        }
        val n = Bench(SimModel.NIR)
        val f = PhysicsLab.lines(n.wl, n.counts(SimScene.Gem("Ruby (Cr)", true))).map { Gems.Feature(it.nm, it.height) }
        val fm = Gems.match(f, Gems.Mode.FLUORESCENCE, n.wl)
        println("ruby fluorescence -> ${fm.take(2)}")
        assertEquals("Ruby (Cr)", fm.first().gem.name)
    }

    @Test fun munsellSoil() {
        val chip = Munsell.all.first { it.hue == "7.5YR" && it.value == 4.0 && it.chroma == 6.0 }
        val m = Munsell.nearest(chip.lab)
        println("Munsell ${chip.notation} -> ${m.map { it.chip.notation + " ΔE %.2f".format(it.deltaE) }}; total ${Munsell.all.size}")
        assertEquals("7.5YR 4/6", m.first().chip.notation)
        val grey = Munsell.nearest(Colorimetry.Lab(51.0, 0.0, 0.0)).first()
        assertTrue(grey.chip.notation.startsWith("N 5"))
    }

    @Test fun displayCheck() {
        val b = Bench(SimModel.VIS)
        fun prim(d: SimDisplay) = (1..3).map { p -> DisplayCheck.xy(b.wl, b.counts(SimScene.Display(d, p)))!! }
        val lcd = DisplayCheck.gamut(prim(SimDisplay.LCD)); val oled = DisplayCheck.gamut(prim(SimDisplay.OLED))
        val white = ColourQuality.cct(ColourQuality.grid(b.wl, b.counts(SimScene.Display(SimDisplay.OLED, 0))))
        println("LCD $lcd\nOLED $oled\nOLED white ${white.kelvin} K (uncorrected)")
        assertTrue(oled.coverP3 > lcd.coverP3); assertTrue(lcd.coverSrgb > 60)
    }

    @Test fun skinAndLightDose() {
        val b = Bench(SimModel.VIS)
        val white = SimScene.Reflect(SimTarget.WHITE)
        fun s(m: Double, bl: Double, c: Double) = SkinOptics.measure(b.wl, b.ratio(SimScene.Skin(m, bl, c), white))!!
        val light = s(0.4, 0.4, 1.0); val burnt = s(0.4, 0.9, 1.0); val dark = s(3.0, 0.4, 1.0); val carrot = s(0.4, 0.4, 2.5)
        println("skin light $light burnt $burnt dark $dark carotenoid $carrot")
        assertTrue(burnt.erythema > light.erythema + 3); assertTrue(dark.melanin > light.melanin + 10); assertTrue(carrot.carotenoid!! > light.carotenoid!! + 5)
        val samples = (0 until 24 * 6).map { k -> val h = k / 6.0; LightExposure.Sample(k * 600_000L, 0.0, if (h in 9.0..17.0) 400.0 else if (h in 20.0..23.0) 30.0 else 0.5) }
        val sm = LightExposure.summary(samples, 7.0, 23.0) { it / 3_600_000.0 % 24 }
        println("light dose $sm")
        assertEquals(8.0, sm.hoursAbove250, 0.3); assertEquals(30.0, sm.eveningMax!!, 0.01)
    }

    @Test fun pulse() {
        val b = Bench(SimModel.NIR, 20_000)
        val start = System.currentTimeMillis()
        b.sim.scene = SimScene.Finger(72.0, start)
        val t = ArrayList<Double>(); val v = ArrayList<Double>()
        while (System.currentTimeMillis() - start < 15_000) {
            val s = b.sim.acquire(); val now = System.currentTimeMillis()
            t += (now - start) / 1000.0; v += (0 until s.size).filter { b.wl[it] in 800.0..900.0 }.map { s[it] - b.dark[it] }.average()
        }
        val r = Pulse.analyse(t.toDoubleArray(), v.toDoubleArray())
        println("pulse ${r.bpm} bpm q ${r.quality} PI ${r.perfusionIndex} RMSSD ${r.rmssdMs} beats ${r.beats} frames ${t.size}")
        assertEquals(72.0, r.bpm!!, 4.0); assertTrue(r.perfusionIndex!! in 1.0..4.0)
    }

    @Test fun availabilityPartialCoverage() {
        val nir = com.rfsat.pocketspectro.core.DeviceInfo("STS-NIR", "T", 1024, 0, 0, com.rfsat.pocketspectro.sim.SimModel.NIR.coefficients, null, 16383, true, fireCapable = true)
        val uv = com.rfsat.pocketspectro.core.DeviceInfo("STS-UV", "T", 1024, 0, 0, com.rfsat.pocketspectro.sim.SimModel.UV.coefficients, null, 16383, true)
        fun av(t: String, i: com.rfsat.pocketspectro.core.DeviceInfo) = com.rfsat.pocketspectro.tools.ToolId.valueOf(t).availability(i)
        assertTrue(av("FIRE", nir) is com.rfsat.pocketspectro.tools.Availability.Ok)
        assertTrue(av("LEAF_CLIP", nir) is com.rfsat.pocketspectro.tools.Availability.Ok)
        assertTrue(av("COLOUR", nir) is com.rfsat.pocketspectro.tools.Availability.Partial)
        assertTrue(av("GREENHOUSE", nir) is com.rfsat.pocketspectro.tools.Availability.Partial)
        assertTrue(av("UV_INDEX", nir) is com.rfsat.pocketspectro.tools.Availability.NeedsRange)
        assertTrue(av("NUCLEIC", uv) is com.rfsat.pocketspectro.tools.Availability.Ok)
        assertTrue(av("PULSE", uv) is com.rfsat.pocketspectro.tools.Availability.NeedsRange)
        assertTrue(av("SKIN", uv) is com.rfsat.pocketspectro.tools.Availability.Partial)
        assertTrue(av("EMISSION", uv) is com.rfsat.pocketspectro.tools.Availability.Ok)
    }

    @Test fun jobClashes() {
        fun n(t: com.rfsat.pocketspectro.tools.ToolId, auto: Boolean = false) = com.rfsat.pocketspectro.tools.Jobs.needs(t, auto)!!
        fun c(a: com.rfsat.pocketspectro.tools.Jobs.Needs, b: com.rfsat.pocketspectro.tools.Jobs.Needs) = com.rfsat.pocketspectro.tools.Jobs.clash(a, b)
        val view = com.rfsat.pocketspectro.tools.Jobs.Clash.VIEW
        val ref = com.rfsat.pocketspectro.tools.Jobs.Clash.REFERENCE
        val ids = com.rfsat.pocketspectro.tools.ToolId.entries.associateBy { it.name }
        assertTrue(c(n(ids.getValue("LIGHT_DOSE")), n(ids.getValue("LAMPS"))).contains(view))
        assertTrue(c(n(ids.getValue("KINETICS")), n(ids.getValue("GROWTH"))).isEmpty())
        assertTrue(c(n(ids.getValue("GREENHOUSE"), true), n(ids.getValue("UV_DOSE"))).isEmpty())
        assertTrue(c(n(ids.getValue("EMISSION")), n(ids.getValue("LAMPS"))).isEmpty())
        assertTrue(c(n(ids.getValue("VEGETATION")), n(ids.getValue("KINETICS"))).containsAll(listOf(view, ref)))
    }
}
