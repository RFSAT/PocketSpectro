package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.ToolEvent
import com.rfsat.pocketspectro.tools.ToolId

/** What the open tool shows at the end of its page (the related tools); set by the Tools tab. */
val LocalToolFooter = androidx.compose.runtime.staticCompositionLocalOf<@Composable () -> Unit> { {} }

/** Scrolling page of a tool, ending with the footer (related tools). */
@Composable
fun ToolPage(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val footer = LocalToolFooter.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        content()
        footer()
    }
}

/** Acquisition ON/OFF and Single, at the top of every tool. */
@Composable
fun AcquisitionRow(engine: AcquisitionEngine) {
    val running by engine.running.collectAsState()
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ToggleButton(running, stringResource(if (running) R.string.live_acq_on else R.string.live_acq_off),
            { engine.setRunning(!running) }, icon = if (running) Icons.Filled.PlayArrow else Icons.Filled.Pause)
        ActionButton(onClick = { engine.singleShot() }, enabled = !running) { Text(stringResource(R.string.action_single)) }
    }
}

enum class RefKind { WHITE, BLANK, OPEN, DARK_ONLY }

/** Dark and reference status with the buttons to store them (white tile, or blank cuvette). */
@Composable
fun ReferencePanel(engine: AcquisitionEngine, kind: RefKind) {
    val dark by engine.dark.collectAsState()
    val ref by engine.reference.collectAsState()
    val shutter by engine.shutterAvailable.collectAsState()
    Section(stringResource(R.string.ref_title)) {
        Text(stringResource(when (kind) {
            RefKind.WHITE -> R.string.ref_help_white
            RefKind.BLANK -> R.string.ref_help_blank
            RefKind.OPEN -> R.string.ref_help_open
            RefKind.DARK_ONLY -> R.string.ref_help_dark
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyValue(stringResource(R.string.live_dark), stringResource(if (dark != null) R.string.ref_stored else R.string.ref_missing))
        if (kind != RefKind.DARK_ONLY) KeyValue(stringResource(when (kind) { RefKind.WHITE -> R.string.ref_white; RefKind.OPEN -> R.string.ref_open; else -> R.string.ref_blank }),
            stringResource(if (ref != null) R.string.ref_stored else R.string.ref_missing))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind != RefKind.DARK_ONLY) ActionButton(onClick = { engine.captureDarkAndReference() }, emphasis = true, enabled = shutter) {
                Text(stringResource(when (kind) { RefKind.WHITE -> R.string.ref_both_white; RefKind.OPEN -> R.string.ref_both_open; else -> R.string.ref_both_blank }))
            }
            ActionButton(onClick = { engine.captureDark(useShutter = true) }) { Text(stringResource(R.string.live_dark_shutter)) }
            ActionButton(onClick = { engine.captureDark(useShutter = false) }) { Text(stringResource(R.string.live_store_dark)) }
            if (kind != RefKind.DARK_ONLY) ActionButton(onClick = { engine.captureReference() }, enabled = dark != null) {
                Text(stringResource(when (kind) { RefKind.WHITE -> R.string.ref_store_white; RefKind.OPEN -> R.string.ref_store_open; else -> R.string.ref_store_blank }))
            }
        }
    }
}

/** Shown only with the simulator: what the simulated probe looks at. */
@Composable
fun SimPanel(engine: AcquisitionEngine, content: @Composable () -> Unit) {
    if (engine.simulator == null) return
    Section(stringResource(R.string.sim_panel_title)) {
        Text(stringResource(R.string.sim_panel_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Wrapping row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoices(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) ChoiceChip(selected == o, label(o), { onSelect(o) })
    }
}

/** Big result line, e.g. "NDVI 0.72". */
@Composable
fun BigValue(label: String, value: String, colour: Color = MaterialTheme.colorScheme.primary, size: TextUnit = 34.sp) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // One line, shrinking if needed: a longer result never pushes the screen down.
        FitText(value, color = colour, fontWeight = FontWeight.Bold, fontSize = size)
    }
}

/** What "Save to map" records: label, value, map colour (#RRGGBB) and detail text. */
data class SaveSpec(val label: String, val value: Double, val colour: String, val detail: String)

/** Records a result with the current GPS position on the map (Map tab). */
@Composable
fun SaveResultButton(engine: AcquisitionEngine, tool: ToolId, label: String, value: Double, colour: String, detail: String, enabled: Boolean = true) =
    SaveResultButton(engine, tool, if (enabled) SaveSpec(label, value, colour, detail) else null)

/**
 * "Save to map" with a photo option. The button is always shown, so the screen does not jump or flash
 * while results come and go between spectra: a result stays saveable for a few seconds after the
 * last spectrum that gave one. The position is the live GPS fix, or the last known one.
 */
@Composable
fun SaveResultButton(engine: AcquisitionEngine, tool: ToolId, spec: SaveSpec?) {
    val live by engine.location.fix.collectAsState()
    val cached = remember(live) { if (live == null) engine.location.lastKnown() else null }
    LaunchedEffect(Unit) { engine.location.start() }
    val fix = live ?: cached
    // Plain holder, not state: updating it while composing must not trigger another composition.
    val hold = remember { HeldSpec() }
    val now = System.currentTimeMillis()
    if (spec != null) { hold.spec = spec; hold.atMs = now }
    val current = spec ?: hold.spec?.takeIf { now - hold.atMs < HOLD_MS }
    var saved by remember(current?.label) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun save(photo: String) {
        val f = fix ?: return
        val c = current ?: return
        val model = (engine.connection.value as? com.rfsat.pocketspectro.core.Connection.Ready)?.info?.model ?: ""
        engine.tools.record(ToolEvent(System.currentTimeMillis(), f.latitude, f.longitude, f.accuracyM?.toDouble(),
            tool.name, c.label, c.value, c.colour, c.detail, model, photo))
        saved = true
    }
    // "With photo": the camera app takes the picture into events/photos, then the result is saved with it.
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val file = pending; pending = null
        if (ok && file != null && file.length() > 0) save(file.name) else file?.delete()
    }
    val can = current != null && fix != null
    val stale = fix != null && now - fix.timeMs > 120_000
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(onClick = { save("") }, enabled = can) { Text(stringResource(R.string.tool_save_result)) }
        androidx.compose.material3.OutlinedIconButton(onClick = {
            val file = java.io.File(engine.toolEvents.photoDir, "photo_%tY%<tm%<td_%<tH%<tM%<tS.jpg".format(java.util.Date()))
            pending = file
            try { camera.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)) } catch (_: Exception) { pending = null }
        }, enabled = can, border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.PhotoCamera, stringResource(R.string.tool_save_photo))
        }
        Text(when {
            fix == null -> stringResource(R.string.tool_save_no_gps)
            saved -> stringResource(R.string.tool_saved)
            current == null -> stringResource(R.string.tool_save_waiting)
            stale -> stringResource(R.string.tool_save_last_known, ((now - fix.timeMs) / 60_000).coerceAtLeast(2).toInt())
            else -> stringResource(R.string.tool_save_hint)
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class HeldSpec { var spec: SaveSpec? = null; var atMs = 0L }

/** How long a result stays saveable after the spectra stop giving one (noise near a threshold). */
private const val HOLD_MS = 5_000L

fun hex(rgb: IntArray) = "#%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])

/**
 * Scans a QR code or barcode (sample label, batch number) with the Google code scanner and hands
 * back its text; used as the trailing icon of sample-name fields.
 */
@Composable
fun ScanButton(onResult: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.IconButton(onClick = {
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context).startScan()
                .addOnSuccessListener { b -> b.rawValue?.takeIf { it.isNotBlank() }?.let { onResult(it.trim().take(60)) } }
        } catch (_: Exception) {}
    }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.QrCodeScanner, stringResource(R.string.scan_code)) }
}
