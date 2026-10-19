package com.rfsat.pocketspectro.tools

/**
 * Emission-line monitor: for each line, find the peak near its nominal wavelength, measure it
 * above a straight-line local continuum (flanks either side), and decide whether it is present.
 * The STS resolves about 1.5 nm, so close doublets (Na D 589.0/589.6) are one line here.
 */
object EmissionLines {

    data class Line(val label: String, val nm: Double)

    enum class Preset(val lines: List<Line>) {
        FLAME(listOf(
            Line("Ca", 422.7), Line("Sr", 460.7), Line("Ba", 553.5), Line("Na D", 589.3), Line("Li", 670.8),
            Line("K", 766.5), Line("K", 769.9), Line("Rb", 780.0), Line("Cs", 852.1),
        )),
        LAMP(listOf(
            Line("Hg", 404.7), Line("Hg", 435.8), Line("Hg", 546.1), Line("Hg", 578.0),
            Line("Ar", 696.5), Line("Ar", 750.4), Line("Ar", 763.5), Line("Ar", 811.5), Line("Ar", 912.3),
        )),
        LASER(listOf(
            Line("405", 405.0), Line("450", 450.0), Line("532", 532.0), Line("635", 635.0), Line("650", 650.0),
            Line("785", 785.0), Line("808", 808.0), Line("850", 850.0), Line("905", 905.0), Line("980", 980.0),
        )),
        CUSTOM(emptyList()),
    }

    data class Config(
        val preset: Preset = Preset.FLAME,
        /** User lines for [Preset.CUSTOM]. */
        val custom: List<Line> = emptyList(),
        val searchHalfWidthNm: Double = 2.0,
        val thresholdPct: Double = 5.0,
        val minSnr: Double = 5.0,
    ) {
        val lines get() = if (preset == Preset.CUSTOM) custom else preset.lines
    }

    data class Measurement(
        val line: Line,
        val peakNm: Double,
        val centroidNm: Double,
        val shiftNm: Double,
        val fwhmNm: Double,
        val counts: Double,
        val continuum: Double,
        val height: Double,
        val pctAbove: Double,
        val snr: Double,
        val present: Boolean,
    )

    /** Lines of [cfg] inside the spectrometer's range (with room for the flanks). */
    fun inRange(wl: DoubleArray, cfg: Config) = cfg.lines.filter { it.nm - 10 >= wl.first() && it.nm + 10 <= wl.last() }

    fun measure(wl: DoubleArray, y: DoubleArray, cfg: Config): List<Measurement> {
        val lines = inRange(wl, cfg)
        return lines.map { line -> measureOne(wl, y, cfg, line, lines.filter { it !== line }.map { it.nm }) }
    }

    private fun measureOne(wl: DoubleArray, y: DoubleArray, cfg: Config, line: Line, others: List<Double>): Measurement {
        val p = Spectra.peak(wl, y, line.nm - cfg.searchHalfWidthNm, line.nm + cfg.searchHalfWidthNm)!!
        // Flanks skip the other lines of the set (close doublets such as K 766.5 / 769.9).
        val fit = Spectra.localContinuum(wl, y, p.nm, 3.0, 6.0, others)
        val cont = fit?.y(p.nm) ?: Spectra.bandMean(wl, y, wl.first(), wl.last())
        val height = y[p.index] - cont
        val noise = fit?.let { Spectra.flankNoise(wl, y, p.nm, 3.0, 6.0, it, others) } ?: Double.NaN
        val snr = if (noise.isFinite() && noise > 0) height / noise else Double.NaN
        val pct = if (cont > 1.0) 100.0 * height / cont else if (height > 0) Double.POSITIVE_INFINITY else 0.0
        val shape = Spectra.peak(wl, y, p.nm - 2.5, p.nm + 2.5, floor = cont)
        val present = height > 0 && pct >= cfg.thresholdPct && (snr.isNaN() || snr >= cfg.minSnr)
        return Measurement(line, p.nm, shape?.centroidNm ?: p.nm, (shape?.centroidNm ?: p.nm) - line.nm,
            shape?.fwhmNm ?: Double.NaN, y[p.index], cont, height, pct, snr, present)
    }

    /** Parses "Na 589.3, Li 670.8" (label optional) into lines. */
    fun parseCustom(text: String): List<Line> = text.split(',', ';', '\n').mapNotNull { part ->
        val t = part.trim()
        if (t.isEmpty()) return@mapNotNull null
        val m = Regex("""^(.*?)\s*(\d+(?:\.\d+)?)\s*(?:nm)?$""").find(t) ?: return@mapNotNull null
        val nm = m.groupValues[2].toDoubleOrNull()?.takeIf { it in 150.0..2600.0 } ?: return@mapNotNull null
        Line(m.groupValues[1].trim().ifEmpty { "%.1f".format(java.util.Locale.US, nm) }, nm)
    }

    fun formatCustom(lines: List<Line>) = lines.joinToString(", ") { "${it.label} ${"%.1f".format(java.util.Locale.US, it.nm)}" }
}
