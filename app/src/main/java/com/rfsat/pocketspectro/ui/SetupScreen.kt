package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Spectrometer") {
            SwitchRow("Use simulator", s.useSimulator, { v -> engine.store.update { it.copy(useSimulator = v) }; onReconnect() },
                detail = "Simulated near-infrared spectrometer for trying the app without hardware")
            engine.simulator?.let { sim ->
                var sample by androidx.compose.runtime.remember(sim) { androidx.compose.runtime.mutableStateOf(sim.sampleOn) }
                SwitchRow("Simulated sample in beam", sample, { sample = it; sim.sampleOn = it },
                    detail = "Adds water / C-H absorption bands, for trying absorbance and %T")
            }
            KeyValue("Status", when (val c = conn) {
                is Connection.Ready -> "Connected"
                Connection.Connecting -> "Connecting…"
                Connection.AwaitingPermission -> "Waiting for USB permission"
                Connection.NoDevice -> "No spectrometer on USB"
                is Connection.Failed -> "Error: ${c.message}"
            })
            if (info != null) {
                KeyValue("Model / serial", "${info.model} / ${info.serial}")
                KeyValue("Pixels", "${info.pixels} (binning ${info.binningFactor}, max ${info.maxBinningFactor})")
                val wl = info.wavelengths()
                val plausible = Calibration.isPlausible(info.wavelengthCoefficients, info.unbinnedPixels)
                KeyValue("Range", if (plausible) "%.1f – %.1f nm".format(Locale.US, wl.first(), wl.last()) else "no valid wave-cal: pixel index")
                KeyValue("Wave-cal c0..c3", info.wavelengthCoefficients?.joinToString { "%.6g".format(Locale.US, it) } ?: "—")
                KeyValue("Nonlinearity k", info.nonlinearityCoefficients?.joinToString { "%.4g".format(Locale.US, it) } ?: "none programmed")
                KeyValue("K I 766.5 / 769.9 nm", "pixels ${Calibration.pixelFor(wl, FireDetector.K_LINE_1_NM)} / ${Calibration.pixelFor(wl, FireDetector.K_LINE_2_NM)}")
            }
            OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
        }

        Section("Acquisition") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Integration", s.integrationMs, { v -> engine.store.update { it.copy(integrationMs = v) } },
                    Modifier.weight(1f), decimals = 2, min = 0.01, max = 85000.0, suffix = "ms")
                NumberField("Average", s.scansToAverage.toDouble(), { v -> engine.store.update { it.copy(scansToAverage = v.toInt()) } },
                    Modifier.weight(0.7f), min = 1.0, max = 5000.0)
                NumberField("Boxcar", s.boxcarWidth.toDouble(), { v -> engine.store.update { it.copy(boxcarWidth = v.toInt()) } },
                    Modifier.weight(0.7f), min = 0.0, max = 50.0)
            }
            val maxBin = info?.maxBinningFactor ?: 3
            Picker("Pixel binning", (0..maxBin).toList(), s.binningFactor.coerceAtMost(maxBin),
                { b -> if (b == 0) "Off (1024 px)" else "×${1 shl b} (${1024 shr b} px)" },
                { b -> engine.store.update { it.copy(binningFactor = b) } })
            SwitchRow("Nonlinearity correction", s.applyNonlinearity, { v -> engine.store.update { it.copy(applyNonlinearity = v) } },
                detail = "Uses the unit's stored coefficients; applied once a dark is stored")
            SwitchRow("Keep screen on while acquiring", s.keepScreenOn, { v -> engine.store.update { it.copy(keepScreenOn = v) } })
        }

        if (info == null || info.fireCapable) Section("Fire detection") {
            val f = s.fire
            fun upd(c: (FireDetector.Config) -> FireDetector.Config) = engine.store.update { it.copy(fire = c(it.fire)) }
            Picker("Windows by", FireDetector.WindowMode.entries, f.windowMode,
                { if (it == FireDetector.WindowMode.WAVELENGTH) "Wavelength (K I lines)" else "Pixel (Pi windows)" },
                { m -> upd { it.copy(windowMode = m) } })
            if (f.windowMode == FireDetector.WindowMode.WAVELENGTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Line 1", f.centre1Nm, { v -> upd { it.copy(centre1Nm = v) } }, Modifier.weight(1f), 2, 300.0, 1200.0, "nm")
                    NumberField("Line 2", f.centre2Nm, { v -> upd { it.copy(centre2Nm = v) } }, Modifier.weight(1f), 2, 300.0, 1200.0, "nm")
                    NumberField("± width", f.halfWidthNm, { v -> upd { it.copy(halfWidthNm = v) } }, Modifier.weight(0.8f), 1, 0.2, 20.0, "nm")
                }
            } else {
                Text("Gradient indices as on the Pi; the candidate peak is the next pixel.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("W1 from", f.pixelWindow1.first.toDouble(), { v -> upd { it.copy(pixelWindow1 = v.toInt()..maxOf(v.toInt(), it.pixelWindow1.last)) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField("W1 to", f.pixelWindow1.last.toDouble(), { v -> upd { it.copy(pixelWindow1 = minOf(v.toInt(), it.pixelWindow1.first)..v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField("W2 from", f.pixelWindow2.first.toDouble(), { v -> upd { it.copy(pixelWindow2 = v.toInt()..maxOf(v.toInt(), it.pixelWindow2.last)) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                    NumberField("W2 to", f.pixelWindow2.last.toDouble(), { v -> upd { it.copy(pixelWindow2 = minOf(v.toInt(), it.pixelWindow2.first)..v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 1020.0)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Threshold", f.thresholdPercent, { v -> upd { it.copy(thresholdPercent = v) } }, Modifier.weight(1f), 1, 0.1, 1000.0, "%")
                NumberField("Confirm frames", f.confirmFrames.toDouble(), { v -> upd { it.copy(confirmFrames = v.toInt()) } }, Modifier.weight(1f), 0, 1.0, 100.0)
            }
            Picker("Peak finder", FireDetector.Algorithm.entries, f.algorithm,
                { if (it == FireDetector.Algorithm.PI_IDENTICAL) "Pi-identical (for comparison)" else "Local maximum (recommended)" },
                { a -> upd { it.copy(algorithm = a) } })
            Picker("Compare lines with", FireDetector.Baseline.entries, f.baseline,
                { if (it == FireDetector.Baseline.LOCAL_CONTINUUM) "Local continuum (recommended)" else "Whole-spectrum mean (Pi)" },
                { b -> upd { it.copy(baseline = b) } })
            SwitchRow("Use dark-corrected counts", s.fireUseDarkCorrected, { v -> engine.store.update { it.copy(fireUseDarkCorrected = v) } },
                detail = "Off matches the Pi (raw counts). The 10 % threshold was tuned on raw counts.")
            SwitchRow("Alarm sound", s.alarmSound, { v -> engine.store.update { it.copy(alarmSound = v) } })
            SwitchRow("Alarm vibration", s.alarmVibrate, { v -> engine.store.update { it.copy(alarmVibrate = v) } })
        }

    }
}
