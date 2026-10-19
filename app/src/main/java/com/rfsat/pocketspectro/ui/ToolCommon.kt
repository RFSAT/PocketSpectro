package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

enum class RefKind { WHITE, BLANK, DARK_ONLY }

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
            RefKind.DARK_ONLY -> R.string.ref_help_dark
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyValue(stringResource(R.string.live_dark), stringResource(if (dark != null) R.string.ref_stored else R.string.ref_missing))
        if (kind != RefKind.DARK_ONLY) KeyValue(stringResource(if (kind == RefKind.WHITE) R.string.ref_white else R.string.ref_blank),
            stringResource(if (ref != null) R.string.ref_stored else R.string.ref_missing))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (kind != RefKind.DARK_ONLY) ActionButton(onClick = { engine.captureDarkAndReference() }, emphasis = true, enabled = shutter) {
                Text(stringResource(if (kind == RefKind.WHITE) R.string.ref_both_white else R.string.ref_both_blank))
            }
            ActionButton(onClick = { engine.captureDark(useShutter = true) }) { Text(stringResource(R.string.live_dark_shutter)) }
            ActionButton(onClick = { engine.captureDark(useShutter = false) }) { Text(stringResource(R.string.live_store_dark)) }
            if (kind != RefKind.DARK_ONLY) ActionButton(onClick = { engine.captureReference() }, enabled = dark != null) {
                Text(stringResource(if (kind == RefKind.WHITE) R.string.ref_store_white else R.string.ref_store_blank))
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
        Text(value, fontSize = size, fontWeight = FontWeight.Bold, color = colour)
    }
}

/** Records a result with the current GPS position on the map (Map tab). */
@Composable
fun SaveResultButton(engine: AcquisitionEngine, tool: ToolId, label: String, value: Double, colour: String, detail: String, enabled: Boolean = true) {
    val fix by engine.location.fix.collectAsState()
    var saved by remember(label) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(onClick = {
            val f = fix ?: return@ActionButton
            val model = (engine.connection.value as? com.rfsat.pocketspectro.core.Connection.Ready)?.info?.model ?: ""
            engine.tools.record(ToolEvent(System.currentTimeMillis(), f.latitude, f.longitude, f.accuracyM?.toDouble(),
                tool.name, label, value, colour, detail, model))
            saved = true
        }, enabled = enabled && fix != null) { Text(stringResource(R.string.tool_save_result)) }
        Text(stringResource(when {
            fix == null -> R.string.tool_save_no_gps
            saved -> R.string.tool_saved
            else -> R.string.tool_save_hint
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun hex(rgb: IntArray) = "#%02X%02X%02X".format(rgb[0], rgb[1], rgb[2])
