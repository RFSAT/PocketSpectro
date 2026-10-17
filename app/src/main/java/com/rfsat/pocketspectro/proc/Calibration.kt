package com.rfsat.pocketspectro.proc

object Calibration {

    /** lambda(p) = c0 + c1 p + c2 p^2 + c3 p^3 (Ocean wavelength calibration). */
    fun wavelength(coeffs: DoubleArray, pixel: Double): Double {
        var x = 1.0
        var sum = 0.0
        for (c in coeffs) { sum += c * x; x *= pixel }
        return sum
    }

    /**
     * Wavelengths for a binned readout: binned pixel i covers unbinned pixels
     * [i*2^b, (i+1)*2^b), so its wavelength is the mean over that group. With no
     * calibration the unbinned pixel index is used instead.
     */
    fun binnedWavelengths(coeffs: DoubleArray?, unbinnedPixels: Int, binningFactor: Int): DoubleArray {
        val group = 1 shl binningFactor
        val n = unbinnedPixels / group
        return DoubleArray(n) { i ->
            var s = 0.0
            for (k in 0 until group) {
                val p = (i * group + k).toDouble()
                s += if (coeffs != null) wavelength(coeffs, p) else p
            }
            s / group
        }
    }

    /** True when coefficients look like a real NIR calibration (monotonic, plausible range). */
    fun isPlausible(coeffs: DoubleArray?, pixels: Int): Boolean {
        if (coeffs == null || coeffs.size < 2) return false
        val first = wavelength(coeffs, 0.0)
        val last = wavelength(coeffs, (pixels - 1).toDouble())
        return first.isFinite() && last.isFinite() && last > first && first > 100.0 && last < 3000.0
    }

    /**
     * Ocean nonlinearity correction: corrected = c / (k0 + k1 c + k2 c^2 + ...), applied to
     * dark-corrected counts. Values where the polynomial is not sensibly positive are left alone.
     */
    fun correctNonlinearity(counts: DoubleArray, k: DoubleArray): DoubleArray = DoubleArray(counts.size) { i ->
        val c = counts[i]
        var x = 1.0
        var p = 0.0
        for (coef in k) { p += coef * x; x *= c }
        if (p > 0.2 && p.isFinite()) c / p else c
    }

    /** Nearest pixel index to a wavelength (wavelengths ascending). */
    fun pixelFor(wavelengths: DoubleArray, nm: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in wavelengths.indices) {
            val d = kotlin.math.abs(wavelengths[i] - nm)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
