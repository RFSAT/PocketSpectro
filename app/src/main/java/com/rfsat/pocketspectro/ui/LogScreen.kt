package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.labelRes
import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.stringResource
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
            androidx.compose.material3.Tab(selected = page == 0, onClick = { page = 0 }, text = { Text(stringResource(R.string.log_tab_recording)) })
            androidx.compose.material3.Tab(selected = page == 1, onClick = { page = 1 }, text = { Text(stringResource(R.string.log_tab_sessions)) })
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
    val conn by engine.connection.collectAsState()
    val res = androidx.compose.ui.platform.LocalResources.current

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section(stringResource(R.string.log_sec_status)) {
            if (log.active) {
                KeyValue(stringResource(R.string.home_state), stringResource(if (log.paused) R.string.log_paused else R.string.log_logging))
                KeyValue(stringResource(R.string.log_spectra_saved),
                    if (log.max > 0) stringResource(R.string.log_n_of_max, log.spectra, log.max) else "${log.spectra}")
                KeyValue(stringResource(R.string.log_fire_detections), "${log.detections}")
                KeyValue(stringResource(R.string.log_folder), log.folder ?: "")
            } else {
                Text(stringResource(R.string.log_not_logging), style = MaterialTheme.typography.bodySmall)
            }
            KeyValue(stringResource(R.string.log_gps), when {
                !s.gpsTagging -> stringResource(R.string.gps_off)
                !engine.location.hasPermission() -> stringResource(R.string.gps_no_permission)
                fix == null -> stringResource(R.string.gps_waiting)
                else -> "%.6f, %.6f  ±%s m".format(Locale.US, fix!!.latitude, fix!!.longitude,
                    fix!!.accuracyM?.let { "%.0f".format(Locale.US, it) } ?: "?")
            })
            if (s.gpsTagging && !engine.location.hasPermission()) {
                OutlinedButton(onClick = onRequestLocation) { Text(stringResource(R.string.log_allow_location)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!log.active) {
                    Button(onClick = { engine.startLog() }, enabled = frame != null || conn is com.rfsat.pocketspectro.core.Connection.Ready) {
                        Text(stringResource(R.string.log_start))
                    }
                } else {
                    OutlinedButton(onClick = {
                        if (log.paused) { engine.setRunning(true); engine.pauseLog(false) } else engine.pauseLog(true)
                    }) { Text(stringResource(if (log.paused) R.string.log_resume else R.string.log_pause)) }
                    Button(onClick = { engine.stopLog() }) { Text(stringResource(R.string.action_stop)) }
                }
            }
        }

        Section(stringResource(R.string.log_sec_acq)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.log_interval), s.sequenceIntervalSec, { v -> engine.store.update { it.copy(sequenceIntervalSec = v) } },
                    Modifier.weight(1f), decimals = 1, min = 0.0, max = 86400.0, suffix = "s")
                NumberField(stringResource(R.string.log_max_spectra), s.maxAcquisitions.toDouble(), { v -> engine.store.update { it.copy(maxAcquisitions = v.toInt()) } },
                    Modifier.weight(1f), min = 0.0, max = 1_000_000.0)
            }
            Text(stringResource(R.string.log_interval_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Picker(stringResource(R.string.log_save_mode), SaveMode.entries, s.saveMode, { res.getString(it.labelRes()) }, { m -> engine.store.update { it.copy(saveMode = m) } })
            OutlinedTextField(
                value = s.filePrefix,
                onValueChange = { v -> engine.store.update { it.copy(filePrefix = v.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(24)) } },
                label = { Text(stringResource(R.string.log_file_prefix)) },
                singleLine = true,
            )
            SwitchRow(stringResource(R.string.log_tag_gps), s.gpsTagging, { v -> engine.store.update { it.copy(gpsTagging = v) } })
        }

        Section(stringResource(R.string.log_sec_written)) {
            Text(
                stringResource(R.string.log_written_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
