package com.rfsat.pocketspectro.tools

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Direct-sun photometry with a collimated spectrometer: aerosol optical depth (AOD) at the AERONET
 * wavelengths, the Ångström exponent, and precipitable water vapour from the 940 nm band.
 *
 * Beer–Lambert–Bouguer: V = V0 · (d0/d)² · exp(−m · τ). V0 (signal outside the atmosphere) comes from
 * a Langley plot (ln V against airmass on a clear, stable morning) or from a known AOD.
 * Water vapour: T_w = exp(−a (m W)^b) in the 940 nm band (Reagan / Bruegge form).
 */
object SunPhotometer {

    val AEROSOL_NM = doubleArrayOf(440.0, 500.0, 675.0, 870.0, 1020.0)
    const val WATER_NM = 940.0
    const val HALF_BAND = 5.0
    /** Band-averaged water-vapour constants for a 10 nm band at 940 nm. */
    const val WATER_A = 0.70
    const val WATER_B = 0.60

    /** Channels this unit covers (aerosol channels, then 940 nm when covered). */
    fun channels(wl: DoubleArray): List<Double> =
        (AEROSOL_NM.toList() + WATER_NM).filter { Spectra.covers(wl, it - HALF_BAND, it + HALF_BAND) }

    /** Dark-corrected signal per channel in counts per second. */
    fun signals(wl: DoubleArray, counts: DoubleArray, integrationUs: Long): Map<Double, Double> {
        val s = 1e6 / integrationUs.coerceAtLeast(1)
        return channels(wl).associateWith { Spectra.bandMean(wl, counts, it - HALF_BAND, it + HALF_BAND) * s }
    }

    data class Atmosphere(val hPa: Double = 1013.25, val ozoneDu: Double = 300.0)

    /** Optical depth of everything but aerosol and water vapour. */
    fun gasTau(nm: Double, atm: Atmosphere) = Solar.rayleighTau(nm, atm.hPa) + Solar.ozoneTau(nm, atm.ozoneDu)

    data class Result(
        val airmass: Double,
        /** Aerosol optical depth per calibrated channel. */
        val aod: Map<Double, Double>,
        val angstrom: Double?,
        /** AOD at 500 nm (measured, or from the Ångström fit). */
        val aod500: Double?,
        /** Precipitable water vapour, cm. */
        val pwvCm: Double?,
    )

    fun analyse(sig: Map<Double, Double>, v0: Map<Double, Double>, m: Double, distFactor: Double, atm: Atmosphere): Result {
        val aod = LinkedHashMap<Double, Double>()
        for ((nm, v) in sig) {
            if (nm == WATER_NM) continue
            val c = v0[nm] ?: continue
            if (v <= 0 || c <= 0) continue
            aod[nm] = ln(c * distFactor / v) / m - gasTau(nm, atm)
        }
        val fit = angstromFit(aod)
        val aod500 = aod[500.0] ?: fit?.let { (alpha, beta) -> beta * 0.5.pow(-alpha) }
        val pwv = run {
            val v = sig[WATER_NM] ?: return@run null
            val c = v0[WATER_NM] ?: return@run null
            val ta = aodAt(WATER_NM, aod, fit) ?: return@run null
            val tw = v / (c * distFactor * exp(-m * (gasTau(WATER_NM, atm) + ta)))
            if (tw <= 0 || tw >= 1) return@run if (tw >= 1) 0.0 else null
            (-ln(tw) / WATER_A).pow(1 / WATER_B) / m
        }
        return Result(m, aod, fit?.first, aod500, pwv)
    }

    /** Ångström fit τ = β λ^−α (λ in µm) through the channels with a usable AOD: (α, β). */
    fun angstromFit(aod: Map<Double, Double>): Pair<Double, Double>? {
        val p = aod.filter { it.value > 0.003 }
        if (p.size < 2) return null
        val f = Spectra.lineFit(p.keys.map { ln(it / 1000.0) }.toDoubleArray(), p.values.map { ln(it) }.toDoubleArray()) ?: return null
        return -f.slope to exp(f.intercept)
    }

    /** Aerosol optical depth at [nm] from the Ångström fit, or the nearest channel. */
    fun aodAt(nm: Double, aod: Map<Double, Double>, fit: Pair<Double, Double>? = angstromFit(aod)): Double? =
        fit?.let { (a, b) -> b * (nm / 1000.0).pow(-a) } ?: aod.minByOrNull { kotlin.math.abs(it.key - nm) }?.value

    /** V0 from one measurement and a known aerosol optical depth (AOD 500 nm, Ångström α), with PWV for 940 nm. */
    fun calibrateFromKnown(sig: Map<Double, Double>, m: Double, distFactor: Double, atm: Atmosphere,
                           aod500: Double, alpha: Double, pwvCm: Double?): Map<Double, Double> {
        val out = HashMap<Double, Double>()
        for ((nm, v) in sig) {
            if (v <= 0) continue
            val ta = aod500 * (nm / 500.0).pow(-alpha)
            var tau = m * (gasTau(nm, atm) + ta)
            if (nm == WATER_NM) { if (pwvCm == null) continue; tau += WATER_A * (m * pwvCm).pow(WATER_B) }
            out[nm] = v * exp(tau) / distFactor
        }
        return out
    }

    data class LangleyPoint(val airmass: Double, val sig: Map<Double, Double>, val distFactor: Double)

    data class LangleyFit(val v0: Map<Double, Double>, val r2: Map<Double, Double>, val points: Int, val mRange: ClosedFloatingPointRange<Double>)

    /**
     * Langley calibration over airmass 2–6 (where the sky is most stable): ln V = ln V0 − m τ.
     * The 940 nm channel uses the modified Langley plot ln V + m τ_other = ln V0 − a W^b m^b.
     */
    fun langley(points: List<LangleyPoint>, atm: Atmosphere, mMin: Double = 2.0, mMax: Double = 6.0): LangleyFit? {
        val p = points.filter { it.airmass in mMin..mMax }
        if (p.size < 8 || p.maxOf { it.airmass } - p.minOf { it.airmass } < 1.0) return null
        val v0 = HashMap<Double, Double>(); val r2 = HashMap<Double, Double>()
        val chans = p.first().sig.keys
        for (nm in chans) {
            if (nm == WATER_NM) continue
            val q = p.filter { (it.sig[nm] ?: 0.0) > 0 }
            val f = Spectra.lineFit(q.map { it.airmass }.toDoubleArray(), q.map { ln(it.sig.getValue(nm) / it.distFactor) }.toDoubleArray()) ?: continue
            v0[nm] = exp(f.intercept); r2[nm] = f.r2
        }
        if (WATER_NM in chans && v0.size >= 2) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (pt in p) {
                val v = pt.sig[WATER_NM] ?: continue
                if (v <= 0) continue
                val aod = analyse(pt.sig, v0, pt.airmass, pt.distFactor, atm).aod
                val ta = aodAt(WATER_NM, aod) ?: continue
                xs += pt.airmass.pow(WATER_B)
                ys += ln(v / pt.distFactor) + pt.airmass * (gasTau(WATER_NM, atm) + ta)
            }
            Spectra.lineFit(xs.toDoubleArray(), ys.toDoubleArray())?.let { v0[WATER_NM] = exp(it.intercept); r2[WATER_NM] = it.r2 }
        }
        if (v0.isEmpty()) return null
        return LangleyFit(v0, r2, p.size, p.minOf { it.airmass }..p.maxOf { it.airmass })
    }
}
