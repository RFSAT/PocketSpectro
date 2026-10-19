package com.rfsat.pocketspectro.tools

import java.io.File
import java.util.Locale

/**
 * Spectral match: a library of named reference spectra (files/library/<name>.csv) and a
 * similarity score for identifying an unknown. Both spectra are put on a common 2 nm grid over
 * the wavelengths they share and smoothed over 14 nm; the library spectrum is then scaled and
 * offset to fit the unknown (least squares), and the score is 1 - 5 x (RMS residual / mean level).
 * So brightness and a constant offset do not matter, featureless spectra (paper, white plastics)
 * still compare reliably, and 1.0 means the unknown is fully explained by that library spectrum.
 */
object SpectralLibrary {

    enum class Quantity { REFLECTANCE, COUNTS }

    data class Entry(val name: String, val quantity: Quantity, val model: String, val timeMs: Long,
                     val wavelengths: DoubleArray, val values: DoubleArray, val file: File?)

    data class Match(val entry: Entry, val score: Double)

    fun dir(root: File) = File(root, "library").apply { mkdirs() }

    fun save(root: File, name: String, q: Quantity, model: String, wl: DoubleArray, y: DoubleArray, timeMs: Long = System.currentTimeMillis()): Entry {
        val safe = name.trim().ifEmpty { "spectrum" }.replace(Regex("""[^\p{L}\p{N} _.-]"""), "_").take(60)
        var f = File(dir(root), "$safe.csv"); var n = 2
        while (f.exists()) f = File(dir(root), "$safe ($n).csv").also { n++ }
        f.bufferedWriter().use { w ->
            w.write("# name=${name.trim()}\n# quantity=${q.name}\n# model=$model\n# time_ms=$timeMs\nwavelength_nm,value\n")
            for (i in wl.indices) w.write("%.4f,%.6g\n".format(Locale.US, wl[i], y[i]))
        }
        return Entry(name.trim(), q, model, timeMs, wl, y, f)
    }

    fun load(root: File): List<Entry> = dir(root).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().mapNotNull { f ->
        try {
            val meta = HashMap<String, String>(); val wl = ArrayList<Double>(); val v = ArrayList<Double>()
            f.forEachLine { line ->
                when {
                    line.startsWith("#") -> line.removePrefix("#").split('=', limit = 2).takeIf { it.size == 2 }?.let { meta[it[0].trim()] = it[1].trim() }
                    else -> line.split(',').takeIf { it.size >= 2 }?.let { p ->
                        val a = p[0].toDoubleOrNull(); val b = p[1].toDoubleOrNull()
                        if (a != null && b != null) { wl += a; v += b }
                    }
                }
            }
            if (wl.size < 10) null else Entry(meta["name"] ?: f.nameWithoutExtension,
                Quantity.entries.firstOrNull { it.name == meta["quantity"] } ?: Quantity.COUNTS, meta["model"] ?: "",
                meta["time_ms"]?.toLongOrNull() ?: f.lastModified(), wl.toDoubleArray(), v.toDoubleArray(), f)
        } catch (_: Exception) { null }
    }.sortedBy { it.name.lowercase() }

    fun delete(e: Entry) { e.file?.delete() }

    /** Similarity of two spectra (see class comment), 0..1; NaN when they share less than 40 nm. */
    fun score(wl1: DoubleArray, y1: DoubleArray, wl2: DoubleArray, y2: DoubleArray): Double {
        val lo = maxOf(wl1.first(), wl2.first()) + 2.0
        val hi = minOf(wl1.last(), wl2.last()) - 2.0
        if (hi - lo < 40.0) return Double.NaN
        val g = Spectra.grid(lo, hi, 2.0)
        val u = smooth(Spectra.resample(wl1, y1, g)); val lib = smooth(Spectra.resample(wl2, y2, g))
        val fit = Spectra.lineFit(lib, u) ?: return 0.0
        var ss = 0.0
        for (i in u.indices) { val r = u[i] - fit.y(lib[i]); ss += r * r }
        val level = u.map { kotlin.math.abs(it) }.average()
        if (level <= 0 || fit.slope <= 0) return 0.0
        return (1.0 - 5.0 * kotlin.math.sqrt(ss / u.size) / level).coerceIn(0.0, 1.0)
    }

    fun match(wl: DoubleArray, y: DoubleArray, q: Quantity, library: List<Entry>): List<Match> =
        library.filter { it.quantity == q }.map { Match(it, score(wl, y, it.wavelengths, it.values)) }
            .filter { it.score.isFinite() }.sortedByDescending { it.score }

    private fun smooth(y: DoubleArray) = DoubleArray(y.size) { i ->
        var s = 0.0; var n = 0
        for (k in i - 3..i + 3) if (k in y.indices) { s += y[k]; n++ }
        s / n
    }
}
