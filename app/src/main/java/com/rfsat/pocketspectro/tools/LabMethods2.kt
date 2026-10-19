package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

// ================================= Growth curve (OD600) ========================================

/** Microbial growth from optical density at 600 nm: maximum specific growth rate, doubling time and lag. */
object GrowthCurve {
    data class Point(val tSec: Double, val od: Double)
    data class Fit(val muPerHour: Double, val doublingMin: Double, val lagMin: Double, val maxOd: Double, val r2: Double, val fromSec: Double, val toSec: Double)

    fun od600(wl: DoubleArray, abs: DoubleArray, pathMm: Double = 10.0) =
        if (Spectra.covers(wl, 595.0, 605.0)) Spectra.bandMean(wl, abs, 595.0, 605.0) * 10.0 / pathMm else null

    /** Steepest straight stretch of ln(OD) (sliding window), with the lag where its tangent meets the starting level. */
    fun fit(points: List<Point>): Fit? {
        val p = points.filter { it.od > 0.005 && it.od.isFinite() }
        if (p.size < 8) return null
        val w = maxOf(5, p.size / 6)
        var best: Spectra.LineFit? = null; var from = 0
        for (s in 0..p.size - w) {
            val f = Spectra.lineFit(DoubleArray(w) { p[s + it].tSec }, DoubleArray(w) { ln(p[s + it].od) }) ?: continue
            if (f.r2 >= 0.97 && (best == null || f.slope > best.slope)) { best = f; from = s }
        }
        val b = best ?: return null
        if (b.slope <= 0) return null
        val od0 = p.take(3).map { ln(it.od) }.average()
        val mu = b.slope * 3600
        val lag = ((od0 - b.intercept) / b.slope).coerceAtLeast(0.0) - p.first().tSec
        return Fit(mu, ln(2.0) / mu * 60, lag / 60, p.maxOf { it.od }, b.r2, p[from].tSec, p[from + w - 1].tSec)
    }
}

// ================================= Nucleic acids and protein (UV) ==============================

object NucleicAcids {
    enum class Kind(val factor: Double) { DS_DNA(50.0), RNA(40.0), SS_DNA(33.0), PROTEIN(1000.0) }
    data class Result(val a230: Double, val a260: Double, val a280: Double, val a320: Double,
                      val r260280: Double, val r260230: Double, /** µg/mL (protein: µg/mL for 1 A = 1 mg/mL). */ val concentration: Double)

    /** A260 / A280 / A230, corrected with the A320 background; concentration with the standard factors (10 mm). */
    fun analyse(wl: DoubleArray, abs: DoubleArray, kind: Kind, pathMm: Double = 10.0, dilution: Double = 1.0): Result? {
        if (!Spectra.covers(wl, 228.0, 322.0)) return null
        fun a(nm: Double) = Spectra.bandMean(wl, abs, nm - 1, nm + 1) * 10.0 / pathMm
        val bg = a(320.0)
        val a230 = a(230.0) - bg; val a260 = a(260.0) - bg; val a280 = a(280.0) - bg
        val conc = (if (kind == Kind.PROTEIN) a280 else a260) * kind.factor * dilution
        return Result(a230, a260, a280, bg, a260 / a280, a260 / a230, conc)
    }
}

// ================================= Test-kit reader =============================================

object TestKits {
    /** Colour reactions of common water test kits: wavelength, default response (absorbance per unit, 10 mm), unit. */
    enum class Method(val nm: Double, val perUnit: Double, val unit: String, val rangeMax: Double, val altNm: Double? = null, val altPerUnit: Double? = null) {
        CHLORINE_DPD(515.0, 0.30, "mg/L Cl₂", 5.0),
        PHOSPHATE(880.0, 0.60, "mg/L PO₄-P", 1.5, 710.0, 0.36),
        AMMONIA(655.0, 1.00, "mg/L NH₃-N", 1.0),
        NITRITE(540.0, 3.00, "mg/L NO₂-N", 0.3),
        IRON(510.0, 0.20, "mg/L Fe", 5.0),
        CUSTOM(550.0, 1.0, "", 10.0),
    }

    /** Wavelength used on this unit (the alternative band when the main one is not covered). */
    fun wavelength(m: Method, wl: DoubleArray) = if (Spectra.covers(wl, m.nm - 3, m.nm + 3) || m.altNm == null) m.nm else m.altNm

    fun defaultResponse(m: Method, wl: DoubleArray) = if (wavelength(m, wl) == m.nm) m.perUnit else m.altPerUnit ?: m.perUnit

    fun absorbance(wl: DoubleArray, abs: DoubleArray, nm: Double, pathMm: Double = 10.0) =
        if (Spectra.covers(wl, nm - 3, nm + 3)) Spectra.bandMean(wl, abs, nm - 3, nm + 3) * 10.0 / pathMm else null
}

// ================================= pH from an indicator dye ====================================

object Indicator {
    /** Acid- and base-form absorption maxima and pKa (25 °C, low ionic strength). */
    enum class Dye(val pKa: Double, val acidNm: Double, val baseNm: Double, val range: ClosedFloatingPointRange<Double>) {
        BROMOCRESOL_GREEN(4.66, 444.0, 617.0, 3.8..5.4),
        BROMOTHYMOL_BLUE(7.10, 432.0, 616.0, 6.0..7.6),
        PHENOL_RED(7.90, 433.0, 558.0, 6.8..8.4),
        M_CRESOL_PURPLE(8.00, 434.0, 578.0, 7.4..9.0),
        THYMOL_BLUE(8.90, 435.0, 596.0, 8.0..9.6),
    }

    /** Ratios of the pure forms: e1 = εA(λB)/εA(λA), e2 = εB(λB)/εA(λA), e3 = εB(λA)/εA(λA). */
    data class Ends(val e1: Double, val e2: Double, val e3: Double)

    fun ends(wl: DoubleArray, acid: DoubleArray, base: DoubleArray, d: Dye): Ends {
        fun at(a: DoubleArray, nm: Double) = Spectra.bandMean(wl, a, nm - 2, nm + 2)
        val aA = at(acid, d.acidNm)
        return Ends(at(acid, d.baseNm) / aA, at(base, d.baseNm) / aA, at(base, d.acidNm) / aA)
    }

    /**
     * Generic end-member ratios (acid band 40 nm, base band 45 nm wide, base twice as strong), used until the
     * user stores the pure acid and base forms of the actual dye.
     */
    fun defaultEnds(d: Dye): Ends {
        fun g(x: Double, c: Double, w: Double) = exp(-((x - c) / w).pow(2))
        return Ends(g(d.baseNm, d.acidNm, 40.0), 2.0, 2.0 * g(d.acidNm, d.baseNm, 45.0))
    }

    data class Result(val pH: Double, val ratio: Double, val inRange: Boolean)

    /** pH = pKa + log10((R − e1) / (e2 − R·e3)) with R = A(λB)/A(λA) (Clayton & Byrne 1993 form). */
    fun pH(wl: DoubleArray, abs: DoubleArray, d: Dye, e: Ends): Result? {
        if (!Spectra.covers(wl, d.acidNm - 3, d.baseNm + 3)) return null
        val aA = Spectra.bandMean(wl, abs, d.acidNm - 2, d.acidNm + 2); val aB = Spectra.bandMean(wl, abs, d.baseNm - 2, d.baseNm + 2)
        if (aA <= 0) return null
        val r = aB / aA
        val num = r - e.e1; val den = e.e2 - r * e.e3
        if (num <= 0 || den <= 0) return null
        val ph = d.pKa + log10(num / den)
        return Result(ph, r, ph in d.range)
    }
}

// ================================= Fluorimeter =================================================

object Fluorimetry {
    /** Excitation LED and emission window of common fluorescence measurements. */
    enum class Preset(val ledNm: Double, val emLo: Double, val emHi: Double, val unit: String) {
        CHLOROPHYLL_A(450.0, 670.0, 700.0, "µg/L"),
        PHYCOCYANIN(590.0, 640.0, 665.0, "µg/L"),
        FLUORESCEIN(470.0, 505.0, 540.0, "µg/L"),
        QUININE(365.0, 425.0, 475.0, "mg/L"),
        OIL(365.0, 400.0, 500.0, ""),
        SECURITY_UV(365.0, 400.0, 700.0, ""),
    }

    data class Result(val signal: Double, val peakNm: Double?, val leakage: Double)

    /** Emission above the blank (counts per second) in the window; [excess] = sample − blank counts. */
    fun measure(wl: DoubleArray, excess: DoubleArray, integrationUs: Long, p: Preset): Result? {
        if (!Spectra.covers(wl, p.emLo, p.emHi)) return null
        val perS = 1e6 / integrationUs
        var s = 0.0
        for (i in wl.indices) if (wl[i] in p.emLo..p.emHi) s += excess[i] * perS
        val peak = Spectra.peak(wl, Spectra.smooth(excess, 2), p.emLo, p.emHi)
        val leak = if (Spectra.covers(wl, p.ledNm - 5, p.ledNm + 5)) Spectra.bandMean(wl, excess, p.ledNm - 5, p.ledNm + 5) * perS else 0.0
        return Result(s, peak?.nm, leak)
    }
}

// ================================= Leaf chlorophyll meter ======================================

/**
 * Leaf-clip chlorophyll index, the SPAD principle: transmittance through the leaf in the red
 * chlorophyll band (650 nm) against the near infrared (940 nm, or 780 nm on visible units).
 * Default scale gives SPAD-like values; one reading next to a SPAD meter calibrates it.
 */
object LeafClip {
    const val DEFAULT_K = 50.0

    data class Result(val logRatio: Double, val spadLike: Double, val nirNm: Double, /** Markwell 1995 µg/cm² (approximate). */ val chlorophyll: Double)

    fun measure(wl: DoubleArray, t: DoubleArray, k: Double = DEFAULT_K): Result? {
        val nir = when { Spectra.covers(wl, 935.0, 945.0) -> 940.0; Spectra.covers(wl, 775.0, 785.0) -> 780.0; else -> return null }
        if (!Spectra.covers(wl, 645.0, 655.0)) return null
        val tr = Spectra.bandMean(wl, t, 646.0, 654.0); val tn = Spectra.bandMean(wl, t, nir - 4, nir + 4)
        if (tr <= 0 || tn <= 0) return null
        val lr = log10(tn / tr)
        val spad = k * lr
        val chl = 10.0.pow(spad.coerceAtLeast(0.0).pow(0.265)) * 893.5 / 1e4
        return Result(lr, spad, nir, chl)
    }
}

// ================================= Greenhouse light ============================================

object PlantLight {
    data class Result(
        val ppfd: Double?, val uvPercent: Double, val bluePercent: Double, val greenPercent: Double, val redPercent: Double, val farRedPercent: Double,
        /** Red : far-red photon ratio (655–665 / 725–735 nm). */ val rfr: Double?,
    )

    /** Photon shares per band from a response-corrected spectrum; PPFD when [ppfd] is known. */
    fun analyse(wl: DoubleArray, spd: DoubleArray, ppfd: Double?): Result? {
        if (!Spectra.covers(wl, 400.0, 700.0)) return null
        fun photons(lo: Double, hi: Double): Double {
            if (!Spectra.covers(wl, lo, hi)) return 0.0
            var s = 0.0; var l = lo
            while (l <= hi) { s += Spectra.at(wl, spd, l).coerceAtLeast(0.0) * l; l += 1.0 }
            return s
        }
        val par = photons(400.0, 700.0).takeIf { it > 0 } ?: return null
        val rfr = if (Spectra.covers(wl, 655.0, 735.0)) photons(655.0, 665.0) / photons(725.0, 735.0).coerceAtLeast(1e-12) else null
        return Result(ppfd, 100 * photons(380.0, 400.0) / par, 100 * photons(400.0, 500.0) / par, 100 * photons(500.0, 600.0) / par,
            100 * photons(600.0, 700.0) / par, 100 * photons(700.0, 750.0) / par, rfr)
    }
}

// ================================= Ozone and NO2 columns =======================================

object GasColumns {
    const val O3_A = 310.0
    const val O3_B = 330.0

    fun ozoneSupported(wl: DoubleArray) = Spectra.covers(wl, O3_A - 2, O3_B + 2)

    /** ln(V(310)/V(330)) from a direct-sun spectrum (dark-corrected counts). */
    fun ozoneRatio(wl: DoubleArray, counts: DoubleArray): Double? {
        val a = Spectra.bandMean(wl, counts, O3_A - 1.5, O3_A + 1.5); val b = Spectra.bandMean(wl, counts, O3_B - 1.5, O3_B + 1.5)
        return if (a > 0 && b > 0) ln(a / b) else null
    }

    private val dAlpha get() = Solar.ozoneTau(O3_A, 1.0) - Solar.ozoneTau(O3_B, 1.0)
    private fun dRay(hPa: Double) = Solar.rayleighTau(O3_A, hPa) - Solar.rayleighTau(O3_B, hPa)

    /** Total ozone (DU): Ω = (L0 − L − m·Δτ_R) / (m·Δα), L = ln ratio, L0 its extraterrestrial value. */
    fun ozone(l: Double, l0: Double, m: Double, hPa: Double = 1013.25) = (l0 - l - m * dRay(hPa)) / (m * dAlpha)

    /** Extraterrestrial ratio from one measurement with a known ozone column (e.g. from a satellite). */
    fun l0FromKnown(l: Double, m: Double, du: Double, hPa: Double = 1013.25) = l + m * dRay(hPa) + m * dAlpha * du

    /** Langley fit of L + m·Δτ_R against m: intercept L0 (and the slope gives the ozone). */
    fun l0Langley(points: List<Pair<Double, Double>>, hPa: Double = 1013.25): Double? {
        if (points.size < 8) return null
        return Spectra.lineFit(points.map { it.first }.toDoubleArray(), points.map { it.second + it.first * dRay(hPa) }.toDoubleArray())?.intercept
    }

    // ---- NO2 by DOAS ----------------------------------------------------------------------------

    const val NO2_LO = 425.0
    const val NO2_HI = 490.0
    const val MOLEC_PER_DU = 2.6867e16

    /** Differential optical depth fit: ln(I0/I) high-pass filtered (cubic) against the cross-section; slant column, molecules/cm². */
    fun no2Slant(wl: DoubleArray, i: DoubleArray, i0: DoubleArray, sigma: (Double) -> Double): Pair<Double, Double>? {
        val idx = wl.indices.filter { wl[it] in NO2_LO..NO2_HI && i[it] > 0 && i0[it] > 0 }
        if (idx.size < 40) return null
        val x0 = (NO2_LO + NO2_HI) / 2; val xr = (NO2_HI - NO2_LO) / 2
        val xs = DoubleArray(idx.size) { (wl[idx[it]] - x0) / xr }
        val od = DoubleArray(idx.size) { ln(i0[idx[it]] / i[idx[it]]) }
        val sg = DoubleArray(idx.size) { sigma(wl[idx[it]]) }
        val co = Spectra.polyFit(xs, od, 3) ?: return null; val cs = Spectra.polyFit(xs, sg, 3) ?: return null
        val odh = DoubleArray(idx.size) { od[it] - Spectra.polyVal(co, xs[it]) }
        val sgh = DoubleArray(idx.size) { sg[it] - Spectra.polyVal(cs, xs[it]) }
        val sxx = sgh.sumOf { it * it }
        if (sxx <= 0) return null
        val scd = (0 until idx.size).sumOf { odh[it] * sgh[it] } / sxx
        val res = sqrt((0 until idx.size).sumOf { (odh[it] - scd * sgh[it]).pow(2) } / (idx.size - 1))
        return scd to res / sqrt(sxx)
    }

    /**
     * Synthetic NO2-like cross-section (cm²/molecule) used only by the simulator and when no measured
     * cross-section file has been imported: banded structure of the right size between 400 and 500 nm.
     */
    fun syntheticNo2(l: Double): Double {
        var s = 3.0e-19 * exp(-((l - 440.0) / 60.0).pow(2))
        for (c in doubleArrayOf(413.0, 427.0, 433.0, 439.0, 448.0, 463.0, 477.0, 491.0)) s += 1.6e-19 * exp(-((l - c) / 2.2).pow(2))
        return s
    }

    /** Cross-section from an imported two-column text file (nm, cm²/molecule). */
    fun parseCrossSection(text: String): Pair<DoubleArray, DoubleArray>? {
        val pts = text.lineSequence().mapNotNull { l ->
            val p = l.trim().split(Regex("[,;\\s]+")); val a = p.getOrNull(0)?.toDoubleOrNull(); val b = p.getOrNull(1)?.toDoubleOrNull()
            if (a != null && b != null) a to b else null
        }.sortedBy { it.first }.toList()
        if (pts.size < 20) return null
        return pts.map { it.first }.toDoubleArray() to pts.map { it.second }.toDoubleArray()
    }
}

// ================================= Display check ==============================================

object DisplayCheck {
    enum class Patch { WHITE, RED, GREEN, BLUE }
    val SRGB = listOf(0.640 to 0.330, 0.300 to 0.600, 0.150 to 0.060)
    val DCI_P3 = listOf(0.680 to 0.320, 0.265 to 0.690, 0.150 to 0.060)
    val REC2020 = listOf(0.708 to 0.292, 0.170 to 0.797, 0.131 to 0.046)

    fun xy(wl: DoubleArray, spd: DoubleArray): Pair<Double, Double>? = Colorimetry.emissionXYZ(wl, spd).chromaticity.takeIf { it.first.isFinite() }

    private fun uvp(p: Pair<Double, Double>): Pair<Double, Double> { val (x, y) = p; val d = -2 * x + 12 * y + 3; return 4 * x / d to 9 * y / d }

    private fun area(poly: List<Pair<Double, Double>>) = abs(poly.indices.sumOf { i -> val a = poly[i]; val b = poly[(i + 1) % poly.size]; a.first * b.second - b.first * a.second }) / 2

    /** Sutherland–Hodgman clipping of a polygon by a convex (counter-clockwise) polygon. */
    private fun clip(subject: List<Pair<Double, Double>>, clipper: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var out = subject
        val c = if (signedArea(clipper) < 0) clipper.reversed() else clipper
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % c.size]
            fun inside(p: Pair<Double, Double>) = (b.first - a.first) * (p.second - a.second) - (b.second - a.second) * (p.first - a.first) >= 0
            fun cross(p: Pair<Double, Double>, q: Pair<Double, Double>): Pair<Double, Double> {
                val a1 = b.second - a.second; val b1 = a.first - b.first; val c1 = a1 * a.first + b1 * a.second
                val a2 = q.second - p.second; val b2 = p.first - q.first; val c2 = a2 * p.first + b2 * p.second
                val det = a1 * b2 - a2 * b1
                return ((b2 * c1 - b1 * c2) / det) to ((a1 * c2 - a2 * c1) / det)
            }
            val input = out; out = ArrayList()
            if (input.isEmpty()) break
            var s = input.last()
            for (e in input) {
                if (inside(e)) { if (!inside(s)) out += cross(s, e); out += e } else if (inside(s)) out += cross(s, e)
                s = e
            }
        }
        return out
    }
    private fun signedArea(p: List<Pair<Double, Double>>) = p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.first * b.second - b.first * a.second } / 2

    data class Gamut(val areaVsSrgb: Double, val coverSrgb: Double, val coverP3: Double, val cover2020: Double)

    /** Gamut size and coverage in CIE 1976 u'v' (percent). */
    fun gamut(primaries: List<Pair<Double, Double>>): Gamut {
        val t = primaries.map { uvp(it) }
        fun cov(ref: List<Pair<Double, Double>>) = 100 * area(clip(t, ref.map { uvp(it) })) / area(ref.map { uvp(it) })
        return Gamut(100 * area(t) / area(SRGB.map { uvp(it) }), cov(SRGB), cov(DCI_P3), cov(REC2020))
    }
}

// ================================= Exposure and physiology (research, non-medical) ============

/** Personal light exposure: melanopic EDI against the recommendations of Brown et al. (2022). */
object LightExposure {
    const val DAY_MIN_MEL_EDI = 250.0
    const val EVENING_MAX_MEL_EDI = 10.0
    const val NIGHT_MAX_MEL_EDI = 1.0

    data class Sample(val tMs: Long, val lux: Double, val melEdi: Double)

    data class Summary(val hoursAbove250: Double, val eveningMax: Double?, val nightMax: Double?, val melLuxHours: Double, val samples: Int)

    /** [hourOf] gives the local clock hour (0–24) of a time; day = wake…bed−3 h, evening = last 3 h before bed, night = bed…wake. */
    fun summary(s: List<Sample>, wake: Double, bed: Double, hourOf: (Long) -> Double): Summary {
        var above = 0.0; var dose = 0.0; var eve: Double? = null; var night: Double? = null
        for (i in s.indices) {
            val dtH = if (i + 1 < s.size) ((s[i + 1].tMs - s[i].tMs) / 3_600_000.0).coerceIn(0.0, 0.25) else 0.0
            val h = hourOf(s[i].tMs)
            dose += s[i].melEdi * dtH
            val asleep = if (bed > wake) h >= bed || h < wake else h in bed..wake
            val evening = !asleep && ((bed - h + 24) % 24) <= 3.0
            when {
                asleep -> night = maxOf(night ?: 0.0, s[i].melEdi)
                evening -> eve = maxOf(eve ?: 0.0, s[i].melEdi)
                s[i].melEdi >= DAY_MIN_MEL_EDI -> above += dtH
            }
        }
        return Summary(above, eve, night, dose, s.size)
    }
}

/** Personal erythemal UV dose: 1 UV index unit = 0.025 W/m² erythemal; 1 SED = 100 J/m². */
object UvDose {
    /** Typical minimal erythema dose by Fitzpatrick skin type (lower end of published ranges), SED. */
    val MED = doubleArrayOf(1.5, 2.5, 3.0, 4.5, 6.0, 9.0)
    fun sedPerHour(uvi: Double) = uvi * 0.025 * 3600 / 100
}

/** Skin optical indices from diffuse reflectance (Diffey et al. 1984) and a relative carotenoid index. */
object SkinOptics {
    data class Result(val erythema: Double, val melanin: Double, val carotenoid: Double?)

    fun measure(wl: DoubleArray, r: DoubleArray): Result? {
        if (!Spectra.covers(wl, 555.0, 660.0)) return null
        fun rr(nm: Double) = Spectra.bandMean(wl, r, nm - 5, nm + 5)
        val g = rr(560.0); val red = rr(650.0)
        if (g <= 0 || red <= 0) return null
        val ei = 100 * (log10(1 / g) - log10(1 / red))
        val mi = 100 * log10(1 / red)
        val car = if (Spectra.covers(wl, 435.0, 525.0)) {
            val a = { nm: Double -> log10(1 / rr(nm).coerceAtLeast(1e-4)) }
            1000 * (a(480.0) - (a(440.0) + a(520.0)) / 2)
        } else null
        return Result(ei, mi, car)
    }
}

/** Pulse from a fingertip photoplethysmogram (transmitted light against time): rate, perfusion index, RMSSD. */
object Pulse {
    data class Result(val bpm: Double?, val quality: Double, val perfusionIndex: Double?, val rmssdMs: Double?, val beats: Int)

    /** [t] in seconds, [v] the band signal; uses the last [windowSec] seconds. */
    fun analyse(t: DoubleArray, v: DoubleArray, windowSec: Double = 12.0): Result {
        if (t.size < 30) return Result(null, 0.0, null, null, 0)
        val end = t.last(); val idx = t.indices.filter { t[it] >= end - windowSec }
        if (idx.size < 30) return Result(null, 0.0, null, null, 0)
        val dt = (t[idx.last()] - t[idx.first()]) / (idx.size - 1)
        if (dt <= 0 || dt > 0.12) return Result(null, 0.0, null, null, 0)
        // Resample uniformly, remove the slow baseline (1.5 s moving average), light smoothing.
        val n = idx.size; val tt = DoubleArray(n) { t[idx.first()] + it * dt }
        val tv = idx.map { t[it] }.toDoubleArray(); val vv = idx.map { v[it] }.toDoubleArray()
        val u = DoubleArray(n) { Spectra.at(tv, vv, tt[it]) }
        val base = Spectra.smooth(u, (0.75 / dt).toInt().coerceAtLeast(1))
        val ac = Spectra.smooth(DoubleArray(n) { -(u[it] - base[it]) }, (0.05 / dt).toInt())  // more blood → less light: invert
        val dc = base.average()
        val sd = sqrt(ac.sumOf { it * it } / n)
        if (sd <= 0 || dc <= 0) return Result(null, 0.0, null, null, 0)
        // Autocorrelation peak between 0.33 s (180 bpm) and 1.5 s (40 bpm).
        var bestLag = -1; var best = 0.0
        val lo = (0.33 / dt).toInt(); val hi = minOf((1.5 / dt).toInt(), n / 2)
        val r = DoubleArray(hi + 2)
        for (lag in lo..hi + 1) { var s = 0.0; for (i in 0 until n - lag) s += ac[i] * ac[i + lag]; r[lag] = s / ((n - lag) * sd * sd) }
        for (lag in lo + 1..hi) if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > best) { best = r[lag]; bestLag = lag }
        if (bestLag < 0 || best < 0.3) return Result(null, best.coerceAtLeast(0.0), null, null, 0)
        val y0 = r[bestLag - 1]; val y1 = r[bestLag]; val y2 = r[bestLag + 1]
        val off = (y0 - y2) / (2 * (y0 - 2 * y1 + y2)).let { if (it == 0.0) 1e-12 else it }
        val period = (bestLag + off.coerceIn(-0.5, 0.5)) * dt
        // Beats: maxima at least 0.6 period apart; RMSSD of successive intervals.
        val beats = ArrayList<Double>()
        val minGap = (0.6 * period / dt).toInt().coerceAtLeast(1)
        var last = -minGap
        for (i in 1 until n - 1) if (ac[i] > ac[i - 1] && ac[i] >= ac[i + 1] && ac[i] > 0.3 * sd && i - last >= minGap) { beats += tt[i]; last = i }
        val ibi = beats.zipWithNext { a, b -> b - a }.filter { abs(it - period) < 0.35 * period }
        val rmssd = if (ibi.size >= 4) sqrt(ibi.zipWithNext { a, b -> (b - a).pow(2) }.average()) * 1000 else null
        val ptp = ac.max() - ac.min()
        return Result(60 / period, best, 100 * ptp / dc, rmssd, beats.size)
    }
}
