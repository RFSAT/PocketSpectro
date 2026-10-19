package com.rfsat.pocketspectro.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fire-watch alerts: posts a small JSON message to a web address (a server, a chat webhook, a home
 * automation hub) for every confirmed fire, so an unattended phone with a spectrometer can act as a
 * remote fire-watch station, like the original Wi-Fi Raspberry Pi detector.
 */
object AlertSender {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "alerts").apply { isDaemon = true } }

    data class Alert(
        val kind: String, val timeMs: Long, val lat: Double?, val lon: Double?, val accuracyM: Double?,
        val confidence: Double?, val percentAbove: Double?, val temperatureK: Double?, val model: String, val serial: String, val simulated: Boolean,
    )

    fun json(a: Alert): String {
        fun n(v: Double?, d: Int) = if (v == null || !v.isFinite()) "null" else "%.${d}f".format(Locale.US, v)
        return buildString {
            append("{\"event\":\"").append(a.kind).append("\",")
            append("\"time\":\"").append(com.rfsat.pocketspectro.data.SessionLogger.iso(a.timeMs)).append("\",")
            append("\"epoch_ms\":").append(a.timeMs).append(',')
            append("\"latitude\":").append(n(a.lat, 7)).append(",\"longitude\":").append(n(a.lon, 7)).append(',')
            append("\"accuracy_m\":").append(n(a.accuracyM, 1)).append(',')
            append("\"confidence_percent\":").append(n(a.confidence, 0)).append(',')
            append("\"k_emission_percent\":").append(n(a.percentAbove, 1)).append(',')
            append("\"flame_temperature_k\":").append(n(a.temperatureK, 0)).append(',')
            append("\"spectrometer\":\"").append(a.model.replace("\"", "'")).append("\",")
            append("\"serial\":\"").append(a.serial.replace("\"", "'")).append("\",")
            append("\"simulated\":").append(a.simulated).append(',')
            append("\"app\":\"PocketSpectro\"}")
        }
    }

    /** Sends in the background; [done] gets the HTTP status (or -1 on a network error). */
    fun send(url: String, a: Alert, done: (Int) -> Unit = {}) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) { done(-1); return }
        val body = json(a).toByteArray()
        executor.execute {
            val code = try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body) }
                    val c = responseCode; disconnect(); c
                }
            } catch (_: Exception) { -1 }
            done(code)
        }
    }
}
