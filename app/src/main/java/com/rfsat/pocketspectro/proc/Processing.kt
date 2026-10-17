package com.rfsat.pocketspectro.proc

import kotlin.math.log10

enum class DisplayMode(val label: String, val unit: String) {
    COUNTS("Counts", "counts"),
    ABSORBANCE("Absorbance", "AU"),
    TRANSMITTANCE("Transmittance / Reflectance", "%"),
}

/** Element-wise running mean of raw scans. */
class ScanAverager(size: Int) {
    private val sum = DoubleArray(size)
    var count = 0
        private set
    var maxRaw = 0
        private set

    fun add(scan: IntArray) {
        for (i in scan.indices) {
            sum[i] += scan[i]
            if (scan[i] > maxRaw) maxRaw = scan[i]
        }
        count++
    }

    fun mean(): DoubleArray = DoubleArray(sum.size) { sum[it] / count.coerceAtLeast(1) }
}

object Processing {

    /**
     * Boxcar smoothing with Ocean's convention: width w averages each pixel with w
     * neighbours on each side (2w + 1 pixels), truncated at the ends.
     */
    fun boxcar(y: DoubleArray, w: Int): DoubleArray {
        if (w <= 0 || y.size < 3) return y.copyOf()
        val n = y.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + y[i]
        return DoubleArray(n) { i ->
            val a = (i - w).coerceAtLeast(0)
            val b = (i + w).coerceAtMost(n - 1)
            (prefix[b + 1] - prefix[a]) / (b - a + 1)
        }
    }

    fun subtract(a: DoubleArray, b: DoubleArray?): DoubleArray =
        if (b == null || b.size != a.size) a.copyOf() else DoubleArray(a.size) { a[it] - b[it] }

    /**
     * Signal chain shared by live display, references and logging:
     * (mean raw - dark) -> optional nonlinearity (only meaningful once dark-corrected).
     */
    fun correctedCounts(meanRaw: DoubleArray, dark: DoubleArray?, nl: DoubleArray?, applyNl: Boolean): DoubleArray {
        val s = subtract(meanRaw, dark)
        return if (applyNl && nl != null && dark != null) Calibration.correctNonlinearity(s, nl) else s
    }

    /** Transmittance / reflectance in percent: 100 * S / R (both already dark-corrected). */
    fun transmittance(sample: DoubleArray, reference: DoubleArray): DoubleArray = DoubleArray(sample.size) { i ->
        val r = reference[i]
        if (r > MIN_REF) 100.0 * sample[i] / r else Double.NaN
    }

    /** Absorbance in AU: -log10(S / R). */
    fun absorbance(sample: DoubleArray, reference: DoubleArray): DoubleArray = DoubleArray(sample.size) { i ->
        val r = reference[i]
        val s = sample[i]
        if (r > MIN_REF && s > 0.0) -log10(s / r) else Double.NaN
    }

    private const val MIN_REF = 1.0
}
