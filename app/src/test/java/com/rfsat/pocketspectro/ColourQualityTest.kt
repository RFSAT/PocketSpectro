package com.rfsat.pocketspectro

import com.rfsat.pocketspectro.tools.ColourQuality
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp

/** CRI and TM-30 against reference values computed with the colour-science library. */
class ColourQualityTest {
    private val nm = ColourQuality.NM
    private fun band(c: Double, fwhm: Double, a: Double) = DoubleArray(81) { a * exp(-((nm[it] - c) / (fwhm / 1.665)).let { x -> x * x }) }
    private fun sum(a: DoubleArray, b: DoubleArray) = DoubleArray(81) { a[it] + b[it] }
    private val fl2 = javaClass.getResourceAsStream("/data/fl2.csv")!!.bufferedReader().readLines().first { !it.startsWith("#") }.split(',').map { it.toDouble() }.toDoubleArray()

    private fun check(name: String, s: DoubleArray, ra: Double, r9: Double, rf: Double, rg: Double, cct: Double, duv: Double) {
        val c = ColourQuality.cri(s); val t = ColourQuality.tm30(s)
        println("$name: CCT ${c.cct.kelvin} Duv ${c.cct.duv} Ra ${c.ra} R9 ${c.r9} Rf ${t.rf} Rg ${t.rg}")
        assertEquals("$name CCT", cct, c.cct.kelvin, cct * 0.002)
        assertEquals("$name Duv", duv, c.cct.duv, 0.0006)
        assertEquals("$name Ra", ra, c.ra, 0.6)
        assertEquals("$name R9", r9, c.r9, 1.5)
        assertEquals("$name Rf", rf, t.rf, 0.6)
        assertEquals("$name Rg", rg, t.rg, 0.8)
    }

    @Test fun incandescent() = check("A", ColourQuality.planck(2856.0), 100.0, 100.0, 100.0, 100.0, 2855.5, 0.0)
    @Test fun fluorescentFl2() = check("FL2", fl2, 64.23, -83.59, 70.12, 86.42, 4224.5, 0.00179)
    @Test fun coolLed() = check("LED cool", sum(band(451.0, 20.0, 1.0), band(555.0, 115.0, 0.62)), 64.54, -99.0, 70.57, 83.80, 6496.7, 0.02014)
    @Test fun warmLed() = check("LED warm", sum(band(451.0, 20.0, 0.45), band(595.0, 130.0, 0.85)), 73.97, -11.98, 77.56, 93.85, 2970.2, 0.00305)
    @Test fun daylightD65() = check("D65", ColourQuality.daylight(6504.0), 100.0, 100.0, 100.0, 100.0, 6503.6, 0.00321)
}
