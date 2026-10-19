package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.Connection
import com.rfsat.pocketspectro.proc.Calibration
import com.rfsat.pocketspectro.proc.FireDetector
import java.util.Locale

@Composable
fun SetupScreen(engine: AcquisitionEngine, onReconnect: () -> Unit) {
    val s by engine.store.settings.collectAsState()
    val conn by engine.connection.collectAsState()
    val info = (conn as? Connection.Ready)?.info
    val res = androidx.compose.ui.platform.LocalResources.current

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section(stringResource(R.string.home_spectrometer)) {
            SwitchRow(stringResource(R.string.set_use_sim), s.useSimulator, { v -> engine.store.update { it.copy(useSimulator = v) }; onReconnect() },
                detail = stringResource(R.string.set_use_sim_detail))
            if (s.useSimulator) Picker(stringResource(R.string.set_sim_model), com.rfsat.pocketspectro.sim.SimModel.entries, s.simModel,
                { res.getString(R.string.set_sim_model_value, it.label) }, { m -> engine.store.update { it.copy(simModel = m) }; engine.connectSimulator(m) })
            engine.simulator?.let { sim ->
                var sample by androidx.compose.runtime.remember(sim) { androidx.compose.runtime.mutableStateOf(sim.sampleOn) }
                SwitchRow(stringResource(R.string.set_sim_sample), sample, { sample = it; sim.sampleOn = it },
                    detail = stringResource(R.string.set_sim_sample_detail))
            }
            KeyValue(stringResource(R.string.log_sec_status), when (val c = conn) {
                is Connection.Ready -> stringResource(R.string.set_connected)
                Connection.Connecting -> stringResource(R.string.conn_connecting)
                Connection.AwaitingPermission -> stringResource(R.string.home_awaiting)
                Connection.NoDevice -> stringResource(R.string.set_no_usb)
                is Connection.Failed -> stringResource(R.string.set_error, c.message)
            })
            if (info != null) {
                KeyValue(stringResource(R.string.set_model_serial), "${info.model} / ${info.serial}")
                KeyValue(stringResource(R.string.set_pixels), stringResource(R.string.set_pixels_value, info.pixels, info.binningFactor, info.maxBinningFactor))
                val wl = info.wavelengths()
                val plausible = Calibration.isPlausible(info.wavelengthCoefficients, info.unbinnedPixels)
                KeyValue(stringResource(R.string.home_range), if (plausible) "%.1f – %.1f nm".format(Locale.US, wl.first(), wl.last()) else stringResource(R.string.set_no_wavecal))
                KeyValue(stringResource(R.string.set_wavecal), info.wavelengthCoefficients?.joinToString { "%.6g".format(Locale.US, it) } ?: "—")
                KeyValue(stringResource(R.string.set_nl_k), info.nonlinearityCoefficients?.joinToString { "%.4g".format(Locale.US, it) } ?: stringResource(R.string.set_nl_none))
                KeyValue("K I 766.5 / 769.9 nm", stringResource(R.string.set_k_pixels, Calibration.pixelFor(wl, FireDetector.K_LINE_1_NM), Calibration.pixelFor(wl, FireDetector.K_LINE_2_NM)))
            }
            ActionButton(onClick = onReconnect) { Text(stringResource(R.string.set_reconnect)) }
        }

        Section(stringResource(R.string.set_sec_acq)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.field_integration), s.integrationMs, { v -> engine.store.update { it.copy(integrationMs = v) } },
                    Modifier.weight(1f), decimals = 2, min = 0.01, max = 85000.0, suffix = "ms")
                NumberField(stringResource(R.string.field_average), s.scansToAverage.toDouble(), { v -> engine.store.update { it.copy(scansToAverage = v.toInt()) } },
                    Modifier.weight(0.7f), min = 1.0, max = 5000.0)
                NumberField(stringResource(R.string.field_boxcar), s.boxcarWidth.toDouble(), { v -> engine.store.update { it.copy(boxcarWidth = v.toInt()) } },
                    Modifier.weight(0.7f), min = 0.0, max = 50.0)
            }
            val maxBin = info?.maxBinningFactor ?: 3
            Picker(stringResource(R.string.set_binning), (0..maxBin).toList(), s.binningFactor.coerceAtMost(maxBin),
                { b -> if (b == 0) res.getString(R.string.set_binning_off) else "×${1 shl b} (${1024 shr b} px)" },
                { b -> engine.store.update { it.copy(binningFactor = b) } })
            SwitchRow(stringResource(R.string.set_nl), s.applyNonlinearity, { v -> engine.store.update { it.copy(applyNonlinearity = v) } },
                detail = stringResource(R.string.set_nl_detail))
            SwitchRow(stringResource(R.string.set_screen_on), s.keepScreenOn, { v -> engine.store.update { it.copy(keepScreenOn = v) } })
        }

        if (info == null || info.fireCapable) Section(stringResource(R.string.alert_title)) {
            Text(stringResource(R.string.alert_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var url by androidx.compose.runtime.remember(s.alertUrl) { androidx.compose.runtime.mutableStateOf(s.alertUrl) }
            androidx.compose.material3.OutlinedTextField(url, { v -> url = v.trim(); engine.store.update { it.copy(alertUrl = v.trim()) } }, singleLine = true,
                label = { Text(stringResource(R.string.alert_url)) }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
            ActionButton(onClick = { engine.sendTestAlert() }, enabled = s.alertUrl.startsWith("http")) { Text(stringResource(R.string.alert_test)) }
        }

        if (info == null || info.fireCapable) Section(stringResource(R.string.home_btn_fire)) {
            val f = s.fire
            fun upd(c: (FireDetector.Config) -> FireDetector.Config) = engine.store.update { it.copy(fire = c(it.fire)) }
            Picker(stringResource(R.string.set_windows_by), FireDetector.WindowMode.entries, f.windowMode,
                { res.getString(if (it == FireDetector.WindowMode.WAVELENGTH) R.string.set_win_wavelength else R.string.set_win_pixel) },
                { m -> upd { it.copy(windowMode = m) } })
            if (f.windowMode == FireDetector.WindowMode.WAVELENGTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.fire_line, 1), f.centre1Nm, { v -> upd { it.copy(centre1Nm = v) } }, Modifier.weight(1f), 2, 300.0, 1200.0, "nm")
                    NumberField(stringResource(R.string.fire_line, 2), f.centre2Nm, { v -> upd { it.copy(centre2Nm = v) } }, Modifier.weight(1f), 2, 300.0, 1200.0, "nm")
                    NumberField(stringResource(R.string.set_half_width), f.halfWidthNm, { v -> upd { it.copy(halfWidthNm = v) } }, Modifier.weight(0.8f), 1, 0.2, 20.0, "nm")
                }
            } else {
                Text(stringResource(R.string.set_gradient_help), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.set_w_from, 1), f.pixelWindow1.first.toDouble(), { v -> upd { it.copy(pixelWindow1 = v.toInt()..maxOf(v.toInt(), it.pixelWindow1.last)) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField(stringResource(R.string.set_w_to, 1), f.pixelWindow1.last.toDouble(), { v -> upd { it.copy(pixelWindow1 = minOf(v.toInt(), it.pixelWindow1.first)..v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField(stringResource(R.string.set_w_from, 2), f.pixelWindow2.first.toDouble(), { v -> upd { it.copy(pixelWindow2 = v.toInt()..maxOf(v.toInt(), it.pixelWindow2.last)) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField(stringResource(R.string.set_w_to, 2), f.pixelWindow2.last.toDouble(), { v -> upd { it.copy(pixelWindow2 = minOf(v.toInt(), it.pixelWindow2.first)..v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.set_threshold), f.thresholdPercent, { v -> upd { it.copy(thresholdPercent = v) } }, Modifier.weight(1f), 1, 0.1, 1000.0, "%")
                NumberField(stringResource(R.string.set_confirm_frames), f.confirmFrames.toDouble(), { v -> upd { it.copy(confirmFrames = v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 100.0)
            }
            Picker(stringResource(R.string.fire_peak_finder), FireDetector.Algorithm.entries, f.algorithm,
                { res.getString(if (it == FireDetector.Algorithm.PI_IDENTICAL) R.string.set_alg_pi else R.string.set_alg_local) },
                { a -> upd { it.copy(algorithm = a) } })
            Picker(stringResource(R.string.set_compare_with), FireDetector.Baseline.entries, f.baseline,
                { res.getString(if (it == FireDetector.Baseline.LOCAL_CONTINUUM) R.string.set_base_local else R.string.set_base_mean) },
                { b -> upd { it.copy(baseline = b) } })
            SwitchRow(stringResource(R.string.set_dark_corrected), s.fireUseDarkCorrected, { v -> engine.store.update { it.copy(fireUseDarkCorrected = v) } },
                detail = stringResource(R.string.set_dark_corrected_detail))
            SwitchRow(stringResource(R.string.set_avoid_o2), s.fire.avoidOxygenBand, { v -> engine.store.update { it.copy(fire = it.fire.copy(avoidOxygenBand = v)) } },
                detail = stringResource(R.string.set_avoid_o2_detail))
            SwitchRow(stringResource(R.string.set_alarm_sound), s.alarmSound, { v -> engine.store.update { it.copy(alarmSound = v) } })
            SwitchRow(stringResource(R.string.set_alarm_vibration), s.alarmVibrate, { v -> engine.store.update { it.copy(alarmVibrate = v) } })
        }

    }
}
