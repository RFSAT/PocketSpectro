package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow

/**
 * Identification by spectral features: Munsell soil colour (renotation data), Forel-Ule water colour,
 * gas-discharge elements and the Rydberg / Planck constants (physics lab), gemstone absorption and
 * fluorescence lines, and historical pigments from their reflectance (FORS).
 */

// ================================= Munsell soil colour =========================================

object Munsell {

    data class Chip(val hue: String, val value: Double, val chroma: Double, val lab: Colorimetry.Lab) {
        val notation get() = if (chroma == 0.0) "N ${fmt(value)}/" else "$hue ${fmt(value)}/${fmt(chroma)}"
        private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }

    /** Hue pages of the Munsell Soil Color Charts (plus the gley pages' hues). */
    val SOIL_HUES = setOf("10R", "2.5YR", "5YR", "7.5YR", "10YR", "2.5Y", "5Y", "10Y", "5GY", "10GY", "5G", "10G", "5BG", "10BG", "5B", "10B", "5PB")

    val all: List<Chip> by lazy {
        val s = Munsell::class.java.getResourceAsStream("/data/munsell_real.csv") ?: return@lazy emptyList()
        val chips = s.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val p = l.split(','); Chip(p[0], p[1].toDouble(), p[2].toDouble(), Colorimetry.Lab(p[3].toDouble(), p[4].toDouble(), p[5].toDouble()))
        }
        // Neutral greys N 1/ … N 9/ from the ASTM D1535 value function.
        chips + (1..9).map { v -> val y = astmY(v.toDouble()); Chip("N", v.toDouble(), 0.0, Colorimetry.Lab(116 * kotlin.math.cbrt(y / 100) - 16, 0.0, 0.0)) }
    }

    /** Luminous reflectance Y (%) for Munsell value V (ASTM D1535). */
    fun astmY(v: Double) = 1.1914 * v - 0.22533 * v.pow(2) + 0.23352 * v.pow(3) - 0.020484 * v.pow(4) + 0.00081939 * v.pow(5)

    private val soilChips by lazy {
        all.filter { (it.hue in SOIL_HUES || it.hue == "N") && it.value in 2.0..8.0 && it.chroma <= 8.0 && (it.chroma == it.chroma.toLong().toDouble()) }
    }

    data class Match(val chip: Chip, val deltaE: Double)

    /** Nearest chips (CIEDE2000) among the soil-chart pages, and among all Munsell colours. */
    fun nearest(lab: Colorimetry.Lab, soilOnly: Boolean = true, n: Int = 3): List<Match> =
        (if (soilOnly) soilChips else all).map { Match(it, Colorimetry.deltaE2000(lab, it.lab)) }.sortedBy { it.deltaE }.take(n)
}

// ================================= Forel-Ule water colour ======================================

object ForelUle {
    /** FU colour chromaticities (Novoa et al. 2013, NWV scale) — for the legend swatches. */
    val XY = arrayOf(
        0.191 to 0.167, 0.199 to 0.200, 0.210 to 0.240, 0.227 to 0.288, 0.246 to 0.335, 0.266 to 0.376, 0.291 to 0.412,
        0.315 to 0.440, 0.337 to 0.462, 0.363 to 0.476, 0.386 to 0.487, 0.402 to 0.481, 0.416 to 0.474, 0.431 to 0.466,
        0.446 to 0.458, 0.461 to 0.449, 0.475 to 0.441, 0.489 to 0.433, 0.503 to 0.425, 0.516 to 0.416, 0.528 to 0.408,
    )
    /** Hue-angle transitions between FU i and i+1 (degrees, Novoa et al. 2013, Table 6). */
    private val TRANSITION = doubleArrayOf(227.168, 220.977, 209.994, 190.779, 163.084, 132.999, 109.054, 94.037, 83.346,
        74.572, 67.957, 62.186, 56.435, 50.665, 45.129, 39.769, 34.906, 30.439, 26.337, 22.741)

    /** Hue angle (degrees) of a chromaticity around the equal-energy white point, anticlockwise from +x. */
    fun hueAngle(x: Double, y: Double) = (Math.toDegrees(atan2(y - 1.0 / 3, x - 1.0 / 3)) + 360) % 360

    fun index(alpha: Double): Int { for (i in TRANSITION.indices) if (alpha >= TRANSITION[i]) return i + 1; return 21 }

    data class Result(val fu: Int, val hueAngle: Double, val x: Double, val y: Double,
                      /** Chlorophyll band ratio R705 / R665 (Gitelson / Gons). */ val chlRatio: Double?,
                      /** Depth of the phycocyanin trough at 620 nm relative to its shoulders. */ val pcIndex: Double?,
                      /** Reflectance near 800 nm (780 nm on visible units; suspended matter backscatter), %. */ val nirPercent: Double?)

    fun supported(wl: DoubleArray) = Spectra.covers(wl, 400.0, 700.0)

    /** [r]: water reflectance (water radiance / white panel radiance). */
    fun analyse(wl: DoubleArray, r: DoubleArray): Result? {
        if (!supported(wl)) return null
        var x = 0.0; var y = 0.0; var z = 0.0; var l = 400.0
        while (l <= 710.0) { val v = Spectra.at(wl, r, l).coerceAtLeast(0.0); x += v * Colorimetry.xBar(l); y += v * Colorimetry.yBar(l); z += v * Colorimetry.zBar(l); l += 1.0 }
        val s = x + y + z
        if (s <= 0) return null
        val cx = x / s; val cy = y / s
        val a = hueAngle(cx, cy)
        fun at(c: Double) = if (Spectra.covers(wl, c - 3, c + 3)) Spectra.bandMean(wl, r, c - 3, c + 3) else null
        val chl = at(705.0)?.let { n -> at(665.0)?.takeIf { it > 0 }?.let { n / it } }
        // Narrow shoulders (608 / 635 nm): wider ones read the curvature of the green-to-red slope of any algal water as a trough.
        val pc = at(620.0)?.let { t -> val s1 = at(608.0); val s2 = at(635.0); if (s1 != null && s2 != null && t > 0) (s1 + s2) / 2 / t - 1 else null }
        return Result(index(a), a, cx, cy, chl, pc, (at(800.0) ?: at(780.0))?.let { it * 100 })
    }
}

// ================================= Physics lab =================================================

object PhysicsLab {

    /** Strong visible / near-IR lines of common discharge tubes (air wavelengths, nm; NIST). */
    val ELEMENTS: List<Pair<String, DoubleArray>> = listOf(
        "Hydrogen" to doubleArrayOf(410.17, 434.05, 486.13, 656.28),
        "Helium" to doubleArrayOf(447.15, 471.31, 492.19, 501.57, 587.56, 667.82, 706.52, 728.13),
        "Neon" to doubleArrayOf(540.06, 585.25, 588.19, 594.48, 603.00, 607.43, 609.62, 614.31, 616.36, 621.73, 626.65, 630.48,
            633.44, 638.30, 640.22, 650.65, 659.90, 692.95, 703.24, 717.39, 724.52, 743.89),
        "Mercury" to doubleArrayOf(404.66, 435.83, 546.07, 576.96, 579.07),
        "Sodium" to doubleArrayOf(588.995, 589.592, 818.33, 819.48),
        "Argon" to doubleArrayOf(696.54, 706.72, 738.40, 750.39, 763.51, 772.38, 794.82, 800.62, 801.48, 810.37, 811.53, 826.45,
            840.82, 842.46, 852.14, 912.30, 922.45),
        "Krypton" to doubleArrayOf(557.03, 587.09, 760.15, 769.45, 785.48, 805.95, 810.44, 811.29, 819.00, 826.32, 829.81, 850.89, 877.68, 892.87),
        "Xenon" to doubleArrayOf(467.12, 473.42, 480.70, 482.97, 484.43, 492.32, 823.16, 828.01, 881.94, 895.23, 904.54, 916.27, 979.97, 992.32),
    )

    data class Line(val nm: Double, val height: Double)

    /** Emission peaks above [snr] × noise, strongest first. */
    fun lines(wl: DoubleArray, counts: DoubleArray, snr: Double = 8.0): List<Line> {
        val y = Spectra.smooth(counts, 1)
        val base = Spectra.median(y)
        val noise = Spectra.noise(counts).coerceAtLeast(1.0)
        val out = ArrayList<Line>()
        for (i in 3 until y.size - 3) {
            if (y[i] - base < snr * noise) continue
            var top = true
            for (k in i - 3..i + 3) if (k != i && y[k] > y[i]) { top = false; break }
            if (!top) continue
            val a = y[i - 1]; val b = y[i]; val c = y[i + 1]
            val d = (a - c) / (2 * (a - 2 * b + c)).let { if (it == 0.0) 1e-12 else it }
            out += Line(wl[i] + d.coerceIn(-0.5, 0.5) * (wl[i + 1] - wl[i - 1]) / 2, b - base)
        }
        return out.sortedByDescending { it.height }
    }

    data class ElementMatch(val element: String, val score: Double, val found: Int, val expected: Int)

    fun identify(found: List<Line>, wl: DoubleArray, tol: Double = 1.2): List<ElementMatch> {
        if (found.isEmpty()) return emptyList()
        val strong = found.filter { it.height > 0.15 * found.first().height }
        return ELEMENTS.mapNotNull { (name, ls) ->
            val inRange = ls.filter { it in wl.first() + 2..wl.last() - 2 }
            if (inRange.isEmpty()) return@mapNotNull null
            val got = inRange.count { l -> found.any { abs(it.nm - l) <= tol } }
            val explained = strong.count { s -> inRange.any { abs(s.nm - it) <= tol } }.toDouble() / strong.size
            ElementMatch(name, got.toDouble() / inRange.size * (0.3 + 0.7 * explained), got, inRange.size)
        }.sortedByDescending { it.score }
    }

    data class Rydberg(val r: Double, val errorPercent: Double, val lines: List<Pair<Int, Double>>)

    /** Rydberg constant from the Balmer lines found (n = 3…6 → 2), with air → vacuum correction. */
    fun rydberg(found: List<Line>): Rydberg? {
        val balmer = mapOf(3 to 656.28, 4 to 486.13, 5 to 434.05, 6 to 410.17)
        val pts = balmer.mapNotNull { (n, l0) -> found.filter { abs(it.nm - l0) <= 2.0 }.maxByOrNull { it.height }?.let { n to it.nm } }
        if (pts.size < 2) return null
        // 1/λ = R (1/4 − 1/n²): least squares through the origin.
        var sxy = 0.0; var sxx = 0.0
        for ((n, nm) in pts) { val x = 0.25 - 1.0 / (n * n); val y = 1 / (nm * 1.000277 * 1e-9); sxy += x * y; sxx += x * x }
        val r = sxy / sxx
        return Rydberg(r, 100 * (r - R_H) / R_H, pts)
    }

    /** Rydberg constant for hydrogen (reduced mass), m⁻¹. */
    const val R_H = 1.09678e7
    const val H = 6.62607015e-34

    data class Planck(val h: Double, val errorPercent: Double, val points: Int)

    /** Planck's constant from LED peak wavelengths (nm) and their threshold voltages: e·V = h·c/λ. */
    fun planck(leds: List<Pair<Double, Double>>): Planck? {
        if (leds.size < 2) return null
        var sxy = 0.0; var sxx = 0.0
        for ((nm, v) in leds) { val x = 1 / (nm * 1e-9); sxy += x * v; sxx += x * x }
        val slope = sxy / sxx // V·m
        val h = slope * 1.602176634e-19 / 2.99792458e8
        return Planck(h, 100 * (h - H) / H, leds.size)
    }
}

// ================================= Gemstones ===================================================

object Gems {
    enum class Mode { ABSORPTION, FLUORESCENCE }
    data class Gem(val name: String, val mode: Mode, val lines: List<Pair<Double, Double>>, val note: String = "")

    /** Diagnostic lines (nm) with weights, from gemmological hand-spectroscope practice. */
    val LIBRARY = listOf(
        Gem("Ruby (Cr)", Mode.FLUORESCENCE, listOf(693.5 to 1.0)),
        Gem("Red spinel (Cr)", Mode.FLUORESCENCE, listOf(675.0 to 0.5, 685.5 to 1.0, 698.0 to 0.6, 708.0 to 0.4)),
        Gem("Ruby (Cr)", Mode.ABSORPTION, listOf(405.0 to 0.6, 550.0 to 1.0, 693.5 to 0.5)),
        Gem("Emerald (Cr)", Mode.ABSORPTION, listOf(637.0 to 0.6, 681.5 to 1.0)),
        Gem("Alexandrite (Cr)", Mode.ABSORPTION, listOf(645.0 to 0.4, 655.0 to 0.5, 665.0 to 0.6, 679.5 to 1.0)),
        Gem("Blue sapphire (Fe)", Mode.ABSORPTION, listOf(450.0 to 1.0, 460.0 to 0.5, 471.0 to 0.4)),
        Gem("Peridot (Fe)", Mode.ABSORPTION, listOf(453.0 to 1.0, 477.0 to 0.8, 493.0 to 0.8)),
        Gem("Almandine garnet (Fe)", Mode.ABSORPTION, listOf(505.0 to 0.9, 527.0 to 1.0, 576.0 to 0.9)),
        Gem("Zircon (U)", Mode.ABSORPTION, listOf(589.0 to 0.4, 653.5 to 1.0, 660.5 to 0.5)),
        Gem("Cape diamond (N3)", Mode.ABSORPTION, listOf(415.5 to 1.0)),
        Gem("Cobalt glass / synthetic blue spinel (Co)", Mode.ABSORPTION, listOf(540.0 to 0.9, 580.0 to 1.0, 635.0 to 0.9)),
        Gem("Didymium glass / apatite (rare earths)", Mode.ABSORPTION, listOf(520.0 to 0.5, 580.5 to 1.0, 740.0 to 0.5, 800.0 to 0.4)),
    )

    data class Feature(val nm: Double, val strength: Double)

    /** Absorption bands: minima of transmittance [t] at least 3 % deep against their surroundings. */
    fun absorptionBands(wl: DoubleArray, t: DoubleArray): List<Feature> {
        val y = Spectra.smooth(t, 2)
        val out = ArrayList<Feature>()
        for (i in wl.indices) {
            val lo = wl.indices.filter { wl[it] in wl[i] - 25..wl[i] - 3 }; val hi = wl.indices.filter { wl[it] in wl[i] + 3..wl[i] + 25 }
            if (lo.isEmpty() || hi.isEmpty()) continue
            var bottom = true
            for (k in maxOf(0, i - 4)..minOf(y.size - 1, i + 4)) if (k != i && y[k] < y[i]) { bottom = false; break }
            if (!bottom) continue
            val shoulder = (lo.maxOf { y[it] } + hi.maxOf { y[it] }) / 2
            val depth = (shoulder - y[i]) / shoulder.coerceAtLeast(1e-6)
            if (depth > 0.03) out += Feature(wl[i], depth)
        }
        return out.sortedByDescending { it.strength }
    }

    data class Match(val gem: Gem, val score: Double)

    fun match(features: List<Feature>, mode: Mode, wl: DoubleArray, tol: Double = 4.0): List<Match> = LIBRARY.filter { it.mode == mode }.mapNotNull { g ->
        val lines = g.lines.filter { it.first in wl.first() + 3..wl.last() - 3 }
        if (lines.isEmpty()) return@mapNotNull null
        val w = lines.sumOf { it.second }
        Match(g, lines.filter { (nm, _) -> features.any { abs(it.nm - nm) <= tol } }.sumOf { it.second } / w)
    }.sortedByDescending { it.score }
}

// ================================= Pigments (FORS) =============================================

object Pigments {
    enum class Kind { INFLECTION, NO_INFLECTION, MINIMUM, MAXIMUM, LOW_NIR, DARK, BRIGHT_FLAT, DARK_FLAT }
    data class F(val kind: Kind, val nm: Double = 0.0, val tol: Double = 15.0, val weight: Double = 1.0)
    data class Pigment(val name: String, val features: List<F>)

    /**
     * Reflectance features of historical pigments (FORS literature, e.g. Aceto et al. 2014; Cosentino 2014):
     * inflection points of the S-shaped edge of reds and yellows, absorption minima of blues and lakes.
     */
    val LIBRARY = listOf(
        Pigment("Ultramarine (lapis lazuli)", listOf(F(Kind.MINIMUM, 600.0, 20.0), F(Kind.MAXIMUM, 450.0, 25.0, 0.6), F(Kind.INFLECTION, 700.0, 25.0, 0.4))),
        Pigment("Azurite", listOf(F(Kind.MAXIMUM, 465.0, 20.0), F(Kind.MINIMUM, 655.0, 25.0, 0.7), F(Kind.LOW_NIR, weight = 0.6), F(Kind.NO_INFLECTION, 715.0, 25.0, 0.5))),
        Pigment("Prussian blue", listOf(F(Kind.MAXIMUM, 450.0, 25.0, 0.6), F(Kind.MINIMUM, 700.0, 50.0), F(Kind.DARK, weight = 0.5), F(Kind.NO_INFLECTION, 715.0, 25.0, 0.8))),
        Pigment("Smalt or cobalt blue", listOf(F(Kind.MINIMUM, 545.0, 12.0), F(Kind.MINIMUM, 590.0, 12.0), F(Kind.MINIMUM, 640.0, 12.0), F(Kind.INFLECTION, 700.0, 30.0, 0.3))),
        Pigment("Indigo", listOf(F(Kind.MINIMUM, 660.0, 20.0), F(Kind.INFLECTION, 715.0, 15.0))),
        Pigment("Malachite", listOf(F(Kind.MAXIMUM, 525.0, 20.0), F(Kind.INFLECTION, 488.0, 15.0, 0.5), F(Kind.LOW_NIR, weight = 0.7))),
        Pigment("Vermilion (cinnabar)", listOf(F(Kind.INFLECTION, 600.0, 8.0))),
        Pigment("Red lead (minium)", listOf(F(Kind.INFLECTION, 570.0, 8.0))),
        Pigment("Cadmium red", listOf(F(Kind.INFLECTION, 615.0, 8.0))),
        Pigment("Red ochre (haematite)", listOf(F(Kind.INFLECTION, 580.0, 15.0), F(Kind.MINIMUM, 870.0, 30.0, 0.8))),
        Pigment("Yellow ochre (goethite)", listOf(F(Kind.INFLECTION, 545.0, 15.0), F(Kind.MINIMUM, 925.0, 30.0, 0.8))),
        Pigment("Cadmium yellow", listOf(F(Kind.INFLECTION, 505.0, 10.0))),
        Pigment("Lead-tin yellow", listOf(F(Kind.INFLECTION, 492.0, 8.0))),
        Pigment("Carmine / lac (red lake)", listOf(F(Kind.MINIMUM, 525.0, 10.0), F(Kind.MINIMUM, 565.0, 10.0), F(Kind.INFLECTION, 600.0, 15.0, 0.5))),
        Pigment("Madder lake", listOf(F(Kind.MINIMUM, 510.0, 10.0), F(Kind.MINIMUM, 540.0, 10.0))),
        Pigment("White (lead white, chalk, gypsum)", listOf(F(Kind.BRIGHT_FLAT))),
        Pigment("Black (carbon, bone black)", listOf(F(Kind.DARK_FLAT))),
    )

    data class Features(val inflections: List<Double>, val minima: List<Double>, val maxima: List<Double>, val meanVis: Double, val nirRatio: Double?, val flat: Boolean)

    fun features(wl: DoubleArray, r: DoubleArray): Features {
        val y = Spectra.smooth(r, 6)
        // Below 420 nm halogen light is weak and reflectance noisy: features are taken from 420 nm up.
        val idx = wl.indices.filter { wl[it] in 420.0..950.0 && it in 8 until wl.size - 8 }
        val d = DoubleArray(wl.size) { i -> if (i in 3 until wl.size - 3) (y[i + 3] - y[i - 3]) / (wl[i + 3] - wl[i - 3]) else 0.0 }
        val dmax = idx.maxOfOrNull { d[it] } ?: 0.0
        // Inflections: steep rises (≥ 35 % of the steepest and ≥ 0.002 per nm), one per edge (40 nm).
        val infl = ArrayList<Double>()
        for (i in idx.sortedByDescending { d[it] }) {
            if (d[i] < maxOf(0.35 * dmax, 0.002)) break
            if (infl.none { abs(it - wl[i]) < 40 }) infl += wl[i]
        }
        fun side(i: Int, lo: Double, hi: Double, f: (List<Double>) -> Double): Double? {
            val l = wl.indices.filter { wl[it] in wl[i] - hi..wl[i] - lo }.map { y[it] }
            val r2 = wl.indices.filter { wl[it] in wl[i] + lo..wl[i] + hi }.map { y[it] }
            return if (l.isEmpty() || r2.isEmpty()) null else minOf(f(l), f(r2))
        }
        // Minima / maxima must stand out on both sides (within 8–50 nm) by 8 % and at least 0.01.
        val minima = idx.filter { i -> (i - 8..i + 8).all { it == i || y[it] >= y[i] } &&
            (side(i, 8.0, 50.0) { it.max() }?.let { it - y[i] > maxOf(0.01, 0.08 * it) } ?: false) }.map { wl[it] }
        val maxima = idx.filter { i -> (i - 8..i + 8).all { it == i || y[it] <= y[i] } &&
            (side(i, 8.0, 50.0) { it.min() }?.let { y[i] - it > maxOf(0.01, 0.08 * y[i]) } ?: false) }.map { wl[it] }
        val vis = wl.indices.filter { wl[it] in 420.0..700.0 }.map { y[it] }
        val mean = vis.average()
        val flat = vis.isNotEmpty() && (vis.max() - vis.min()) < 0.12 * maxOf(mean, 0.05)
        val nir = if (Spectra.covers(wl, 880.0, 920.0)) Spectra.bandMean(wl, y, 880.0, 920.0) / vis.max().coerceAtLeast(1e-6) else null
        return Features(infl.sorted(), minima, maxima, mean, nir, flat)
    }

    /** [evidence]: weight of the features that could be tested (ties go to the better-evidenced pigment). */
    data class Match(val pigment: Pigment, val score: Double, val evidence: Double = 0.0)

    /**
     * Score = share of the pigment's features found, × position precision, × how much of what was observed
     * (inflections and minima) the pigment accounts for — a candidate must explain the spectrum, not just fit one point.
     */
    fun match(wl: DoubleArray, f: Features): List<Match> = LIBRARY.mapNotNull { p ->
        var got = 0.0; var total = 0.0; var errSum = 0.0; var errN = 0
        fun near(list: List<Double>, x: F) = list.minByOrNull { abs(it - x.nm) }?.takeIf { abs(it - x.nm) <= x.tol }
        for (x in p.features) {
            val testable = when (x.kind) {
                Kind.LOW_NIR -> f.nirRatio != null
                Kind.INFLECTION, Kind.NO_INFLECTION, Kind.MINIMUM, Kind.MAXIMUM -> x.nm in wl.first() + 10..wl.last() - 10
                else -> true
            }
            if (!testable) continue
            total += x.weight
            val hit: Double? = when (x.kind) {
                Kind.INFLECTION -> near(f.inflections, x)
                Kind.MINIMUM -> near(f.minima, x)
                Kind.MAXIMUM -> near(f.maxima, x)
                else -> null
            }
            if (hit != null) { errSum += abs(hit - x.nm) / x.tol; errN++ }
            val ok = when (x.kind) {
                Kind.INFLECTION, Kind.MINIMUM, Kind.MAXIMUM -> hit != null
                Kind.NO_INFLECTION -> f.inflections.none { abs(it - x.nm) <= x.tol }
                Kind.LOW_NIR -> (f.nirRatio ?: 1.0) < 0.6
                Kind.DARK -> f.meanVis < 0.12
                Kind.BRIGHT_FLAT -> f.flat && f.meanVis > 0.6
                Kind.DARK_FLAT -> f.meanVis < 0.10 && f.inflections.isEmpty() && f.minima.isEmpty() && f.maxima.isEmpty()
            }
            if (ok) got += x.weight
        }
        if (total <= 0) return@mapNotNull null
        val precision = if (errN > 0) 1 - 0.5 * errSum / errN else 1.0
        val observed = f.inflections.map { Kind.INFLECTION to it } + f.minima.map { Kind.MINIMUM to it }
        val explained = if (observed.isEmpty()) 1.0 else observed.count { (k, nm) ->
            p.features.any { it.kind == k && abs(it.nm - nm) <= it.tol * 1.5 }
        }.toDouble() / observed.size
        Match(p, got / total * (0.8 + 0.2 * precision) * (0.6 + 0.4 * explained), total)
    }.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.evidence })
}
