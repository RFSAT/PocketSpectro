package com.rfsat.pocketspectro.data

import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Daily exposure and physiology logs for research use (e.g. ENACT exposome studies):
 * files/exposure/<kind>_YYYYMMDD.csv, one row per sample, local date.
 */
class ExposureLog(root: File) {
    val dir = File(root, "exposure").apply { mkdirs() }

    enum class Kind(val header: String) {
        LIGHT("time_utc,epoch_ms,lux,melanopic_edi_lx,cct_k,integration_ms"),
        UV("time_utc,epoch_ms,uv_index,erythemal_w_m2,dose_sed_today"),
        PULSE("time_utc,epoch_ms,heart_rate_bpm,perfusion_index_pct,rmssd_ms,quality"),
        SKIN("time_utc,epoch_ms,label,erythema_index,melanin_index,carotenoid_index"),
        PLANT_LIGHT("time_utc,epoch_ms,ppfd_umol_m2_s,dli_mol_m2_today,red_far_red"),
    }

    fun day(timeMs: Long): String { val c = Calendar.getInstance().apply { timeInMillis = timeMs }; return "%04d%02d%02d".format(Locale.US, c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH]) }

    fun file(kind: Kind, timeMs: Long) = File(dir, "${kind.name.lowercase(Locale.US)}_${day(timeMs)}.csv")

    @Synchronized
    fun append(kind: Kind, timeMs: Long, values: List<Any?>) {
        val f = file(kind, timeMs)
        val head = if (!f.exists()) kind.header + "\n" else ""
        f.appendText(head + (listOf(SessionLogger.iso(timeMs), timeMs) + values).joinToString(",") { v ->
            when (v) { null -> ""; is Double -> if (v.isFinite()) "%.6g".format(Locale.US, v) else ""; is String -> "\"" + v.replace("\"", "'") + "\""; else -> v.toString() }
        } + "\n")
    }

    fun read(kind: Kind, timeMs: Long): List<Map<String, String>> = file(kind, timeMs).let { if (it.exists()) CsvTable.read(it) else emptyList() }

    /** All log files of the day [timeMs] falls on. */
    fun filesOf(timeMs: Long): List<File> = Kind.entries.map { file(it, timeMs) }.filter { it.exists() }

    /** Local clock hour (0–24) of a time. */
    fun hourOf(timeMs: Long): Double { val c = Calendar.getInstance().apply { timeInMillis = timeMs }; return c[Calendar.HOUR_OF_DAY] + c[Calendar.MINUTE] / 60.0 + c[Calendar.SECOND] / 3600.0 }
}
