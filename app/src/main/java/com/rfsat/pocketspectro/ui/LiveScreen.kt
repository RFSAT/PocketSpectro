package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.chipRes
import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.StoredSpectrum
import com.rfsat.pocketspectro.proc.DisplayMode
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val running by engine.running.collectAsState()
    val s by engine.store.settings.collectAsState()
    val dark by engine.dark.collectAsState()
    val ref by engine.reference.collectAsState()
    val shutter by engine.shutterAvailable.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in DisplayMode.entries) {
                ChoiceChip(selected = s.displayMode == m, label = stringResource(m.chipRes()),
                    onClick = { engine.store.update { it.copy(displayMode = m) } })
            }
        }

        val f = frame
        if (f != null) {
            SpectrumChart(f.wavelengths, f.display, yUnit = if (f.needsReference || f.mode == DisplayMode.COUNTS) stringResource(R.string.unit_counts) else f.mode.unit,
                modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            Text(stringResource(R.string.live_no_spectrum), Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Chips that change with every spectrum sit on their own row: a longer peak value or SATURATED
        // can never re-flow the chips below onto another line.
        if (f != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = {}, label = { FitText("%.1f ms × %d".format(Locale.US, f.integrationUs / 1000.0, f.scans)) })
            AssistChip(
                onClick = {},
                label = { FitText(if (f.saturated) stringResource(R.string.live_saturated) else stringResource(R.string.live_peak, f.maxRaw)) },
                colors = if (f.saturated) AssistChipDefaults.assistChipColors(containerColor = FireRed.copy(alpha = 0.25f))
                else AssistChipDefaults.assistChipColors(),
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f != null) {
                if (f.needsReference) AssistChip(onClick = {}, label = { Text(stringResource(R.string.live_needs_ref)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = WarnAmber.copy(alpha = 0.25f)))
                if (f.nlCorrected) AssistChip(onClick = {}, label = { Text(stringResource(R.string.live_nl_corrected)) })
            }
            AssistChip(onClick = {}, label = { Text(storedLabel(stringResource(R.string.live_dark), dark, s.integrationUs)) })
            AssistChip(onClick = {}, label = { Text(storedLabel(stringResource(R.string.live_ref), ref, s.integrationUs)) })
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ToggleButton(running, stringResource(if (running) R.string.live_acq_on else R.string.live_acq_off),
                { engine.setRunning(!running) }, icon = if (running) Icons.Filled.PlayArrow else Icons.Filled.Pause)
            ActionButton(onClick = { engine.singleShot() }, enabled = !running) { Text(stringResource(R.string.action_single)) }
        }

        Section(stringResource(R.string.live_sec_integration)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.field_integration), s.integrationMs, { v -> engine.store.update { it.copy(integrationMs = v) } },
                    Modifier.weight(1f), decimals = 2, min = 0.01, max = 85000.0, suffix = "ms")
                NumberField(stringResource(R.string.field_average), s.scansToAverage.toDouble(), { v -> engine.store.update { it.copy(scansToAverage = v.toInt()) } },
                    Modifier.weight(0.7f), min = 1.0, max = 5000.0)
                NumberField(stringResource(R.string.field_boxcar), s.boxcarWidth.toDouble(), { v -> engine.store.update { it.copy(boxcarWidth = v.toInt()) } },
                    Modifier.weight(0.7f), min = 0.0, max = 50.0)
            }
        }

        Section(stringResource(R.string.live_sec_dark_ref)) {
            Text(stringResource(R.string.live_dark_ref_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.captureDark(useShutter = false) }) { Text(stringResource(R.string.live_store_dark)) }
                ActionButton(onClick = { engine.captureDark(useShutter = true) }, enabled = shutter) { Text(stringResource(R.string.live_dark_shutter)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.captureReference() }, enabled = dark != null) { Text(stringResource(R.string.live_store_ref)) }
                ActionButton(onClick = { engine.clearDark(); engine.clearReference() }, enabled = dark != null || ref != null) { Text(stringResource(R.string.live_clear_both)) }
            }
        }
    }
}

@Composable
private fun storedLabel(name: String, s: StoredSpectrum?, currentUs: Long): String = when {
    s == null -> stringResource(R.string.stored_none, name)
    s.integrationUs != currentUs -> stringResource(R.string.stored_stale, name, (s.integrationUs / 1000.0).toString())
    else -> stringResource(R.string.stored_ok, name)
}
