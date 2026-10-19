package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Light-source colour quality on the standard tabulated data (bundled in resources/data):
 * CCT and Duv (Planckian locus search, CIE 1931 2°), CIE 13.3 colour rendering index (Ra, R1–R14),
 * and ANSI/IES TM-30-18 fidelity Rf and gamut Rg (CIE 224:2017 99 samples, CIE 1964 10°, CAM02-UCS).
 * Verified against the colour-science library (FL2: Ra 64.2, Rf 70.1, Rg 86.4).
 */
object ColourQuality {

    /** 380–780 nm every 5 nm. */
    val NM = DoubleArray(81) { 380.0 + 5 * it }

    private fun table(name: String): List<List<String>> {
        val s = ColourQuality::class.java.getResourceAsStream("/data/$name") ?: error("missing $name")
        return s.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(',') }
    }

    private class Cmf(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray)
    private fun cmf(name: String) = table(name).let { t -> Cmf(DoubleArray(81) { t[it][1].toDouble() }, DoubleArray(81) { t[it][2].toDouble() }, DoubleArray(81) { t[it][3].toDouble() }) }
    private val cmf2 by lazy { cmf("cmf_1931_2.csv") }
    private val cmf10 by lazy { cmf("cmf_1964_10.csv") }
    private val s012 by lazy { table("daylight_s012.csv").let { t -> Array(3) { k -> DoubleArray(81) { t[it][k + 1].toDouble() } } } }
    private val tcs by lazy { table("cri_tcs.csv").map { r -> DoubleArray(81) { r[it + 1].toDouble() } } }
    private val ces by lazy { table("tm30_ces.csv").map { r -> DoubleArray(81) { r[it + 1].toDouble() } } }

    /** Resamples a spectrum onto 380–780 nm / 5 nm; zero outside the measured range (as CIE 224 recommends). */
    fun grid(wl: DoubleArray, spd: DoubleArray) = DoubleArray(81) { i ->
        val l = NM[i]; if (l < wl.first() || l > wl.last()) 0.0 else Spectra.at(wl, spd, l).coerceAtLeast(0.0)
    }

    private fun xyz(s: DoubleArray, c: Cmf, r: DoubleArray? = null): DoubleArray {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (i in 0 until 81) { val v = s[i] * (r?.get(i) ?: 1.0); x += v * c.x[i]; y += v * c.y[i]; z += v * c.z[i] }
        return doubleArrayOf(x, y, z)
    }

    private fun uv(x: DoubleArray): Pair<Double, Double> { val d = x[0] + 15 * x[1] + 3 * x[2]; return 4 * x[0] / d to 6 * x[1] / d }

    fun planck(t: Double) = DoubleArray(81) { val l = NM[it] * 1e-9; 3.741771852e-16 / (l.pow(5) * (exp(1.438776877e-2 / (l * t)) - 1)) }

    private fun planckUv(t: Double) = uv(xyz(planck(t), cmf2))

    data class Cct(val kelvin: Double, val duv: Double)

    /** CCT and Duv: nearest point of the Planckian locus in CIE 1960 uv (exact search; equivalent to Ohno 2013). */
    fun cct(s: DoubleArray): Cct {
        val (u, v) = uv(xyz(s, cmf2))
        fun d(t: Double): Double { val (pu, pv) = planckUv(t); return (u - pu).pow(2) + (v - pv).pow(2) }
        // Search in mired (1/T) between 1000 and 25 000 K.
        var best = 1e6 / 1000.0; var bd = Double.MAX_VALUE
        var m = 40.0
        while (m <= 1000.0) { val dd = d(1e6 / m); if (dd < bd) { bd = dd; best = m }; m += 5.0 }
        var a = best - 5; var b = best + 5
        val g = (sqrt(5.0) - 1) / 2
        repeat(60) { val c1 = b - g * (b - a); val c2 = a + g * (b - a); if (d(1e6 / c1) < d(1e6 / c2)) b = c2 else a = c1 }
        val t = 1e6 / ((a + b) / 2)
        val (pu, pv) = planckUv(t)
        // Duv is positive above the Planckian locus (greenish), negative below (pinkish).
        val sgn = if (v >= pv) 1.0 else -1.0
        return Cct(t, sgn * sqrt((u - pu).pow(2) + (v - pv).pow(2)))
    }

    /** CIE D-series illuminant at [t] K (4000–25 000 K). */
    fun daylight(t: Double): DoubleArray {
        val x = if (t <= 7000) -4.6070e9 / t.pow(3) + 2.9678e6 / t.pow(2) + 0.09911e3 / t + 0.244063
        else -2.0064e9 / t.pow(3) + 1.9018e6 / t.pow(2) + 0.24748e3 / t + 0.237040
        val y = -3.000 * x * x + 2.870 * x - 0.275
        val den = 0.0241 + 0.2562 * x - 0.7341 * y
        val m1 = (-1.3515 - 1.7703 * x + 5.9114 * y) / den
        val m2 = (0.0300 - 31.4424 * x + 30.0717 * y) / den
        return DoubleArray(81) { s012[0][it] + m1 * s012[1][it] + m2 * s012[2][it] }
    }

    // ---- CIE 13.3 colour rendering index ---------------------------------------------------------

    data class Cri(val ra: Double, val r: DoubleArray, val cct: Cct) {
        val r9 get() = r[8]
    }

    fun cri(s: DoubleArray): Cri {
        val c = cct(s)
        val ref = if (c.kelvin < 5000) planck(c.kelvin) else daylight(c.kelvin)
        fun cd(u: Double, v: Double) = (4 - u - 10 * v) / v to (1.708 * v + 0.404 - 1.481 * u) / v
        val wk = xyz(s, cmf2); val wr = xyz(ref, cmf2)
        val (uk, vk) = uv(wk); val (ur, vr) = uv(wr)
        val (ck, dk) = cd(uk, vk); val (cr, dr) = cd(ur, vr)
        val r = DoubleArray(tcs.size) { i ->
            val xk = xyz(s, cmf2, tcs[i]).map { it * 100 / wk[1] }; val xr = xyz(ref, cmf2, tcs[i]).map { it * 100 / wr[1] }
            val (uki, vki) = uv(xk.toDoubleArray()); val (uri, vri) = uv(xr.toDoubleArray())
            val (cki, dki) = cd(uki, vki)
            val den = 16.518 + 1.481 * (cr / ck) * cki - (dr / dk) * dki
            val ua = (10.872 + 0.404 * (cr / ck) * cki - 4 * (dr / dk) * dki) / den
            val va = 5.520 / den
            val wkS = 25 * cbrt(xk[1]) - 17; val wrS = 25 * cbrt(xr[1]) - 17
            val du = 13 * wkS * (ua - ur) - 13 * wrS * (uri - ur)
            val dv = 13 * wkS * (va - vr) - 13 * wrS * (vri - vr)
            100 - 4.6 * sqrt(du * du + dv * dv + (wkS - wrS).pow(2))
        }
        return Cri((0 until 8).sumOf { r[it] } / 8, r, c)
    }

    // ---- ANSI/IES TM-30-18 -------------------------------------------------------------------------

    data class Tm30(val rf: Double, val rg: Double, val cct: Cct, val rcs: DoubleArray, val rfBins: DoubleArray)

    private val mCat = arrayOf(doubleArrayOf(0.7328, 0.4296, -0.1624), doubleArrayOf(-0.7036, 1.6975, 0.0061), doubleArrayOf(0.0030, 0.0136, 0.9834))
    private val mHpe = arrayOf(doubleArrayOf(0.38971, 0.68898, -0.07868), doubleArrayOf(-0.22981, 1.18340, 0.04641), doubleArrayOf(0.0, 0.0, 1.0))
    private val mCatInv by lazy { invert(mCat) }
    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { r -> m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2] }
    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]; val d = m[1][0]; val e = m[1][1]; val f = m[1][2]; val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det))
    }

    /** CIECAM02 → CAM02-UCS (J', a', b') with L_A = 100 cd/m², Y_b = 20, average surround, full adaptation. */
    private fun jab(x: DoubleArray, w: DoubleArray): DoubleArray {
        val la = 100.0; val yb = 20.0; val c = 0.69; val nc = 1.0
        val k = 1 / (5 * la + 1)
        val fl = 0.2 * k.pow(4) * 5 * la + 0.1 * (1 - k.pow(4)).pow(2) * cbrt(5 * la)
        val n = yb / w[1]; val nbb = 0.725 * (1 / n).pow(0.2); val z = 1.48 + sqrt(n)
        fun adapt(v: DoubleArray): DoubleArray {
            val rgb = mul(mCat, v); val rgbW = mul(mCat, w)
            val rc = DoubleArray(3) { w[1] / rgbW[it] * rgb[it] }
            val p = mul(mHpe, mul(mCatInv, rc))
            return DoubleArray(3) { val q = (fl * abs(p[it]) / 100).pow(0.42); sign(p[it]) * 400 * q / (q + 27.13) + 0.1 }
        }
        val ra = adapt(x); val rw = adapt(w)
        val a = ra[0] - 12 * ra[1] / 11 + ra[2] / 11; val b = (ra[0] + ra[1] - 2 * ra[2]) / 9
        val h = (Math.toDegrees(atan2(b, a)) + 360) % 360
        val et = 0.25 * (cos(Math.toRadians(h) + 2) + 3.8)
        val aa = (2 * ra[0] + ra[1] + ra[2] / 20 - 0.305) * nbb; val aw = (2 * rw[0] + rw[1] + rw[2] / 20 - 0.305) * nbb
        val j = 100 * (aa / aw).pow(c * z)
        val t = (50000.0 / 13 * nc * nbb * et * sqrt(a * a + b * b)) / (ra[0] + ra[1] + 21.0 / 20 * ra[2])
        val cc = t.pow(0.9) * sqrt(j / 100) * (1.64 - 0.29.pow(n)).pow(0.73)
        val m = cc * fl.pow(0.25)
        val jp = 1.7 * j / (1 + 0.007 * j); val mp = ln(1 + 0.0228 * m) / 0.0228
        return doubleArrayOf(jp, mp * cos(Math.toRadians(h)), mp * sin(Math.toRadians(h)), h)
    }

    private fun rfOf(de: Double) = 10 * ln(1 + exp((100 - 6.73 * de) / 10))

    fun tm30(s: DoubleArray): Tm30 {
        val c = cct(s)
        val t = c.kelvin
        val ref = when {
            t < 4000 -> planck(t)
            t > 5000 -> daylight(t)
            else -> {
                val p = planck(t); val d = daylight(t)
                val yp = xyz(p, cmf2)[1]; val yd = xyz(d, cmf2)[1]; val m = (t - 4000) / 1000
                DoubleArray(81) { (1 - m) * p[it] / yp + m * d[it] / yd }
            }
        }
        val wt = xyz(s, cmf10).let { v -> DoubleArray(3) { v[it] * 100 / v[1] } }
        val wr = xyz(ref, cmf10).let { v -> DoubleArray(3) { v[it] * 100 / v[1] } }
        val kt = 100 / xyz(s, cmf10)[1]; val kr = 100 / xyz(ref, cmf10)[1]
        val test = ces.map { r -> jab(xyz(s, cmf10, r).map { it * kt }.toDoubleArray(), wt) }
        val refs = ces.map { r -> jab(xyz(ref, cmf10, r).map { it * kr }.toDoubleArray(), wr) }
        val de = DoubleArray(ces.size) { sqrt((0..2).sumOf { k -> (test[it][k] - refs[it][k]).pow(2) }) }
        val rf = rfOf(de.average())
        // 16 hue bins of the reference hue angle.
        val ta = Array(16) { DoubleArray(2) }; val ra = Array(16) { DoubleArray(2) }; val cnt = IntArray(16); val deBin = DoubleArray(16)
        for (i in ces.indices) {
            val bin = (refs[i][3] / 22.5).toInt().coerceIn(0, 15)
            ta[bin][0] += test[i][1]; ta[bin][1] += test[i][2]; ra[bin][0] += refs[i][1]; ra[bin][1] += refs[i][2]; cnt[bin]++; deBin[bin] += de[i]
        }
        for (b in 0 until 16) if (cnt[b] > 0) { ta[b][0] /= cnt[b]; ta[b][1] /= cnt[b]; ra[b][0] /= cnt[b]; ra[b][1] /= cnt[b]; deBin[b] /= cnt[b] }
        fun area(p: Array<DoubleArray>) = (0 until 16).sumOf { i -> val u = p[i]; val v = p[(i + 1) % 16]; (u[0] * v[1] - u[1] * v[0]) / 2 }
        val rg = 100 * area(ta) / area(ra)
        val rcs = DoubleArray(16) { b ->
            val ang = Math.toRadians(22.5 * b + 11.25); val norm = sqrt(ra[b][0].pow(2) + ra[b][1].pow(2))
            100 * ((ta[b][0] - ra[b][0]) * cos(ang) + (ta[b][1] - ra[b][1]) * sin(ang)) / norm
        }
        return Tm30(rf, rg, c, rcs, DoubleArray(16) { rfOf(deBin[it]) })
    }

}
