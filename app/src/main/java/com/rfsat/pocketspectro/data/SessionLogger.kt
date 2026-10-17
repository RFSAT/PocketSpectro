package com.rfsat.pocketspectro.data

import com.rfsat.pocketspectro.core.DeviceInfo
import com.rfsat.pocketspectro.proc.FireDetector
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One spectrum as written to disk. */
data class LoggedSpectrum(
    val timeMs: Long,
    val integrationUs: Long,
    val scansAveraged: Int,
    val boxcarWidth: Int,
    val darkCorrected: Boolean,
    val nonlinearityCorrected: Boolean,
    val wavelengths: DoubleArray,
    val counts: DoubleArray,
    val fix: GeoFix?,
)

/**
 * Writes one logging session into its own folder:
 *  - spectra.csv          (SINGLE) one row per spectrum: time, GPS, settings, then one column per wavelength
 *  - <prefix>_NNNNN.txt   (MULTI)  one file per spectrum in the Pi daemon's text layout
 *  - fire.csv             every fire analysis made while logging (detections flagged)
 *  - detections.kml       track plus a placemark per confirmed detection, rewritten as they arrive
 *  - session.txt          device and settings snapshot
 */
class SessionLogger(
    root: File,
    private val device: DeviceInfo,
    private val settings: AppSettings,
    startMs: Long = System.currentTimeMillis(),
) {
    val dir: File = File(root, "session_" + stamp(startMs)).apply { mkdirs() }
    private var spectraCsv: BufferedWriter? = null
    private var fireCsv: BufferedWriter? = null
    private val track = ArrayList<GeoFix>()
    private val detections = ArrayList<Pair<Long, FireDetector.Result>>()
    private val detectionFixes = ArrayList<GeoFix?>()
    var spectraWritten = 0
        private set
    var detectionsWritten = 0
        private set

    init {
        File(dir, "session.txt").writeText(buildString {
            appendLine("PocketSpectro session ${iso(startMs)}")
            appendLine("Device: ${device.model}  serial ${device.serial}${if (device.simulated) "  (SIMULATED)" else ""}")
            appendLine("Pixels: ${device.pixels} (binning factor ${device.binningFactor})")
            appendLine("Wavelength coefficients: ${device.wavelengthCoefficients?.joinToString() ?: "none (pixel index used)"}")
            appendLine("Nonlinearity coefficients: ${device.nonlinearityCoefficients?.joinToString() ?: "none"}")
            appendLine("Integration: ${settings.integrationMs} ms  average: ${settings.scansToAverage}  boxcar: ${settings.boxcarWidth}")
            appendLine("Save mode: ${settings.saveMode.name}  interval: ${settings.sequenceIntervalSec} s  max: ${settings.maxAcquisitions}")
            val f = settings.fire
            appendLine("Fire: ${f.windowMode} windows, centres ${f.centre1Nm}/${f.centre2Nm} nm +/- ${f.halfWidthNm} nm, " +
                "pixels ${f.pixelWindow1}/${f.pixelWindow2}, threshold ${f.thresholdPercent} %, ${f.algorithm}, confirm ${f.confirmFrames}")
        })
    }

    fun writeSpectrum(s: LoggedSpectrum) {
        s.fix?.let { addTrackPoint(it) }
        when (settings.saveMode) {
            SaveMode.SINGLE -> {
                val w = spectraCsv ?: File(dir, "spectra.csv").bufferedWriter().also { w ->
                    w.write("time_utc,epoch_ms,latitude,longitude,altitude_m,accuracy_m,speed_ms,bearing_deg," +
                        "integration_us,scans_averaged,boxcar,dark_corrected,nl_corrected")
                    for (l in s.wavelengths) w.write(",%.3f".format(Locale.US, l))
                    w.newLine()
                    spectraCsv = w
                }
                w.write(iso(s.timeMs)); w.write(","); w.write(s.timeMs.toString()); w.write(",")
                w.write(fixColumns(s.fix)); w.write(",")
                w.write("${s.integrationUs},${s.scansAveraged},${s.boxcarWidth},${if (s.darkCorrected) 1 else 0},${if (s.nonlinearityCorrected) 1 else 0}")
                for (c in s.counts) w.write(",%.2f".format(Locale.US, c))
                w.newLine()
                w.flush()
            }
            SaveMode.MULTI -> {
                val name = "%s_%05d.txt".format(Locale.US, settings.filePrefix.ifBlank { "nir" }, spectraWritten + 1)
                File(dir, name).bufferedWriter().use { w ->
                    w.write("Spectrometer: ${device.model} ${device.serial}\n")
                    w.write("Saved at time: ${iso(s.timeMs)}\n")
                    w.write("Integration time: ${s.integrationUs}\n")
                    w.write("Scans to average: ${s.scansAveraged}\n")
                    w.write("Boxcar smoothing: ${s.boxcarWidth}\n")
                    s.fix?.let { w.write("Position: ${it.latitude}, ${it.longitude}, alt ${it.altitudeM ?: ""} m\n") }
                    w.write("Wavelengths\tIntensities\n")
                    for (i in s.wavelengths.indices) w.write("%.4f\t%.2f\n".format(Locale.US, s.wavelengths[i], s.counts[i]))
                }
            }
        }
        spectraWritten++
    }

    fun writeFire(timeMs: Long, r: FireDetector.Result, confirmed: Boolean, fix: GeoFix?) {
        fix?.let { addTrackPoint(it) }
        val w = fireCsv ?: File(dir, "fire.csv").bufferedWriter().also { w ->
            w.write("time_utc,epoch_ms,latitude,longitude,altitude_m,accuracy_m,speed_ms,bearing_deg," +
                "mean_floor,baseline_mode,peak1_nm,peak1_counts,peak1_baseline,peak1_pct,peak2_nm,peak2_counts,peak2_baseline,peak2_pct,confidence,snr1,snr2,fire,confirmed")
            w.newLine()
            fireCsv = w
        }
        w.write("${iso(timeMs)},$timeMs,${fixColumns(fix)},")
        w.write("%.1f,%s,%.3f,%.1f,%.1f,%.2f,%.3f,%.1f,%.1f,%.2f,%.1f,%.2f,%.2f,%d,%d".format(
            Locale.US, r.floor, r.baselineMode.name, r.window1.wavelengthNm, r.window1.intensity, r.window1.baseline, r.window1.percentAboveFloor,
            r.window2.wavelengthNm, r.window2.intensity, r.window2.baseline, r.window2.percentAboveFloor,
            r.confidence.score, r.confidence.snr1, r.confidence.snr2,
            if (r.fire) 1 else 0, if (confirmed) 1 else 0,
        ))
        w.newLine()
        w.flush()
        if (confirmed) {
            detections += timeMs to r
            detectionFixes += fix
            detectionsWritten++
            writeKml()
        }
    }

    fun close() {
        try { spectraCsv?.close() } catch (_: Exception) {}
        try { fireCsv?.close() } catch (_: Exception) {}
        if (track.isNotEmpty() || detections.isNotEmpty()) writeKml()
    }

    private fun addTrackPoint(f: GeoFix) {
        val last = track.lastOrNull()
        if (last == null || last.timeMs != f.timeMs) track += f
    }

    private fun writeKml() {
        File(dir, "detections.kml").writeText(Kml.build(dir.name, track, detections.indices.map { i ->
            Kml.Detection(detections[i].first, detectionFixes[i], detections[i].second)
        }))
    }

    private fun fixColumns(f: GeoFix?): String = if (f == null) ",,,,," else
        "%.7f,%.7f,%s,%s,%s,%s".format(
            Locale.US, f.latitude, f.longitude,
            f.altitudeM?.let { "%.1f".format(Locale.US, it) } ?: "",
            f.accuracyM?.let { "%.1f".format(Locale.US, it) } ?: "",
            f.speedMs?.let { "%.2f".format(Locale.US, it) } ?: "",
            f.bearingDeg?.let { "%.1f".format(Locale.US, it) } ?: "",
        )

    companion object {
        fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}

/** Minimal KML: survey track as a LineString and red placemarks for detections. */
object Kml {
    data class Detection(val timeMs: Long, val fix: GeoFix?, val result: FireDetector.Result)

    fun build(name: String, track: List<GeoFix>, detections: List<Detection>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${esc(name)}</name>""")
        appendLine("""<Style id="fire"><IconStyle><color>ff0000ff</color><scale>1.3</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/firedept.png</href></Icon></IconStyle></Style>""")
        appendLine("""<Style id="track"><LineStyle><color>ffff8800</color><width>3</width></LineStyle></Style>""")
        if (track.size >= 2) {
            append("""<Placemark><name>Track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>""")
            for (f in track) append("%.7f,%.7f,%.1f ".format(Locale.US, f.longitude, f.latitude, f.altitudeM ?: 0.0))
            appendLine("</coordinates></LineString></Placemark>")
        }
        for (d in detections) {
            val f = d.fix ?: continue
            val r = d.result
            appendLine("<Placemark><name>K+ ${"%.1f".format(Locale.US, r.maxPercent)} %</name><styleUrl>#fire</styleUrl>")
            appendLine("<TimeStamp><when>${SessionLogger.iso(d.timeMs)}</when></TimeStamp>")
            appendLine("<description>${esc("Peak 1 %.2f nm %.1f %%, peak 2 %.2f nm %.1f %% (vs %s)".format(Locale.US,
                r.window1.wavelengthNm, r.window1.percentAboveFloor, r.window2.wavelengthNm, r.window2.percentAboveFloor,
                if (r.baselineMode == FireDetector.Baseline.GLOBAL_MEAN) "spectrum mean" else "local continuum"))}</description>")
            appendLine("<Point><coordinates>%.7f,%.7f,%.1f</coordinates></Point></Placemark>".format(Locale.US, f.longitude, f.latitude, f.altitudeM ?: 0.0))
        }
        appendLine("</Document></kml>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
