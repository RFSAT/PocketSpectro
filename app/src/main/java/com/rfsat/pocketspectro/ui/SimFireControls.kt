package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.sim.SimFireMode
import java.util.Locale
import kotlin.math.ceil

/**
 * Simulator only: inject fire incidents by hand (a burst, or a flame held on) or let them occur at
 * random. Detections they cause go through the normal detector, alarm, journal and map.
 */
@Composable
fun SimFireControls(engine: AcquisitionEngine) {
    val s by engine.store.settings.collectAsState()
    val st by engine.simFire.collectAsState()
    val running by engine.running.collectAsState()
    val cfg = s.simFire
    fun update(change: (com.rfsat.pocketspectro.sim.SimFireConfig) -> com.rfsat.pocketspectro.sim.SimFireConfig) =
        engine.store.update { it.copy(simFire = change(it.simFire)) }

    Section(stringResource(R.string.simfire_title)) {
        Text(stringResource(R.string.simfire_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in SimFireMode.entries) {
                ChoiceChip(
                    selected = cfg.mode == m,
                    label = stringResource(when (m) {
                        SimFireMode.OFF -> R.string.simfire_off
                        SimFireMode.MANUAL -> R.string.simfire_manual
                        SimFireMode.RANDOM -> R.string.simfire_random
                    }),
                    onClick = {
                        if (m == SimFireMode.OFF) engine.stopSimFire()
                        update { it.copy(mode = m) }
                    },
                )
            }
        }

        if (cfg.mode != SimFireMode.OFF) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cfg.mode == SimFireMode.RANDOM) {
                    NumberField(stringResource(R.string.simfire_interval), cfg.meanIntervalSec, { v -> update { it.copy(meanIntervalSec = v) } },
                        Modifier.weight(1f), 0, 5.0, 86_400.0, "s")
                }
                NumberField(stringResource(R.string.simfire_duration), cfg.durationSec, { v -> update { it.copy(durationSec = v) } },
                    Modifier.weight(1f), 0, 1.0, 3600.0, "s")
                NumberField(stringResource(R.string.simfire_strength), cfg.strength, { v -> update { it.copy(strength = v) } },
                    Modifier.weight(1f), 2, 0.0, 10.0, "×")
            }
            Text(stringResource(if (cfg.mode == SimFireMode.RANDOM) R.string.simfire_random_help else R.string.simfire_strength_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (cfg.mode == SimFireMode.MANUAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.injectSimFire() }, emphasis = true) { Text(stringResource(R.string.simfire_inject)) }
                ActionButton(onClick = { engine.stopSimFire() }, enabled = st.active) { Text(stringResource(R.string.action_stop)) }
            }
            SwitchRow(stringResource(R.string.simfire_hold), st.held, { engine.holdSimFire(it) },
                detail = stringResource(R.string.simfire_hold_detail))
        }

        if (cfg.mode != SimFireMode.OFF) {
            val now = System.currentTimeMillis()
            fun secs(t: Long) = ceil((t - now).coerceAtLeast(0L) / 1000.0).toInt()
            val stateText = when {
                st.held -> stringResource(R.string.simfire_state_held)
                st.endsAtMs != null -> pluralStringResource(R.plurals.simfire_state_burst, secs(st.endsAtMs!!), secs(st.endsAtMs!!))
                st.nextAtMs != null && running -> pluralStringResource(R.plurals.simfire_state_next, secs(st.nextAtMs!!), secs(st.nextAtMs!!))
                cfg.mode == SimFireMode.RANDOM -> stringResource(R.string.simfire_state_waiting_run)
                else -> stringResource(R.string.simfire_state_idle)
            }
            KeyValue(stringResource(R.string.log_sec_status), stateText)
            if (st.active) KeyValue(stringResource(R.string.simfire_level), "%.2f ×".format(Locale.US, st.level))
            KeyValue(stringResource(R.string.simfire_count), st.incidents.toString())
            if (st.active) Text(stringResource(R.string.simfire_emitting), color = FireRed, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
