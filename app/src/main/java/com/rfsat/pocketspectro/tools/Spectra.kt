package com.rfsat.pocketspectro.tools

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small numeric helpers shared by the tools. Wavelength arrays are ascending (nm). */
object Spectra {

    fun covers(wl: DoubleArray, lo: Double, hi: Double) = wl.isNotEmpty() && wl.first() <= lo && wl.last() >= hi

    /** Index of the pixel nearest to [nm]. */
    fun index(wl: DoubleArray, nm: Double): Int {
        var lo = 0; var hi = wl.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (wl[mid] < nm) lo = mid else hi = mid }
        return if (abs(wl[lo] - nm) <= abs(wl[hi] - nm)) lo else hi
    }

    /** Linear interpolation of y at [nm] (clamped to the ends). */
    fun at(wl: DoubleArray, y: DoubleArray, nm: Double): Double {
        if (nm <= wl.first()) return y.first()
        if (nm >= wl.last()) return y.last()
        var i = index(wl, nm)
        if (wl[i] > nm) i--
        val t = (nm - wl[i]) / (wl[i + 1] - wl[i])
        return y[i] + t * (y[i + 1] - y[i])
    }

    /** Mean of y over [lo, hi] nm (at least the nearest pixel). */
    fun bandMean(wl: DoubleArray, y: DoubleArray, lo: Double, hi: Double): Double {
        var s = 0.0; var n = 0
        for (i in wl.indices) if (wl[i] in lo..hi && y[i].isFinite()) { s += y[i]; n++ }
        return if (n > 0) s / n else at(wl, y, (lo + hi) / 2)
    }

    /** y resampled onto [grid] by linear interpolation. */
    fun resample(wl: DoubleArray, y: DoubleArray, grid: DoubleArray) = DoubleArray(grid.size) { at(wl, y, grid[it]) }

    /** Uniform grid from lo to hi (inclusive) with [step]. */
    fun grid(lo: Double, hi: Double, step: Double): DoubleArray {
        val n = ((hi - lo) / step).toInt() + 1
        return DoubleArray(n) { lo + it * step }
    }

    /** Planck spectral radiance shape at [nm] for temperature [k] (arbitrary units). */
    fun planck(nm: Double, k: Double): Double {
        val l = nm * 1e-9
        return 1.0 / (l * l * l * l * l * (exp(1.438777e-2 / (l * k)) - 1.0)) * 1e-30
    }

    /** Least-squares straight line through (x, y): returns slope, intercept and R². */
    data class LineFit(val slope: Double, val intercept: Double, val r2: Double) {
        fun y(x: Double) = slope * x + intercept
    }

    fun lineFit(x: DoubleArray, y: DoubleArray): LineFit? {
        val n = x.size
        if (n < 2) return null
        val mx = x.average(); val my = y.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) { val dx = x[i] - mx; val dy = y[i] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        if (sxx == 0.0) return null
        val m = sxy / sxx
        val r2 = if (syy == 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
        return LineFit(m, my - m * mx, r2)
    }

    /** Pearson correlation of two equally long arrays. */
    fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; sab += da * db; saa += da * da; sbb += db * db }
        return if (saa == 0.0 || sbb == 0.0) 0.0 else sab / sqrt(saa * sbb)
    }

    /** Peak of a spectrum inside [lo, hi] with its centroid and full width at half maximum above [floor]. */
    data class Peak(val index: Int, val nm: Double, val height: Double, val centroidNm: Double, val fwhmNm: Double)

    fun peak(wl: DoubleArray, y: DoubleArray, lo: Double = wl.first(), hi: Double = wl.last(), floor: Double = 0.0): Peak? {
        var best = -1
        for (i in wl.indices) if (wl[i] in lo..hi && (best < 0 || y[i] > y[best])) best = i
        if (best < 0) return null
        val h = y[best] - floor
        if (h <= 0) return Peak(best, wl[best], h, wl[best], Double.NaN)
        val half = floor + h / 2
        var l = best; while (l > 0 && y[l] > half) l--
        var r = best; while (r < wl.size - 1 && y[r] > half) r++
        fun cross(a: Int, b: Int): Double {
            val ya = y[a]; val yb = y[b]
            return if (ya == yb) wl[a] else wl[a] + (half - ya) / (yb - ya) * (wl[b] - wl[a])
        }
        val left = if (y[l] <= half) cross(l, l + 1) else wl[l]
        val right = if (y[r] <= half) cross(r - 1, r) else wl[r]
        var sw = 0.0; var s = 0.0
        for (i in l..r) { val v = max(0.0, y[i] - half); sw += v * wl[i]; s += v }
        return Peak(best, wl[best], h, if (s > 0) sw / s else wl[best], right - left)
    }

    /** Straight-line continuum through two flanks either side of [centre], used to measure lines. */
    fun localContinuum(wl: DoubleArray, y: DoubleArray, centre: Double, gap: Double, width: Double,
                       exclude: List<Double> = emptyList(), excludeHalfWidth: Double = 2.5): Spectra.LineFit? {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in wl.indices) {
            val d = abs(wl[i] - centre)
            if (d >= gap && d <= gap + width && exclude.none { abs(wl[i] - it) < excludeHalfWidth }) { xs += wl[i]; ys += y[i] }
        }
        return lineFit(xs.toDoubleArray(), ys.toDoubleArray())
    }

    /** Standard deviation of the residuals of [y] about [fit] over the flank pixels (noise estimate). */
    fun flankNoise(wl: DoubleArray, y: DoubleArray, centre: Double, gap: Double, width: Double, fit: LineFit,
                   exclude: List<Double> = emptyList(), excludeHalfWidth: Double = 2.5): Double {
        var s = 0.0; var n = 0
        for (i in wl.indices) {
            val d = abs(wl[i] - centre)
            if (d >= gap && d <= gap + width && exclude.none { abs(wl[i] - it) < excludeHalfWidth }) { val r = y[i] - fit.y(wl[i]); s += r * r; n++ }
        }
        return if (n > 1) sqrt(s / (n - 1)) else Double.NaN
    }

    fun clamp01(v: Double) = min(1.0, max(0.0, v))
}
