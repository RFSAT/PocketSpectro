package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.stringResource
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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        val r = frame?.fire
        val confirmed = frame?.fireConfirmed == true
        val (bg, title) = when {
            !running || r == null -> MaterialTheme.colorScheme.surfaceVariant to stringResource(R.string.fire_not_acquiring)
            confirmed -> FireRed to stringResource(R.string.fire_detected)
            r.fire -> WarnAmber to stringResource(R.string.fire_confirming)
            else -> SafeGreen to stringResource(R.string.fire_none)
        }
        Column(
            Modifier.fillMaxWidth().padding(12.dp).background(bg, RoundedCornerShape(16.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = if (bg == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White,
                textAlign = TextAlign.Center)
            if (r != null && (confirmed || r.fire)) {
                Text(stringResource(R.string.fire_confidence, "%.0f".format(Locale.US, r.confidence.score)), fontWeight = FontWeight.SemiBold,
                    color = if (bg == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White)
            }
            if (r != null) {
                Text(stringResource(if (r.baselineMode == FireDetector.Baseline.LOCAL_CONTINUUM) R.string.fire_emission_continuum
                    else R.string.fire_emission_mean, "%.1f".format(Locale.US, r.maxPercent), s.fire.thresholdPercent.toString()),
                    color = if (bg == MaterialTheme.colorScheme.surfaceVariant) MaterialTheme.colorScheme.onSurface else Color.White)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val monitoring = running && armed
            ToggleButton(monitoring, stringResource(if (monitoring) R.string.fire_monitoring_on else R.string.fire_monitoring_off), {
                engine.armFire(!monitoring)
                engine.setRunning(!monitoring)
            })
            ToggleButton(armed, stringResource(if (armed) R.string.fire_alarm_on else R.string.fire_alarm_off), { engine.armFire(!armed) })
        }

        if (s.fire.baseline == FireDetector.Baseline.GLOBAL_MEAN) {
            Text(stringResource(R.string.fire_pi_warning),
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(WarnAmber.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(10.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        val f = frame
        if (f != null && (f.wavelengths.first() > s.fire.centre1Nm || f.wavelengths.last() < s.fire.centre2Nm)) {
            Text(stringResource(R.string.fire_range_warning,
                "%.0f".format(Locale.US, f.wavelengths.first()), "%.0f".format(Locale.US, f.wavelengths.last())),
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
                f.wavelengths, y, yUnit = stringResource(R.string.unit_counts), height = 240.dp,
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
                ActionButton(onClick = { showFull = !showFull }, compact = true) { Text(stringResource(if (showFull) R.string.fire_zoom_k else R.string.fire_show_full)) }
            }
            Section(stringResource(R.string.fire_sec_doublet)) {
                KeyValue(stringResource(R.string.fire_compared_with), if (local) stringResource(R.string.fire_cmp_local)
                    else stringResource(R.string.fire_cmp_mean, "%.0f".format(Locale.US, r.floor)))
                WindowRow(stringResource(R.string.fire_line, 1), r.window1, s.fire.thresholdPercent)
                WindowRow(stringResource(R.string.fire_line, 2), r.window2, s.fire.thresholdPercent)
                KeyValue(stringResource(R.string.fire_windows), if (s.fire.windowMode == FireDetector.WindowMode.WAVELENGTH)
                    "%.2f ± %.1f, %.2f ± %.1f nm".format(Locale.US, s.fire.centre1Nm, s.fire.halfWidthNm, s.fire.centre2Nm, s.fire.halfWidthNm)
                else stringResource(R.string.fire_windows_pixels, "${b1.first}–${b1.last}", "${b2.first}–${b2.last}"))
                val c = r.confidence
                KeyValue(stringResource(R.string.fire_confidence_label), stringResource(R.string.fire_confidence_detail,
                    "%.0f".format(Locale.US, c.score), "%.1f".format(Locale.US, c.snr1), "%.1f".format(Locale.US, c.snr2),
                    if (c.ratio.isFinite()) "%.2f".format(Locale.US, c.ratio) else "—"))
                KeyValue(stringResource(R.string.fire_peak_finder), stringResource(if (s.fire.algorithm == FireDetector.Algorithm.PI_IDENTICAL)
                    R.string.alg_pi_identical else R.string.alg_local_max))
            }
            Section(stringResource(if (s.fire.baseline == FireDetector.Baseline.GLOBAL_MEAN) R.string.fire_other_mean else R.string.fire_other_local)) {
                val list = if (showAll) r.otherMaxima else r.otherMaxima.sortedByDescending { it.percentAboveFloor }.take(5)
                for (p in list) KeyValue("%.2f nm".format(Locale.US, p.wavelengthNm), stringResource(R.string.fire_other_value,
                    "%.0f".format(Locale.US, p.intensity), "%.1f".format(Locale.US, p.percentAboveFloor)))
                if (r.otherMaxima.size > 5) ActionButton(onClick = { showAll = !showAll }, compact = true) {
                    Text(if (showAll) stringResource(R.string.fire_show_top5) else stringResource(R.string.fire_show_all, r.otherMaxima.size))
                }
            }
        }

        Section(stringResource(R.string.fire_sec_presets)) {
            Text(stringResource(R.string.fire_preset_rec_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.fire_preset_pi_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.applyRecommendedFirePreset() }) { Text(stringResource(R.string.fire_preset_rec)) }
                ActionButton(onClick = { engine.applyPiFirePreset() }) { Text(stringResource(R.string.fire_preset_pi)) }
            }
        }
        if (sim != null) SimFireControls(engine)
    }
}

@Composable
private fun WindowRow(name: String, w: FireDetector.WindowResult, thr: Double) {
    val over = w.percentAboveFloor > thr
    KeyValue(name, stringResource(R.string.fire_window_value, "%.2f".format(Locale.US, w.wavelengthNm),
        "%.0f".format(Locale.US, w.intensity), "%+.1f".format(Locale.US, w.percentAboveFloor)) + if (over) "  ▲" else "")
}
