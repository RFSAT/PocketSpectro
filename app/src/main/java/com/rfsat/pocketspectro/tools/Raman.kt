package com.rfsat.pocketspectro.tools

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Raman spectroscopy with a 785 nm laser and a long-pass filter in front of an NIR spectrometer:
 * wavelength → Raman shift, fluorescence baseline removal, peak picking and matching against a
 * library of peak positions. At the ~1.5 nm resolution of a pocket spectrometer the bands are about
 * 20 cm⁻¹ wide, enough to tell common plastics, minerals and solvents apart.
 */
object Raman {

    /** Raman shift (cm⁻¹) of light at [nm] for a laser at [laserNm]. */
    fun shift(nm: Double, laserNm: Double) = 1e7 / laserNm - 1e7 / nm

    /** Wavelength (nm) of a band at [shift] cm⁻¹. */
    fun wavelength(shift: Double, laserNm: Double) = 1e7 / (1e7 / laserNm - shift)

    data class Spectrum(val shift: DoubleArray, val y: DoubleArray, val baseline: DoubleArray, val noise: Double)

    /**
     * Converts to Raman shift between [minShift] and [maxShift] and removes the fluorescence background
     * with an iteratively clipped polynomial (modified polyfit, Lieber & Mahadevan-Jansen 2003).
     */
    fun prepare(wl: DoubleArray, counts: DoubleArray, laserNm: Double, minShift: Double = 250.0, maxShift: Double = 3400.0, degree: Int = 5): Spectrum? {
        val idx = wl.indices.filter { shift(wl[it], laserNm) in minShift..maxShift }
        if (idx.size < 30) return null
        val s = DoubleArray(idx.size) { shift(wl[idx[it]], laserNm) }
        val y = DoubleArray(idx.size) { counts[idx[it]] }
        val x0 = (s.first() + s.last()) / 2; val xr = (s.last() - s.first()) / 2
        val xs = DoubleArray(s.size) { (s[it] - x0) / xr }
        var work = Spectra.smooth(y, 1)
        var base = work.copyOf()
        repeat(80) {
            val c = Spectra.polyFit(xs, work, degree) ?: return null
            base = DoubleArray(xs.size) { Spectra.polyVal(c, xs[it]) }
            var changed = false
            work = DoubleArray(xs.size) { i -> if (work[i] > base[i]) { changed = true; base[i] } else work[i] }
            if (!changed) return@repeat
        }
        val r = DoubleArray(y.size) { y[it] - base[it] }
        return Spectrum(s, r, base, Spectra.noise(y))
    }

    data class Peak(val shift: Double, val height: Double)

    /** Local maxima more than [snr] × noise above the baseline, strongest first. */
    fun peaks(sp: Spectrum, snr: Double = 6.0): List<Peak> {
        val y = Spectra.smooth(sp.y, 1)
        val thr = snr * sp.noise.coerceAtLeast(1e-9)
        val out = ArrayList<Peak>()
        for (i in 3 until y.size - 3) {
            if (y[i] < thr) continue
            var top = true
            for (k in i - 3..i + 3) if (k != i && y[k] > y[i]) { top = false; break }
            if (!top) continue
            // Parabolic interpolation of the peak position.
            val a = y[i - 1]; val b = y[i]; val c = y[i + 1]
            val d = (a - c) / (2 * (a - 2 * b + c)).let { if (it == 0.0) 1e-12 else it }
            val step = (sp.shift[i + 1] - sp.shift[i - 1]) / 2
            out += Peak(sp.shift[i] + d.coerceIn(-0.5, 0.5) * step, b)
        }
        return out.sortedByDescending { it.height }
    }

    /** A reference material: band positions (cm⁻¹) with relative intensities (strongest = 1). */
    data class Ref(val name: String, val bands: List<Pair<Double, Double>>, val user: Boolean = false)

    /** Approximate literature band positions of common materials (strong bands only). */
    val LIBRARY = listOf(
        Ref("Polystyrene (PS)", listOf(621.0 to 0.25, 795.0 to 0.15, 1001.0 to 1.0, 1031.0 to 0.3, 1155.0 to 0.15, 1583.0 to 0.15, 1602.0 to 0.45, 2904.0 to 0.3, 3054.0 to 0.7)),
        Ref("Polyethylene (PE)", listOf(1062.0 to 0.5, 1129.0 to 0.55, 1295.0 to 0.7, 1440.0 to 0.8, 2848.0 to 1.0, 2882.0 to 0.9)),
        Ref("Polypropylene (PP)", listOf(398.0 to 0.4, 809.0 to 0.8, 841.0 to 0.75, 973.0 to 0.4, 1152.0 to 0.3, 1330.0 to 0.3, 1458.0 to 0.6, 2883.0 to 1.0, 2952.0 to 0.8)),
        Ref("PET", listOf(632.0 to 0.2, 857.0 to 0.3, 1096.0 to 0.3, 1286.0 to 0.4, 1615.0 to 1.0, 1726.0 to 0.55, 3080.0 to 0.2)),
        Ref("PMMA (acrylic)", listOf(600.0 to 0.3, 812.0 to 0.8, 988.0 to 0.2, 1452.0 to 0.5, 1728.0 to 0.4, 2955.0 to 1.0)),
        Ref("Calcite (CaCO₃)", listOf(282.0 to 0.3, 712.0 to 0.15, 1086.0 to 1.0)),
        Ref("Gypsum (CaSO₄·2H₂O)", listOf(415.0 to 0.2, 493.0 to 0.2, 1008.0 to 1.0, 1136.0 to 0.1)),
        Ref("Quartz (SiO₂)", listOf(355.0 to 0.1, 464.0 to 1.0)),
        Ref("Diamond", listOf(1332.0 to 1.0)),
        Ref("Silicon", listOf(520.0 to 1.0, 960.0 to 0.08)),
        Ref("Sulfur", listOf(473.0 to 1.0)),
        Ref("Ethanol", listOf(433.0 to 0.3, 882.0 to 1.0, 1052.0 to 0.35, 1096.0 to 0.3, 1454.0 to 0.45, 2928.0 to 0.9, 2974.0 to 0.6)),
        Ref("Cyclohexane", listOf(384.0 to 0.15, 801.0 to 1.0, 1028.0 to 0.5, 1266.0 to 0.3, 1444.0 to 0.4, 2852.0 to 0.8, 2923.0 to 0.9, 2938.0 to 0.85)),
        Ref("Paracetamol", listOf(465.0 to 0.2, 651.0 to 0.25, 797.0 to 0.35, 858.0 to 0.5, 1168.0 to 0.45, 1236.0 to 0.4, 1324.0 to 0.4, 1561.0 to 0.3, 1611.0 to 0.6, 1648.0 to 0.4)),
        Ref("Aspirin", listOf(551.0 to 0.2, 750.0 to 0.35, 1044.0 to 0.4, 1191.0 to 0.3, 1293.0 to 0.25, 1606.0 to 1.0, 1622.0 to 0.6, 1753.0 to 0.25, 3075.0 to 0.3)),
    )

    data class Match(val ref: Ref, val score: Double, val matched: Int, val total: Int, val offset: Double)

    /**
     * Scores every reference: share of its (intensity-weighted) bands found within [tolerance] cm⁻¹,
     * times how much of the strong measured peaks it explains. A common offset of up to ±20 cm⁻¹
     * (laser wavelength error) is allowed.
     */
    fun match(peaks: List<Peak>, range: ClosedFloatingPointRange<Double>, refs: List<Ref>, tolerance: Double = 12.0): List<Match> {
        if (peaks.isEmpty()) return emptyList()
        val hmax = peaks.maxOf { it.height }
        val strong = peaks.filter { it.height > 0.2 * hmax }
        val out = ArrayList<Match>()
        for (r in refs) {
            val bands = r.bands.filter { it.first in range }
            if (bands.isEmpty()) continue
            val wsum = bands.sumOf { it.second }
            var best: Match? = null
            var off = -20.0
            while (off <= 20.0) {
                var got = 0.0; var n = 0
                for ((pos, w) in bands) if (peaks.any { abs(it.shift - (pos + off)) <= tolerance }) { got += w; n++ }
                val explained = strong.count { p -> bands.any { abs(p.shift - (it.first + off)) <= tolerance } }.toDouble() / strong.size
                val score = got / wsum * (0.4 + 0.6 * explained)
                if (best == null || score > best.score) best = Match(r, score, n, bands.size, off)
                off += 2.0
            }
            best?.let { out += it }
        }
        return out.sortedByDescending { it.score }
    }

    /** Centroid of the residual laser line near [laserNm] (±5 nm), or null when it is not visible. */
    fun laserLine(wl: DoubleArray, counts: DoubleArray, laserNm: Double): Double? {
        val p = Spectra.peak(wl, counts, laserNm - 5, laserNm + 5) ?: return null
        val around = wl.indices.filter { abs(wl[it] - laserNm) in 6.0..14.0 }.map { counts[it] }
        if (around.isEmpty()) return null
        val bg = Spectra.median(around.toDoubleArray())
        return if (p.height > 3 * bg.coerceAtLeast(1.0) && p.centroidNm.isFinite()) p.centroidNm else null
    }

    // ---- user library: files/raman_library.csv (name, then shift:intensity pairs) ---------------

    fun userLibrary(root: File): List<Ref> {
        val f = File(root, "raman_library.csv")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 2) return@mapNotNull null
            val bands = p.drop(1).mapNotNull { b -> val q = b.split(':'); val s = q.getOrNull(0)?.toDoubleOrNull(); val i = q.getOrNull(1)?.toDoubleOrNull(); if (s != null && i != null) s to i else null }
            if (bands.isEmpty()) null else Ref(p[0], bands, user = true)
        }
    }

    fun addToUserLibrary(root: File, name: String, peaks: List<Peak>) {
        val top = peaks.take(12); if (top.isEmpty()) return
        val hmax = top.maxOf { it.height }
        File(root, "raman_library.csv").appendText(name.replace(",", " ").ifBlank { "Sample" } + "," +
            top.joinToString(",") { String.format(Locale.US, "%.1f:%.2f", it.shift, it.height / hmax) } + "\n")
    }

    fun removeFromUserLibrary(root: File, name: String) {
        val f = File(root, "raman_library.csv"); if (!f.exists()) return
        f.writeText(f.readLines().filter { it.split(',').firstOrNull() != name }.joinToString("") { it + "\n" })
    }
}
