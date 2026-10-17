package com.rfsat.pocketspectro.data

import com.rfsat.pocketspectro.proc.FireDetector
import java.io.File
import java.util.Locale

/** Measurement conditions saved with a detection. */
data class DetectionContext(
    val model: String,
    val serial: String,
    val integrationUs: Long,
    val scans: Int,
    val thresholdPct: Double,
    val confirmFrames: Int,
    val wavelengths: DoubleArray,
    val counts: DoubleArray,
)

/** One confirmed fire detection with its position, analysis and measurement conditions. */
data class FireEvent(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Double?,
    val speedMs: Double?,
    val bearingDeg: Double?,
    val line1Nm: Double,
    val line1Counts: Double,
    val line1Baseline: Double,
    val line1Pct: Double,
    val line2Nm: Double,
    val line2Counts: Double,
    val line2Baseline: Double,
    val line2Pct: Double,
    val baseline: String,
    val confidence: Double,
    val snr1: Double,
    val snr2: Double,
    val ratio: Double,
    val model: String,
    val serial: String,
    val integrationUs: Long,
    val scans: Int,
    val thresholdPct: Double,
    val confirmFrames: Int,
    val session: String?,
    /** Spectrum saved at the detection (wavelength_nm,counts), relative to the journal folder. */
    val spectrumFile: String?,
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
    fun record(
        timeMs: Long, fix: GeoFix?, r: FireDetector.Result, session: String?, risingEdge: Boolean, ctx: DetectionContext,
    ): FireEvent? {
        if (fix == null) return null
        val prev = last
        if (!risingEdge && prev != null &&
            timeMs - prev.timeMs < minIntervalMs &&
            distanceM(prev.latitude, prev.longitude, fix.latitude, fix.longitude) < minDistanceM
        ) return null
        val c = r.confidence
        val specName = "spectra/$timeMs.csv"
        val e = FireEvent(
            timeMs, fix.latitude, fix.longitude, fix.altitudeM, fix.accuracyM?.toDouble(), fix.speedMs?.toDouble(), fix.bearingDeg?.toDouble(),
            r.window1.wavelengthNm, r.window1.intensity, r.window1.baseline, r.window1.percentAboveFloor,
            r.window2.wavelengthNm, r.window2.intensity, r.window2.baseline, r.window2.percentAboveFloor,
            r.baselineMode.name, c.score, c.snr1, c.snr2, c.ratio,
            ctx.model.replace(',', ' '), ctx.serial.replace(',', ' '), ctx.integrationUs, ctx.scans, ctx.thresholdPct, ctx.confirmFrames,
            session, specName,
        )
        val dir = file.parentFile!!
        File(dir, "spectra").mkdirs()
        File(dir, specName).writeText(buildString {
            appendLine("wavelength_nm,counts")
            for (i in ctx.wavelengths.indices) appendLine("%.4f,%.2f".format(Locale.US, ctx.wavelengths[i], ctx.counts[i]))
        })
        val newFile = !file.exists()
        file.appendText(buildString {
            if (newFile) appendLine(HEADER)
            appendLine(listOf(
                SessionLogger.iso(timeMs), timeMs.toString(), "%.7f".format(Locale.US, e.latitude), "%.7f".format(Locale.US, e.longitude),
                f1(e.altitudeM), f1(e.accuracyM), f2(e.speedMs), f1(e.bearingDeg),
                f3(e.line1Nm), f1(e.line1Counts), f1(e.line1Baseline), f2(e.line1Pct),
                f3(e.line2Nm), f1(e.line2Counts), f1(e.line2Baseline), f2(e.line2Pct),
                e.baseline, f1(e.confidence), f2(e.snr1), f2(e.snr2), f3(e.ratio),
                e.model, e.serial, e.integrationUs.toString(), e.scans.toString(), f1(e.thresholdPct), e.confirmFrames.toString(),
                session ?: "", specName,
            ).joinToString(","))
        })
        last = e
        return e
    }

    fun load(): List<FireEvent> = if (!file.exists()) emptyList() else CsvTable.read(file).mapNotNull { row ->
        fun d(k: String) = row[k]?.toDoubleOrNull()
        val lat = d("latitude") ?: return@mapNotNull null
        val lon = d("longitude") ?: return@mapNotNull null
        FireEvent(
            timeMs = row["epoch_ms"]?.toLongOrNull() ?: 0L,
            latitude = lat, longitude = lon,
            altitudeM = d("altitude_m"), accuracyM = d("accuracy_m"), speedMs = d("speed_ms"), bearingDeg = d("bearing_deg"),
            line1Nm = d("line1_nm") ?: Double.NaN, line1Counts = d("line1_counts") ?: Double.NaN,
            line1Baseline = d("line1_baseline") ?: Double.NaN, line1Pct = d("line1_pct") ?: Double.NaN,
            line2Nm = d("line2_nm") ?: Double.NaN, line2Counts = d("line2_counts") ?: Double.NaN,
            line2Baseline = d("line2_baseline") ?: Double.NaN, line2Pct = d("line2_pct") ?: Double.NaN,
            baseline = row["baseline"] ?: "",
            confidence = d("confidence") ?: Double.NaN, snr1 = d("snr1") ?: Double.NaN, snr2 = d("snr2") ?: Double.NaN,
            ratio = d("ratio") ?: Double.NaN,
            model = row["model"] ?: "", serial = row["serial"] ?: "",
            integrationUs = row["integration_us"]?.toLongOrNull() ?: 0L, scans = row["scans"]?.toIntOrNull() ?: 0,
            thresholdPct = d("threshold_pct") ?: Double.NaN, confirmFrames = row["confirm_frames"]?.toIntOrNull() ?: 0,
            session = row["session"]?.ifBlank { null },
            spectrumFile = row["spectrum_file"]?.ifBlank { null },
        )
    }

    /** Wavelengths and counts saved with a detection, or null when missing. */
    fun loadSpectrum(e: FireEvent): Pair<DoubleArray, DoubleArray>? {
        val f = e.spectrumFile?.let { File(file.parentFile, it) } ?: return null
        if (!f.exists()) return null
        val rows = f.readLines().drop(1).mapNotNull { line ->
            val c = line.split(',')
            val w = c.getOrNull(0)?.toDoubleOrNull(); val v = c.getOrNull(1)?.toDoubleOrNull()
            if (w != null && v != null) w to v else null
        }
        if (rows.size < 4) return null
        return rows.map { it.first }.toDoubleArray() to rows.map { it.second }.toDoubleArray()
    }

    private fun f1(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.1f".format(Locale.US, it) } ?: ""
    private fun f2(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.US, it) } ?: ""
    private fun f3(v: Double?) = v?.takeIf { it.isFinite() }?.let { "%.3f".format(Locale.US, it) } ?: ""

    fun clear() { file.delete(); File(file.parentFile, "spectra").deleteRecursively(); last = null }

    companion object {
        const val HEADER = "time_utc,epoch_ms,latitude,longitude,altitude_m,accuracy_m,speed_ms,bearing_deg," +
            "line1_nm,line1_counts,line1_baseline,line1_pct,line2_nm,line2_counts,line2_baseline,line2_pct," +
            "baseline,confidence,snr1,snr2,ratio,model,serial,integration_us,scans,threshold_pct,confirm_frames,session,spectrum_file"

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
