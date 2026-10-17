package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.proc.FireDetector
import java.util.Locale

@Composable
fun FireScreen(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val running by engine.running.collectAsState()
    val armed by engine.fireArmed.collectAsState()
    val s by engine.store.settings.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    val sim = engine.simulator
    var simFire by remember(sim) { mutableStateOf(sim?.fireOn ?: false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        val r = frame?.fire
        val confirmed = frame?.fireConfirmed == true
        val (bg, title) = when {
            !running || r == null -> MaterialTheme.colorScheme.surfaceVariant to "Not acquiring"
            confirmed -> FireRed to "FIRE DETECTED"
            r.fire -> WarnAmber to "K+ signal, confirming…"
            else -> SafeGreen to "No fire"
        }
        Column(
            Modifier.fillMaxWidth().padding(12.dp).background(bg, RoundedCornerShape(16.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = if (bg == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White,
                textAlign = TextAlign.Center)
            if (r != null) {
                Text("K I emission ${"%.1f".format(Locale.US, r.maxPercent)} % above ${if (r.baselineMode == FireDetector.Baseline.LOCAL_CONTINUUM) "continuum" else "mean"} (threshold ${s.fire.thresholdPercent} %)",
                    color = if (bg == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val on = !(running && armed)
                engine.armFire(on)
                engine.setRunning(on)
            }) { Text(if (running && armed) "Stop monitoring" else "Start monitoring") }
            OutlinedButton(onClick = { engine.armFire(!armed) }) { Text(if (armed) "Alarm on" else "Alarm off") }
        }

        if (s.fire.baseline == FireDetector.Baseline.GLOBAL_MEAN) {
            Text("Pi rule active: the K I lines are compared with the mean of the whole spectrum, so a bright " +
                "continuum near 766 nm can be reported as fire with no emission lines present. Use Recommended for detection.",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(WarnAmber.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(10.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        val f = frame
        if (f != null && (f.wavelengths.first() > s.fire.centre1Nm || f.wavelengths.last() < s.fire.centre2Nm)) {
            Text("This spectrometer's range (%.0f–%.0f nm) does not cover the K I lines at 766–770 nm; fire detection is not meaningful."
                .format(Locale.US, f.wavelengths.first(), f.wavelengths.last()),
                Modifier.padding(12.dp), color = FireRed, style = MaterialTheme.typography.bodyMedium)
        }
        if (f != null && r != null) {
            val b1 = FireDetector.peakPixelRange(f.wavelengths, s.fire, 1)
            val b2 = FireDetector.peakPixelRange(f.wavelengths, s.fire, 2)
            val band = FireRed.copy(alpha = 0.15f)
            val flank = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
            val local = r.baselineMode == FireDetector.Baseline.LOCAL_CONTINUUM
            val lo = (f.wavelengths[minOf(b1.first, r.leftFlank.first)] - 12.0).coerceAtLeast(f.wavelengths.first())
            val hi = (f.wavelengths[maxOf(b2.last, r.rightFlank.last)] + 12.0).coerceAtMost(f.wavelengths.last())
            val y = if (s.fireUseDarkCorrected && f.darkCorrected) f.counts else f.meanRaw
            SpectrumChart(
                f.wavelengths, y, yUnit = "counts", height = 240.dp,
                xRange = if (showFull) null else lo..hi,
                bands = listOfNotNull(
                    Band(f.wavelengths[b1.first], f.wavelengths[b1.last], band),
                    Band(f.wavelengths[b2.first], f.wavelengths[b2.last], band),
                    if (local) Band(f.wavelengths[r.leftFlank.first], f.wavelengths[r.leftFlank.last], flank) else null,
                    if (local) Band(f.wavelengths[r.rightFlank.first], f.wavelengths[r.rightFlank.last], flank) else null,
                ),
                markers = listOf(r.window1.wavelengthNm, r.window2.wavelengthNm),
                hLine = if (local) null else r.floor,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { showFull = !showFull }) { Text(if (showFull) "Zoom to K lines" else "Show full range") }
            }
            Section("Potassium doublet") {
                KeyValue("Compared with", if (local) "local continuum (green flanks)" else "spectrum mean %.0f counts (Pi)".format(Locale.US, r.floor))
                WindowRow("Line 1", r.window1, s.fire.thresholdPercent)
                WindowRow("Line 2", r.window2, s.fire.thresholdPercent)
                KeyValue("Windows", if (s.fire.windowMode == FireDetector.WindowMode.WAVELENGTH)
                    "%.2f ± %.1f, %.2f ± %.1f nm".format(Locale.US, s.fire.centre1Nm, s.fire.halfWidthNm, s.fire.centre2Nm, s.fire.halfWidthNm)
                else "pixels ${b1.first}–${b1.last}, ${b2.first}–${b2.last} (Pi)")
                KeyValue("Peak finder", if (s.fire.algorithm == FireDetector.Algorithm.PI_IDENTICAL) "Pi-identical" else "Local maximum")
            }
            Section("Other maxima ≥ 1 % above spectrum mean") {
                val list = if (showAll) r.otherMaxima else r.otherMaxima.sortedByDescending { it.percentAboveFloor }.take(5)
                for (p in list) KeyValue("%.2f nm".format(Locale.US, p.wavelengthNm), "%.0f counts  (+%.1f %%)".format(Locale.US, p.intensity, p.percentAboveFloor))
                if (r.otherMaxima.size > 5) TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Show top 5" else "Show all ${r.otherMaxima.size}")
                }
            }
        }

        Section("Presets") {
            Text("Recommended: 200 ms, K I lines by wavelength, each line against the local continuum, 10 % threshold.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pi preset: exactly as the Pi ran it: one raw 200 ms scan (its daemon ignored the requested boxcar 3), " +
                "pixel windows 278–282 / 287–291, lines against the whole-spectrum mean. For comparing with old data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { engine.applyRecommendedFirePreset() }) { Text("Recommended") }
                OutlinedButton(onClick = { engine.applyPiFirePreset() }) { Text("Pi preset") }
            }
            if (sim != null) {
                SwitchRow("Simulate flame (K I doublet)", simFire, { simFire = it; sim.fireOn = it },
                    detail = "Simulator only: adds 766.5 / 769.9 nm emission")
            }
        }
    }
}

@Composable
private fun WindowRow(name: String, w: FireDetector.WindowResult, thr: Double) {
    val over = w.percentAboveFloor > thr
    KeyValue(name, "%.2f nm  %.0f counts  %+.1f %%%s".format(Locale.US, w.wavelengthNm, w.intensity, w.percentAboveFloor, if (over) "  ▲" else ""))
}
