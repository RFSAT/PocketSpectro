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

    data class Peak(val pixel: Int, val wavelengthNm: Double, val intensity: Double, val percentAboveFloor: Double)

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
        val l = (lo - gap - width).coerceAtLeast(0)..(lo - gap).coerceAtLeast(0)
        val r = (hi + gap).coerceAtMost(n - 1)..(hi + gap + width).coerceAtMost(n - 1)
        return l to r
    }

    fun analyse(wavelengths: DoubleArray, counts: DoubleArray, cfg: Config): Result {
        require(wavelengths.size == counts.size && counts.size >= 4)
        val floor = floorOf(counts)
        val (lf, rf) = flanks(wavelengths, cfg)
        val base: (Int) -> Double = when (cfg.baseline) {
            Baseline.GLOBAL_MEAN -> { _ -> floor }
            Baseline.LOCAL_CONTINUUM -> {
                val lx = lf.map { wavelengths[it] }.average(); val ly = lf.map { counts[it] }.average()
                val rx = rf.map { wavelengths[it] }.average(); val ry = rf.map { counts[it] }.average()
                val slope = if (rx > lx) (ry - ly) / (rx - lx) else 0.0
                val fn: (Int) -> Double = { p -> (ly + slope * (wavelengths[p] - lx)).coerceAtLeast(1.0) }
                fn
            }
        }
        val w1 = window(wavelengths, counts, base, peakPixelRange(wavelengths, cfg, 1), cfg.algorithm)
        val w2 = window(wavelengths, counts, base, peakPixelRange(wavelengths, cfg, 2), cfg.algorithm)
        val fire = w1.percentAboveFloor > cfg.thresholdPercent || w2.percentAboveFloor > cfg.thresholdPercent
        return Result(floor, cfg.baseline, lf, rf, w1, w2, fire, otherMaxima(wavelengths, counts, floor))
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

    /** Pi's "List of other maxima": gradient zero crossings at least 1 % above the floor. */
    fun otherMaxima(wl: DoubleArray, y: DoubleArray, floor: Double, limit: Int = 50): List<Peak> {
        val out = ArrayList<Peak>()
        for (p in 1 until y.size - 1) {
            if (y[p] >= y[p - 1] && y[p] > y[p + 1] && y[p] >= floor * 1.01) {
                out += Peak(p, wl[p], y[p], y[p] * 100.0 / floor - 100.0)
            }
        }
        return out.sortedByDescending { it.intensity }.take(limit).sortedBy { it.pixel }
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
