package com.rfsat.pocketspectro.data

import java.io.File
import java.util.Locale

/**
 * A geo-tagged result posted by one of the tools (vegetation survey point, identified material,
 * concentration, colour, light measurement, emission-line alarm). Fire detections keep their own,
 * richer journal ([FireEventJournal]).
 */
data class ToolEvent(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    /** Tool id (ToolId name). */
    val tool: String,
    /** Short label, e.g. "NDVI 0.71" or "PET plastic". */
    val label: String,
    /** Main numeric value (NDVI, concentration, ΔE...), NaN when none. */
    val value: Double,
    /** Marker colour #RRGGBB. */
    val colour: String,
    /** Free text details (key: value; ...). */
    val detail: String,
    val model: String,
    /** Photo file name in events/photos (empty when none). */
    val photo: String = "",
)

/** files/events/tool_events.csv, appended by the tools; read by the Map. */
class ToolEventJournal(root: File) {
    val file = File(File(root, "events").apply { mkdirs() }, "tool_events.csv")

    @Synchronized
    fun record(e: ToolEvent) {
        // Files from before 1.7.0 lack the photo column: upgrade the header (old rows simply have no photo).
        if (file.exists()) file.useLines { it.firstOrNull() }?.let { head ->
            if (head != HEADER && !head.endsWith(",photo")) file.writeText(HEADER + "\n" + file.readLines().drop(1).joinToString("") { it + "\n" })
        }
        val newFile = !file.exists()
        file.appendText(buildString {
            if (newFile) append(HEADER).append('\n')
            append(listOf(SessionLogger.iso(e.timeMs), e.timeMs, "%.7f".format(Locale.US, e.latitude), "%.7f".format(Locale.US, e.longitude),
                e.accuracyM?.let { "%.1f".format(Locale.US, it) } ?: "", e.tool, q(e.label),
                if (e.value.isFinite()) "%.6g".format(Locale.US, e.value) else "", e.colour, q(e.detail), q(e.model), q(e.photo)).joinToString(","))
            append('\n')
        })
    }

    fun load(): List<ToolEvent> = if (!file.exists()) emptyList() else CsvTable.read(file).mapNotNull { r ->
        val lat = r["latitude"]?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = r["longitude"]?.toDoubleOrNull() ?: return@mapNotNull null
        ToolEvent(r["epoch_ms"]?.toLongOrNull() ?: 0L, lat, lon, r["accuracy_m"]?.toDoubleOrNull(), r["tool"] ?: "",
            r["label"] ?: "", r["value"]?.toDoubleOrNull() ?: Double.NaN, r["colour"]?.ifEmpty { null } ?: "#1E88E5",
            r["detail"] ?: "", r["model"] ?: "", r["photo"] ?: "")
    }

    fun clear() { file.delete(); photoDir.listFiles()?.forEach { it.delete() } }

    /** Folder for photos taken with results. */
    val photoDir: File get() = File(file.parentFile, "photos").apply { mkdirs() }

    private fun q(s: String) = "\"" + s.replace("\"", "'").replace("\n", " ") + "\""

    companion object {
        const val HEADER = "time_utc,epoch_ms,latitude,longitude,accuracy_m,tool,label,value,colour,detail,model,photo"
    }
}
