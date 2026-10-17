package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.SaveMode
import java.util.Locale

/** Log tab: recording controls and the saved sessions (formerly the Results tab). */
@Composable
fun LogTab(engine: AcquisitionEngine, onRequestLocation: () -> Unit, initialSessions: Boolean = false) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(if (initialSessions) 1 else 0) }
    Column {
        androidx.compose.material3.SecondaryTabRow(selectedTabIndex = page) {
            androidx.compose.material3.Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("Recording") })
            androidx.compose.material3.Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("Sessions") })
        }
        if (page == 0) LogScreen(engine, onRequestLocation) else ResultsScreen(engine)
    }
}

@Composable
fun LogScreen(engine: AcquisitionEngine, onRequestLocation: () -> Unit) {
    val s by engine.store.settings.collectAsState()
    val log by engine.log.collectAsState()
    val frame by engine.frame.collectAsState()
    val fix by engine.location.fix.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Status") {
            if (log.active) {
                KeyValue("State", if (log.paused) "Paused" else "Logging")
                KeyValue("Spectra saved", if (log.max > 0) "${log.spectra} of ${log.max}" else "${log.spectra}")
                KeyValue("Fire detections", "${log.detections}")
                KeyValue("Folder", log.folder ?: "")
            } else {
                Text("Not logging. Settings below apply to the next session.", style = MaterialTheme.typography.bodySmall)
            }
            KeyValue("GPS", when {
                !s.gpsTagging -> "tagging off"
                !engine.location.hasPermission() -> "no permission"
                fix == null -> "waiting for fix…"
                else -> "%.6f, %.6f  ±%s m".format(Locale.US, fix!!.latitude, fix!!.longitude,
                    fix!!.accuracyM?.let { "%.0f".format(Locale.US, it) } ?: "?")
            })
            if (s.gpsTagging && !engine.location.hasPermission()) {
                OutlinedButton(onClick = onRequestLocation) { Text("Allow location") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!log.active) {
                    Button(onClick = { engine.startLog() }, enabled = frame != null || engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready) {
                        Text("Start logging")
                    }
                } else {
                    OutlinedButton(onClick = {
                        if (log.paused) { engine.setRunning(true); engine.pauseLog(false) } else engine.pauseLog(true)
                    }) { Text(if (log.paused) "Resume" else "Pause") }
                    Button(onClick = { engine.stopLog() }) { Text("Stop") }
                }
            }
        }

        Section("Acquisition control") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Interval", s.sequenceIntervalSec, { v -> engine.store.update { it.copy(sequenceIntervalSec = v) } },
                    Modifier.weight(1f), decimals = 1, min = 0.0, max = 86400.0, suffix = "s")
                NumberField("Max spectra", s.maxAcquisitions.toDouble(), { v -> engine.store.update { it.copy(maxAcquisitions = v.toInt()) } },
                    Modifier.weight(1f), min = 0.0, max = 1_000_000.0)
            }
            Text("Interval 0 saves every spectrum; max 0 means no limit.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Picker("Save mode", SaveMode.entries, s.saveMode, { it.label }, { m -> engine.store.update { it.copy(saveMode = m) } })
            OutlinedTextField(
                value = s.filePrefix,
                onValueChange = { v -> engine.store.update { it.copy(filePrefix = v.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(24)) } },
                label = { Text("File prefix (per-spectrum mode)") },
                singleLine = true,
            )
            SwitchRow("Tag with GPS position", s.gpsTagging, { v -> engine.store.update { it.copy(gpsTagging = v) } })
        }

        Section("What is written") {
            Text(
                "Each session gets its own folder with spectra.csv (or one text file per spectrum), fire.csv with the " +
                    "K-line analysis of every saved spectrum, detections.kml (track and confirmed detections, opens in " +
                    "Google Earth) and session.txt with the device calibration and settings. Logged counts are the " +
                    "processed counts shown on the Live screen (dark, nonlinearity and boxcar as flagged per row).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
