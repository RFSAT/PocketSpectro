package com.rfsat.pocketspectro.proc

/**
 * Biomass-fire detection from the potassium (K I) emission doublet, ported from the
 * Pi "AF3" Detect page (af3-peaks.php).
 *
 * What the Pi did, per request:
 *  - integration 200 ms; boxcar 3 and averaging 0 were *requested*, but the Ocean daemon
 *    never applied either, so detection ran on one raw, unsmoothed scan;
 *  - floor = floor(sum(counts[0 .. n-2]) / n)  (the mean of the spectrum);
 *  - forward-difference gradient; in pixel windows i = 278..282 and 287..291 a zero
 *    crossing gradient[i+1] * gradient[i] <= 0 marks a peak candidate at pixel i+1, keeping
 *    the candidate with the largest *wavelength* (i.e. the last one in the window);
 *  - peak% = counts * 100 / floor - 100; "FIRE" when either window's peak% > 10.
 *
 * The Pi measured each line against the mean of the WHOLE spectrum ([Baseline.GLOBAL_MEAN]).
 * That makes the verdict depend on the overall spectral shape: wherever the continuum near
 * 766 nm happens to sit >10 % above the spectrum mean, it reports fire with no emission at
 * all. [Baseline.LOCAL_CONTINUUM] (default) instead compares each line with a straight-line
 * continuum fitted through flanking regions either side of the doublet.
 *
 * Quirks of the original kept only in [Algorithm.PI_IDENTICAL]: a zero crossing also fires
 * on local minima, and with no crossing in a window it falls back to pixel 1.
 * [Algorithm.LOCAL_MAXIMUM] (default) takes the strongest true local maximum in the window
 * (or the window maximum when none) and is otherwise identical.
 */
object FireDetector {

    const val K_LINE_1_NM = 766.49
    const val K_LINE_2_NM = 769.90
    /** Short-wavelength edge of the O2 A band (its head is at 759.4 nm). */
    const val O2_A_BAND_START_NM = 757.5

    enum class Algorithm { LOCAL_MAXIMUM, PI_IDENTICAL }

    enum class WindowMode { WAVELENGTH, PIXEL }

    enum class Baseline { LOCAL_CONTINUUM, GLOBAL_MEAN }

    data class Config(
        val windowMode: WindowMode = WindowMode.WAVELENGTH,
        val centre1Nm: Double = K_LINE_1_NM,
        val centre2Nm: Double = K_LINE_2_NM,
        val halfWidthNm: Double = 1.2,
        /** Pi pixel windows, as gradient indices i (the candidate peak is at i + 1). */
        val pixelWindow1: IntRange = 278..282,
        val pixelWindow2: IntRange = 287..291,
        val thresholdPercent: Double = 10.0,
        val algorithm: Algorithm = Algorithm.LOCAL_MAXIMUM,
        val baseline: Baseline = Baseline.LOCAL_CONTINUUM,
        /** Flank regions start this far outside the line windows and are this wide (nm, or pixels in PIXEL mode x2). */
        val flankGapNm: Double = 2.0,
        val flankWidthNm: Double = 8.0,
        /**
         * Keep the left flank below the atmospheric oxygen A band (759-765 nm), which in daylight
         * pulls the continuum down next to the K I lines and makes them look brighter than they are.
         */
        val avoidOxygenBand: Boolean = true,
        /** Consecutive positive frames required before raising an alarm (Pi: 1). */
        val confirmFrames: Int = 1,
    )

    data class WindowResult(
        val firstPixel: Int,
        val lastPixel: Int,
        val peakPixel: Int,
        val wavelengthNm: Double,
        val intensity: Double,
        /** Level the peak is compared with: local continuum or global mean, per [Config.baseline]. */
        val baseline: Double,
        val percentAboveFloor: Double,
    )

    /** A local maximum, measured against [baseline] (local continuum, or spectrum mean in the Pi preset). */
    data class Peak(val pixel: Int, val wavelengthNm: Double, val intensity: Double, val baseline: Double, val percentAboveFloor: Double)

    /**
     * How much the detection can be trusted, 0-100 %, and what that rests on. Always computed
     * against the local continuum (straight line through the flanks), whichever baseline the
     * fire/no-fire rule uses:
     *  - snr: line height above the continuum divided by the scatter of the flank points about it;
     *  - both lines of the doublet should rise above the noise;
     *  - their height ratio (766.5 / 769.9) should be about 1-2.5 (2 when optically thin, nearer 1
     *    when self-absorbed);
     *  - the peaks should sit close to the K I wavelengths.
     */
    data class Confidence(
        val score: Double,
        val snr1: Double,
        val snr2: Double,
        val ratio: Double,
        val offset1Nm: Double,
        val offset2Nm: Double,
        val noise: Double,
        val snrFactor: Double,
        val doubletFactor: Double,
        val ratioFactor: Double,
        val positionFactor: Double,
    )

    data class Result(
        /** Mean of the spectrum (the Pi's "floor"). */
        val floor: Double,
        val baselineMode: Baseline,
        /** Flank regions used for the local continuum (pixel ranges). */
        val leftFlank: IntRange,
        val rightFlank: IntRange,
        val window1: WindowResult,
        val window2: WindowResult,
        val fire: Boolean,
        val otherMaxima: List<Peak>,
        val confidence: Confidence,
    ) {
        val maxPercent get() = maxOf(window1.percentAboveFloor, window2.percentAboveFloor)
    }

    /** Candidate-peak pixel range for a window (inclusive), clamped to [1, n-2]. */
    fun peakPixelRange(wavelengths: DoubleArray, cfg: Config, which: Int): IntRange {
        val n = wavelengths.size
        val r = when (cfg.windowMode) {
            WindowMode.PIXEL -> {
                val w = if (which == 1) cfg.pixelWindow1 else cfg.pixelWindow2
                (w.first + 1)..(w.last + 1)
            }
            WindowMode.WAVELENGTH -> {
                val c = if (which == 1) cfg.centre1Nm else cfg.centre2Nm
                Calibration.pixelFor(wavelengths, c - cfg.halfWidthNm)..Calibration.pixelFor(wavelengths, c + cfg.halfWidthNm)
            }
        }
        val a = r.first.coerceIn(1, n - 2)
        val b = r.last.coerceIn(a, n - 2)
        return a..b
    }

    fun floorOf(counts: DoubleArray): Double {
        val n = counts.size
        if (n < 2) return 1.0
        var s = 0.0
        for (i in 0 until n - 1) s += counts[i]
        return kotlin.math.floor(s / n).coerceAtLeast(1.0)
    }

    /** Flank pixel ranges either side of the doublet, clamped to the detector. */
    fun flanks(wavelengths: DoubleArray, cfg: Config): Pair<IntRange, IntRange> {
        val n = wavelengths.size
        val w1 = peakPixelRange(wavelengths, cfg, 1)
        val w2 = peakPixelRange(wavelengths, cfg, 2)
        val lo = minOf(w1.first, w2.first)
        val hi = maxOf(w1.last, w2.last)
        val (gap, width) = if (cfg.windowMode == WindowMode.PIXEL) {
            (cfg.flankGapNm * 2).toInt().coerceAtLeast(1) to (cfg.flankWidthNm * 2).toInt().coerceAtLeast(2)
        } else {
            val step = ((wavelengths[hi] - wavelengths[lo]) / (hi - lo).coerceAtLeast(1)).coerceAtLeast(1e-6)
            (cfg.flankGapNm / step).toInt().coerceAtLeast(1) to (cfg.flankWidthNm / step).toInt().coerceAtLeast(2)
        }
        var l = (lo - gap - width).coerceAtLeast(0)..(lo - gap).coerceAtLeast(0)
        if (cfg.avoidOxygenBand && cfg.windowMode == WindowMode.WAVELENGTH && wavelengths[l.last] > O2_A_BAND_START_NM) {
            // Same width, ending just below the band.
            val end = (0 until n).lastOrNull { wavelengths[it] <= O2_A_BAND_START_NM } ?: l.last
            l = (end - width).coerceAtLeast(0)..end
        }
        val r = (hi + gap).coerceAtMost(n - 1)..(hi + gap + width).coerceAtMost(n - 1)
        return l to r
    }

    fun analyse(wavelengths: DoubleArray, counts: DoubleArray, cfg: Config): Result {
        require(wavelengths.size == counts.size && counts.size >= 4)
        val floor = floorOf(counts)
        val (lf, rf) = flanks(wavelengths, cfg)
        val lx = lf.map { wavelengths[it] }.average(); val ly = lf.map { counts[it] }.average()
        val rx = rf.map { wavelengths[it] }.average(); val ry = rf.map { counts[it] }.average()
        val slope = if (rx > lx) (ry - ly) / (rx - lx) else 0.0
        val local: (Int) -> Double = { p -> (ly + slope * (wavelengths[p] - lx)).coerceAtLeast(1.0) }
        val base: (Int) -> Double = when (cfg.baseline) {
            Baseline.GLOBAL_MEAN -> { _ -> floor }
            Baseline.LOCAL_CONTINUUM -> local
        }
        val w1 = window(wavelengths, counts, base, peakPixelRange(wavelengths, cfg, 1), cfg.algorithm)
        val w2 = window(wavelengths, counts, base, peakPixelRange(wavelengths, cfg, 2), cfg.algorithm)
        val fire = w1.percentAboveFloor > cfg.thresholdPercent || w2.percentAboveFloor > cfg.thresholdPercent
        val maxima = if (cfg.baseline == Baseline.GLOBAL_MEAN) otherMaxima(wavelengths, counts, floor)
        else otherMaximaLocal(wavelengths, counts)
        val conf = confidence(wavelengths, counts, lf, rf, local, w1, w2)
        return Result(floor, cfg.baseline, lf, rf, w1, w2, fire, maxima, conf)
    }

    private fun confidence(
        wl: DoubleArray, y: DoubleArray, lf: IntRange, rf: IntRange, local: (Int) -> Double,
        w1: WindowResult, w2: WindowResult,
    ): Confidence {
        val residuals = (lf + rf).map { y[it] - local(it) }
        val mean = residuals.average()
        val sd = if (residuals.size >= 3) kotlin.math.sqrt(residuals.sumOf { (it - mean) * (it - mean) } / (residuals.size - 1)) else Double.NaN
        // Never trust a noise estimate below photon/read noise.
        val noise = maxOf(if (sd.isFinite()) sd else 0.0, kotlin.math.sqrt(local(w1.peakPixel).coerceAtLeast(1.0)) * 0.5, 1.0)
        val a1 = y[w1.peakPixel] - local(w1.peakPixel)
        val a2 = y[w2.peakPixel] - local(w2.peakPixel)
        val snr1 = a1 / noise; val snr2 = a2 / noise
        val ratio = if (a2 > 0) a1 / a2 else Double.NaN
        val o1 = w1.wavelengthNm - K_LINE_1_NM; val o2 = w2.wavelengthNm - K_LINE_2_NM
        val fSnr = ((maxOf(snr1, snr2) - 3.0) / 7.0).coerceIn(0.0, 1.0)
        val fDoublet = when {
            snr1 >= 3 && snr2 >= 3 -> 1.0
            snr1 >= 3 || snr2 >= 3 -> 0.5
            else -> 0.0
        }
        val fRatio = if (ratio.isFinite() && ratio in 0.8..2.6) 1.0 else 0.6
        val fPos = 1.0 - 0.5 * (maxOf(kotlin.math.abs(o1), kotlin.math.abs(o2)) / 1.0).coerceIn(0.0, 1.0)
        return Confidence(100.0 * fSnr * fDoublet * fRatio * fPos, snr1, snr2, ratio, o1, o2, noise, fSnr, fDoublet, fRatio, fPos)
    }

    private fun window(wl: DoubleArray, y: DoubleArray, base: (Int) -> Double, range: IntRange, alg: Algorithm): WindowResult {
        val peak = when (alg) {
            Algorithm.PI_IDENTICAL -> {
                // Gradient index i in (range - 1); candidate at i + 1; keep the largest wavelength.
                var maxi = 0
                var maxWl = 0.0
                for (p in range) {
                    val i = p - 1
                    val g0 = (y[i + 1] - y[i]) / (wl[i + 1] - wl[i])
                    val g1 = (y[i + 2] - y[i + 1]) / (wl[i + 2] - wl[i + 1])
                    if (g1 * g0 <= 0.0 && wl[i + 1] > maxWl) { maxi = i; maxWl = wl[i + 1] }
                }
                maxi + 1
            }
            Algorithm.LOCAL_MAXIMUM -> {
                var best = -1
                for (p in range) {
                    if (y[p] >= y[p - 1] && y[p] >= y[p + 1] && (best < 0 || y[p] > y[best])) best = p
                }
                if (best < 0) range.maxByOrNull { y[it] } ?: range.first else best
            }
        }
        val b = base(peak)
        return WindowResult(range.first, range.last, peak, wl[peak], y[peak], b, y[peak] * 100.0 / b - 100.0)
    }

    /** Pi's "List of other maxima": gradient zero crossings at least 1 % above the spectrum mean. */
    fun otherMaxima(wl: DoubleArray, y: DoubleArray, floor: Double, limit: Int = 50): List<Peak> {
        val out = ArrayList<Peak>()
        for (p in 1 until y.size - 1) {
            if (y[p] >= y[p - 1] && y[p] > y[p + 1] && y[p] >= floor * 1.01) {
                out += Peak(p, wl[p], y[p], floor, y[p] * 100.0 / floor - 100.0)
            }
        }
        return out.sortedByDescending { it.intensity }.take(limit).sortedBy { it.pixel }
    }

    /**
     * Local maxima standing at least [minPercent] above the local continuum, estimated as a running
     * median over +/- [halfWindowNm]. The median ignores narrow lines, so it follows the continuum
     * under them; the list is sorted by how far each peak rises above it.
     */
    fun otherMaximaLocal(wl: DoubleArray, y: DoubleArray, halfWindowNm: Double = 12.0, minPercent: Double = 1.0, limit: Int = 50): List<Peak> {
        val n = y.size
        if (n < 5) return emptyList()
        val step = ((wl[n - 1] - wl[0]) / (n - 1)).let { if (it > 0) it else 1.0 }
        val h = (halfWindowNm / step).toInt().coerceIn(3, n / 2)
        val base = runningMedian(y, h)
        val out = ArrayList<Peak>()
        for (p in 1 until n - 1) {
            val b = base[p].coerceAtLeast(1.0)
            if (y[p] >= y[p - 1] && y[p] > y[p + 1] && y[p] >= b * (1 + minPercent / 100.0)) {
                out += Peak(p, wl[p], y[p], b, y[p] * 100.0 / b - 100.0)
            }
        }
        return out.sortedByDescending { it.percentAboveFloor }.take(limit).sortedBy { it.pixel }
    }

    fun runningMedian(y: DoubleArray, h: Int): DoubleArray {
        val n = y.size
        val buf = DoubleArray(2 * h + 1)
        return DoubleArray(n) { i ->
            val a = (i - h).coerceAtLeast(0); val b = (i + h).coerceAtMost(n - 1)
            val m = b - a + 1
            for (k in 0 until m) buf[k] = y[a + k]
            java.util.Arrays.sort(buf, 0, m)
            if (m % 2 == 1) buf[m / 2] else (buf[m / 2 - 1] + buf[m / 2]) / 2
        }
    }
}

/** Debounces detections so an alarm needs N consecutive positive frames. */
class FireConfirmer(var required: Int = 1) {
    private var run = 0
    fun update(positive: Boolean): Boolean {
        run = if (positive) run + 1 else 0
        return run >= required.coerceAtLeast(1)
    }
    fun reset() { run = 0 }
}
