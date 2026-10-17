package com.rfsat.pocketspectro.data

import com.rfsat.pocketspectro.proc.FireDetector
import java.io.File
import java.util.Locale

/** One confirmed fire detection with its position. */
data class FireEvent(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Double?,
    val line1Nm: Double,
    val line1Pct: Double,
    val line2Nm: Double,
    val line2Pct: Double,
    val baseline: String,
    val session: String?,
) {
    val maxPct get() = maxOf(line1Pct, line2Pct)
}

data class TrackPoint(val timeMs: Long, val latitude: Double, val longitude: Double)

data class SessionTrack(val name: String, val points: List<TrackPoint>)

/**
 * Persistent journal of confirmed fire detections (files/events/fire_events.csv).
 * Written whether or not a logging session is open, so every detection that had a
 * GPS fix ends up on the map. A sustained detection is re-recorded only every
 * [minIntervalMs] or after moving [minDistanceM], so one fire isn't hundreds of rows.
 */
class FireEventJournal(root: File, private val minIntervalMs: Long = 10_000, private val minDistanceM: Double = 25.0) {
    val file: File = File(root, "events/fire_events.csv")
    private var last: FireEvent? = null

    @Synchronized
    fun record(timeMs: Long, fix: GeoFix?, r: FireDetector.Result, session: String?, risingEdge: Boolean): FireEvent? {
        if (fix == null) return null
        val prev = last
        if (!risingEdge && prev != null &&
            timeMs - prev.timeMs < minIntervalMs &&
            distanceM(prev.latitude, prev.longitude, fix.latitude, fix.longitude) < minDistanceM
        ) return null
        val e = FireEvent(
            timeMs, fix.latitude, fix.longitude, fix.altitudeM, fix.accuracyM?.toDouble(),
            r.window1.wavelengthNm, r.window1.percentAboveFloor, r.window2.wavelengthNm, r.window2.percentAboveFloor,
            r.baselineMode.name, session,
        )
        file.parentFile?.mkdirs()
        val newFile = !file.exists()
        file.appendText(buildString {
            if (newFile) appendLine(HEADER)
            appendLine(
                "%s,%d,%.7f,%.7f,%s,%s,%.3f,%.2f,%.3f,%.2f,%s,%s".format(
                    Locale.US, SessionLogger.iso(timeMs), timeMs, e.latitude, e.longitude,
                    e.altitudeM?.let { "%.1f".format(Locale.US, it) } ?: "",
                    e.accuracyM?.let { "%.1f".format(Locale.US, it) } ?: "",
                    e.line1Nm, e.line1Pct, e.line2Nm, e.line2Pct, e.baseline, session ?: "",
                )
            )
        })
        last = e
        return e
    }

    fun load(): List<FireEvent> = if (!file.exists()) emptyList() else CsvTable.read(file).mapNotNull { row ->
        val lat = row["latitude"]?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = row["longitude"]?.toDoubleOrNull() ?: return@mapNotNull null
        FireEvent(
            timeMs = row["epoch_ms"]?.toLongOrNull() ?: 0L,
            latitude = lat, longitude = lon,
            altitudeM = row["altitude_m"]?.toDoubleOrNull(),
            accuracyM = row["accuracy_m"]?.toDoubleOrNull(),
            line1Nm = row["line1_nm"]?.toDoubleOrNull() ?: Double.NaN,
            line1Pct = row["line1_pct"]?.toDoubleOrNull() ?: Double.NaN,
            line2Nm = row["line2_nm"]?.toDoubleOrNull() ?: Double.NaN,
            line2Pct = row["line2_pct"]?.toDoubleOrNull() ?: Double.NaN,
            baseline = row["baseline"] ?: "",
            session = row["session"]?.ifBlank { null },
        )
    }

    fun clear() { file.delete(); last = null }

    companion object {
        const val HEADER = "time_utc,epoch_ms,latitude,longitude,altitude_m,accuracy_m,line1_nm,line1_pct,line2_nm,line2_pct,baseline,session"

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
            return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

/** Survey tracks from the logged sessions (positions of saved spectra). */
object SessionTracks {
    fun load(sessionsRoot: File, maxPointsPerSession: Int = 2000): List<SessionTrack> =
        (sessionsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }.mapNotNull { dir ->
            val src = listOf(File(dir, "fire.csv"), File(dir, "spectra.csv")).firstOrNull { it.exists() } ?: return@mapNotNull null
            val pts = CsvTable.readPositions(src)
            if (pts.isEmpty()) null else SessionTrack(dir.name, thin(pts, maxPointsPerSession))
        }

    private fun thin(p: List<TrackPoint>, max: Int): List<TrackPoint> {
        if (p.size <= max) return p
        val step = p.size.toDouble() / max
        return List(max) { p[(it * step).toInt()] } + p.last()
    }
}

/** Minimal CSV reading for the files this app writes (no quoted fields). */
object CsvTable {
    fun read(f: File): List<Map<String, String>> {
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = lines.first().split(',')
        return lines.drop(1).map { line ->
            val cells = line.split(',')
            head.indices.associate { head[it] to cells.getOrElse(it) { "" } }
        }
    }

    /** Reads only time and position columns, cheap even for wide spectra.csv rows. */
    fun readPositions(f: File): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        f.bufferedReader().use { r ->
            val head = r.readLine()?.split(',') ?: return out
            val iT = head.indexOf("epoch_ms"); val iLat = head.indexOf("latitude"); val iLon = head.indexOf("longitude")
            if (iLat < 0 || iLon < 0) return out
            val need = maxOf(iT, iLat, iLon) + 2
            r.lineSequence().forEach { line ->
                val c = line.split(',', limit = need)
                val lat = c.getOrNull(iLat)?.toDoubleOrNull() ?: return@forEach
                val lon = c.getOrNull(iLon)?.toDoubleOrNull() ?: return@forEach
                out += TrackPoint(c.getOrNull(iT)?.toLongOrNull() ?: 0L, lat, lon)
            }
        }
        return out
    }
}
