package com.rfsat.pocketspectro.tools

/**
 * Vegetation indices from a reflectance spectrum (0..1, sample / white reference).
 * Each index is computed only when the spectrometer covers its bands.
 *  - NDVI = (NIR - Red) / (NIR + Red), red 660-680 nm, NIR 800-850 nm (or the top 20 nm below 850).
 *  - Red-edge position (linear interpolation, Guyot & Baret 1988):
 *    R_re = (R670 + R780) / 2,  REP = 700 + 40 (R_re - R700) / (R740 - R700).
 *  - Red-edge chlorophyll index CIre = R780 / R710 - 1 (Gitelson 2003).
 *  - Water band index WBI = R900 / R970 (Peñuelas 1993).
 *  - Photochemical reflectance index PRI = (R531 - R570) / (R531 + R570) (Gamon 1992), VIS units.
 */
object Vegetation {

    enum class Health { NONE, SPARSE, MODERATE, DENSE }

    data class Result(
        val ndvi: Double,
        val redNm: ClosedFloatingPointRange<Double>,
        val nirNm: ClosedFloatingPointRange<Double>,
        val rep: Double?,
        val cire: Double?,
        val wbi: Double?,
        val pri: Double?,
        val health: Health,
    )

    /** True when the unit covers red and some NIR (the NDVI minimum). */
    fun supported(wl: DoubleArray) = wl.isNotEmpty() && wl.first() <= 660.0 && wl.last() >= 780.0

    fun nirBand(wl: DoubleArray): ClosedFloatingPointRange<Double> =
        if (wl.last() >= 850.0) 800.0..850.0 else (wl.last() - 20.0)..wl.last()

    fun analyse(wl: DoubleArray, r: DoubleArray): Result? {
        if (!supported(wl)) return null
        val red = 660.0..680.0
        val nir = nirBand(wl)
        val rr = Spectra.bandMean(wl, r, red.start, red.endInclusive)
        val rn = Spectra.bandMean(wl, r, nir.start, nir.endInclusive)
        val ndvi = if (rn + rr > 0) (rn - rr) / (rn + rr) else Double.NaN
        fun b(nm: Double) = Spectra.bandMean(wl, r, nm - 2.0, nm + 2.0)
        val rep = if (Spectra.covers(wl, 668.0, 782.0)) {
            val rre = (b(670.0) + b(780.0)) / 2
            val d = b(740.0) - b(700.0)
            if (d > 1e-6) (700.0 + 40.0 * (rre - b(700.0)) / d).takeIf { it in 680.0..760.0 } else null
        } else null
        val cire = if (Spectra.covers(wl, 708.0, 782.0) && b(710.0) > 1e-6) b(780.0) / b(710.0) - 1.0 else null
        val wbi = if (Spectra.covers(wl, 898.0, 972.0) && b(970.0) > 1e-6) b(900.0) / b(970.0) else null
        val pri = if (Spectra.covers(wl, 529.0, 572.0)) {
            val a = b(531.0); val c = b(570.0); if (a + c > 0) (a - c) / (a + c) else null
        } else null
        return Result(ndvi, red, nir, rep, cire, wbi, pri, health(ndvi))
    }

    fun health(ndvi: Double) = when {
        !ndvi.isFinite() || ndvi < 0.2 -> Health.NONE
        ndvi < 0.4 -> Health.SPARSE
        ndvi < 0.6 -> Health.MODERATE
        else -> Health.DENSE
    }

    /** Map colour for an NDVI value: brown (bare) through yellow to green (dense), as #RRGGBB. */
    fun colour(ndvi: Double): String {
        val stops = listOf(-0.1 to intArrayOf(0x8D, 0x6E, 0x63), 0.2 to intArrayOf(0xD7, 0xB4, 0x6A),
            0.4 to intArrayOf(0xF2, 0xE2, 0x4B), 0.6 to intArrayOf(0x8B, 0xC3, 0x4A), 0.85 to intArrayOf(0x1B, 0x5E, 0x20))
        val v = if (ndvi.isFinite()) ndvi.coerceIn(stops.first().first, stops.last().first) else stops.first().first
        val i = stops.indexOfLast { it.first <= v }.coerceAtMost(stops.size - 2)
        val (x0, c0) = stops[i]; val (x1, c1) = stops[i + 1]
        val t = ((v - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        val c = IntArray(3) { (c0[it] + t * (c1[it] - c0[it])).toInt() }
        return "#%02X%02X%02X".format(c[0], c[1], c[2])
    }
}
