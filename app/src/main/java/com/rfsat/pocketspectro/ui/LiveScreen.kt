package com.rfsat.pocketspectro.ui

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
                FilterChip(
                    selected = s.displayMode == m,
                    onClick = { engine.store.update { it.copy(displayMode = m) } },
                    label = { Text(if (m == DisplayMode.TRANSMITTANCE) "%T / %R" else m.label) },
                )
            }
        }

        val f = frame
        if (f != null) {
            SpectrumChart(f.wavelengths, f.display, yUnit = if (f.needsReference) "counts" else f.mode.unit,
                modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            Text("No spectrum yet. Press Run or Single.", Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f != null) {
                AssistChip(onClick = {}, label = { Text("%.1f ms × %d".format(Locale.US, f.integrationUs / 1000.0, f.scans)) })
                AssistChip(
                    onClick = {},
                    label = { Text(if (f.saturated) "SATURATED" else "peak ${f.maxRaw}") },
                    colors = if (f.saturated) AssistChipDefaults.assistChipColors(containerColor = FireRed.copy(alpha = 0.25f))
                    else AssistChipDefaults.assistChipColors(),
                )
                if (f.needsReference) AssistChip(onClick = {}, label = { Text("needs reference") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = WarnAmber.copy(alpha = 0.25f)))
                if (f.nlCorrected) AssistChip(onClick = {}, label = { Text("NL corrected") })
            }
            AssistChip(onClick = {}, label = { Text(storedLabel("Dark", dark, s.integrationUs)) })
            AssistChip(onClick = {}, label = { Text(storedLabel("Ref", ref, s.integrationUs)) })
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { engine.setRunning(!running) }) {
                Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                Text(if (running) " Stop" else " Run")
            }
            OutlinedButton(onClick = { engine.singleShot() }, enabled = !running) { Text("Single") }
        }

        Section("Integration and averaging") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Integration", s.integrationMs, { v -> engine.store.update { it.copy(integrationMs = v) } },
                    Modifier.weight(1f), decimals = 2, min = 0.01, max = 85000.0, suffix = "ms")
                NumberField("Average", s.scansToAverage.toDouble(), { v -> engine.store.update { it.copy(scansToAverage = v.toInt()) } },
                    Modifier.weight(0.7f), min = 1.0, max = 5000.0)
                NumberField("Boxcar", s.boxcarWidth.toDouble(), { v -> engine.store.update { it.copy(boxcarWidth = v.toInt()) } },
                    Modifier.weight(0.7f), min = 0.0, max = 50.0)
            }
        }

        Section("Dark and reference") {
            Text("Dark: block the light path (or use the built-in shutter), then store. Reference: point at the white/blank " +
                "standard. Absorbance and %T/%R need both, taken at the current integration time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { engine.captureDark(useShutter = false) }) { Text("Store dark") }
                OutlinedButton(onClick = { engine.captureDark(useShutter = true) }, enabled = shutter) { Text("Dark (shutter)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { engine.captureReference() }, enabled = dark != null) { Text("Store reference") }
                TextButton(onClick = { engine.clearDark(); engine.clearReference() }) { Text("Clear both") }
            }
        }
    }
}

private fun storedLabel(name: String, s: StoredSpectrum?, currentUs: Long): String = when {
    s == null -> "$name: none"
    s.integrationUs != currentUs -> "$name: stale (${s.integrationUs / 1000.0} ms)"
    else -> "$name: ✓"
}
