@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.rfsat.pocketspectro.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rfsat.pocketspectro.BuildConfig
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.ExposureLog
import com.rfsat.pocketspectro.sim.SimClock
import com.rfsat.pocketspectro.sim.SimOptics
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.tools.LightExposure
import com.rfsat.pocketspectro.tools.SkinOptics
import com.rfsat.pocketspectro.tools.Solar
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.UvDose
import com.rfsat.pocketspectro.tools.UvIndex
import com.rfsat.pocketspectro.tools.ratio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

// ================================= Wellness disclaimer ======================================

/**
 * The exposure and physiology tools are for wellness, education and research, not medical use: they
 * open only after the user has read and accepted the notice once (Google Play health-app policy).
 */
@Composable
fun WellnessGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tools", Context.MODE_PRIVATE) }
    var ack by remember { mutableStateOf(prefs.getBoolean("healthAck", false)) }
    if (ack) { content(); return }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wellness_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.wellness_text), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.wellness_research), style = MaterialTheme.typography.bodySmall)
        ActionButton(onClick = { prefs.edit().putBoolean("healthAck", true).apply(); ack = true }, emphasis = true) { Text(stringResource(R.string.wellness_accept)) }
    }
}

/** Short reminder at the top of each exposure tool. */
@Composable
fun WellnessNote() = Note(stringResource(R.string.wellness_short), MaterialTheme.colorScheme.primary)

// ================================= Light dose (melanopic) ===================================

private fun simDayLux(engine: AcquisitionEngine): Double? {
    val sc = engine.simulator?.scene as? SimScene.Day ?: return null
    val h = ((sc.clock.at(System.currentTimeMillis()) % 86_400_000L + 86_400_000L) % 86_400_000L) / 3_600_000.0
    return SimOptics.dayLux(h)
}

@Composable
fun LightDoseTool(engine: AcquisitionEngine) {
    val st by engine.tools.lightDose.collectAsState()
    val params by engine.tools.params.collectAsState()
    val frame by engine.frame.collectAsState()
    val log = engine.tools.exposure
    val wake = params["dose.wake"] ?: 7.0
    val bed = params["dose.bed"] ?: 23.0
    LaunchedEffect(Unit) { engine.tools.lightDoseLoad(engine.solarTimeMs(System.currentTimeMillis())) }
    val now = frame?.let { engine.solarTimeMs(it.timeMs) } ?: System.currentTimeMillis()
    val hour = log?.hourOf(now) ?: 12.0
    val asleep = if (bed > wake) hour >= bed || hour < wake else hour in bed..wake
    val evening = !asleep && ((bed - hour + 24) % 24) <= 3.0
    val mel = st.melEdi
    val aimOk: Pair<Int, Boolean> = when {
        asleep -> R.string.dose_aim_night to (mel == null || mel <= LightExposure.NIGHT_MAX_MEL_EDI)
        evening -> R.string.dose_aim_evening to (mel == null || mel <= LightExposure.EVENING_MAX_MEL_EDI)
        else -> R.string.dose_aim_day to (mel == null || mel >= LightExposure.DAY_MIN_MEL_EDI)
    }
    val (aim, ok) = aimOk
    val sum = remember(st.samples.size, wake, bed) { log?.let { lg -> LightExposure.summary(st.samples, wake, bed) { lg.hourOf(it) } } }
    ToolPage {
        WellnessNote()
        AcquisitionRow(engine)
        if (mel == null) Note(stringResource(R.string.dose_needs_cal))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.lh_mel_edi), mel?.let { "${f(it, if (it < 10) 1 else 0)} lx" } ?: "—", size = 28.sp,
                colour = if (mel == null) MaterialTheme.colorScheme.primary else if (ok) OnGreen else WarnAmber)
            BigValue(stringResource(R.string.light_lux), st.lux?.let { "${f(it, 0)} lx" } ?: "—", size = 24.sp)
        }
        Text(stringResource(aim) + if (mel != null) "  " + stringResource(if (ok) R.string.dose_ok else R.string.dose_not_ok) else "",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (st.samples.size >= 2) {
            SpectrumChart(st.samples.map { log?.hourOf(it.tMs) ?: 0.0 }.toDoubleArray(), st.samples.map { kotlin.math.log10(1 + it.melEdi) }.toDoubleArray(),
                height = 170.dp, yUnit = "log₁₀(1 + EDI)", dots = true, hLine = kotlin.math.log10(1 + LightExposure.DAY_MIN_MEL_EDI),
                xRange = 0.0..24.0, yRange = 0.0..4.5)
            Hint(stringResource(R.string.dose_chart))
        }
        Section(stringResource(R.string.dose_today)) {
            KeyValue(stringResource(R.string.dose_hours_250), sum?.let { "${f(it.hoursAbove250, 1)} h" } ?: "—", live = true)
            KeyValue(stringResource(R.string.dose_evening_max), sum?.eveningMax?.let { "${f(it, 1)} lx" } ?: "—", live = true)
            KeyValue(stringResource(R.string.dose_night_max), sum?.nightMax?.let { "${f(it, 1)} lx" } ?: "—", live = true)
            KeyValue(stringResource(R.string.dose_mel_hours), sum?.let { "${f(it.melLuxHours, 0)} lx·h" } ?: "—", live = true)
            KeyValue(stringResource(R.string.kin_points), (sum?.samples ?: 0).toString(), live = true)
            KeyValue("CCT", st.cct?.let { "${f(it, 0)} K" } ?: "—", live = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.dose_wake), wake, { engine.tools.setParam("dose.wake", it) }, Modifier.weight(1f), 1, 0.0, 24.0, "h")
                NumberField(stringResource(R.string.dose_bed), bed, { engine.tools.setParam("dose.bed", it) }, Modifier.weight(1f), 1, 0.0, 24.0, "h")
            }
            Hint(stringResource(R.string.dose_help))
        }
        Section(stringResource(R.string.dose_logger)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.logging, stringResource(if (st.logging) R.string.dose_logging_on else R.string.dose_logging_off), {
                    engine.tools.lightDoseLogging(!st.logging); if (!st.logging) engine.setRunning(true) }, enabled = mel != null || st.logging)
                NumberField(stringResource(R.string.log_interval), params["dose.interval"] ?: 30.0, { engine.tools.setParam("dose.interval", it) }, Modifier.weight(1f), 0, 5.0, 3600.0, "s")
            }
            SwitchRow(stringResource(R.string.dose_auto), st.auto, { engine.tools.lightDoseAuto(it) }, stringResource(R.string.dose_auto_help))
            Hint(stringResource(R.string.dose_logger_help))
        }
        CalibrationPanel(engine, { simDayLux(engine) })
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_day_help))
            val n = System.currentTimeMillis(); val lon = engine.site().second
            ChipChoices(listOf(7.0, 13.0, 20.0, 23.8), null, { "%02.0f:%02.0f".format(Locale.US, kotlin.math.floor(it), (it % 1) * 60) }) { h ->
                engine.setSimScene(SimScene.Day(SimClock(n, Solar.solarHourToUtcMs(n, lon, h), 120.0)))
            }
            simDayLux(engine)?.let { Hint(stringResource(R.string.sim_day_truth, f(it, 0))) }
        }
    }
}

// ================================= UV dose ==================================================

@Composable
fun UvDoseTool(engine: AcquisitionEngine) {
    val st by engine.tools.uvDose.collectAsState()
    val params by engine.tools.params.collectAsState()
    val frame by engine.frame.collectAsState()
    val type = (params["uv.skin"] ?: 2.0).toInt().coerceIn(1, 6)
    val med = UvDose.MED[type - 1]
    val uvi = st.uvi
    val rate = uvi?.let { UvDose.sedPerHour(it) }
    val left = if (rate != null && rate > 0.01) ((med - st.sedToday) / rate * 60).coerceAtLeast(0.0) else null
    val pct = 100 * st.sedToday / med
    ToolPage {
        WellnessNote()
        AcquisitionRow(engine)
        if (params["uvi.scale"] == null) Note(stringResource(R.string.uvdose_needs_scale))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.uvdose_today), "${f(st.sedToday, 2)} SED", size = 28.sp, colour = if (pct >= 100) OffRed else if (pct >= 60) WarnAmber else OnGreen)
            BigValue(stringResource(R.string.uvi_index), f(uvi, 1), size = 28.sp, colour = uvi?.let { riskColourOf(it) } ?: MaterialTheme.colorScheme.primary)
        }
        Section(stringResource(R.string.uvdose_details)) {
            KeyValue(stringResource(R.string.uvdose_share), "${f(pct, 0)} % · MED ${f(med, 1)} SED", live = true)
            KeyValue(stringResource(R.string.uvdose_rate), rate?.let { "${f(it, 2)} SED/h" } ?: "—", live = true)
            KeyValue(stringResource(R.string.uvdose_left), left?.let { if (it > 600) "> 10 h" else "${f(it, 0)} min" } ?: "—", live = true)
            Text(stringResource(R.string.uvdose_skin_type), style = MaterialTheme.typography.bodySmall)
            ChipChoices((1..6).toList(), type, { stringResource(R.string.uvdose_type_n, roman(it)) }) { engine.tools.setParam("uv.skin", it.toDouble()) }
            Hint(stringResource(skinTypeRes(type)))
            Hint(stringResource(R.string.uvdose_help))
        }
        Section(stringResource(R.string.dose_logger)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.logging, stringResource(if (st.logging) R.string.dose_logging_on else R.string.dose_logging_off), {
                    engine.tools.uvDoseLogging(!st.logging); if (!st.logging) engine.setRunning(true) }, enabled = uvi != null || st.logging)
                ActionButton(onClick = { engine.tools.uvDoseReset() }, compact = true) { Text(stringResource(R.string.action_reset)) }
            }
            ActionButton(onClick = { engine.openTool(ToolId.UV_INDEX) }) { Text(stringResource(R.string.uvdose_open_uvi)) }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_uvdose_help))
            val sc = engine.simulator?.scene as? SimScene.SunUv
            val site = engine.site()
            val truth = sc?.let { s ->
                val t = s.clock.at(System.currentTimeMillis()); val z = Solar.position(site.first, site.second, t).zenithDeg
                if (z < 89) SimOptics.trueUvIndex(kotlin.math.cos(Math.toRadians(z)), Solar.airmass(z), Solar.distanceFactor(t), s) else 0.0
            }
            truth?.let { Text(stringResource(R.string.sim_uvi_truth, f(it, 1)), style = MaterialTheme.typography.bodySmall) }
            ActionButton(onClick = {
                val fr = frame ?: return@ActionButton
                val spd = DoubleArray(fr.counts.size) { fr.counts[it] * 1e6 / fr.integrationUs }
                val r = UvIndex.analyse(fr.wavelengths, spd, null)
                if (r != null && truth != null && fr.darkCorrected) UvIndex.scaleFrom(r, truth)?.let { engine.tools.setParam("uvi.scale", it) }
            }, enabled = truth != null && truth > 0.2 && frame?.darkCorrected == true) { Text(stringResource(R.string.sim_uv_meter)) }
            val n = System.currentTimeMillis(); val lon = site.second
            ChipChoices(listOf(9.0, 11.0, 13.0), null, { "%02.0f:00 · 60×".format(Locale.US, it) }) { h ->
                engine.setSimScene(SimScene.SunUv(sc?.ozoneDu ?: 300.0, SimClock(n, Solar.solarHourToUtcMs(n, lon, h), 60.0), sc?.cloud ?: 1.0))
            }
        }
    }
}

private fun roman(n: Int) = listOf("I", "II", "III", "IV", "V", "VI")[n - 1]
private fun skinTypeRes(t: Int) = when (t) {
    1 -> R.string.skin_type_1; 2 -> R.string.skin_type_2; 3 -> R.string.skin_type_3
    4 -> R.string.skin_type_4; 5 -> R.string.skin_type_5; else -> R.string.skin_type_6
}
private fun riskColourOf(uvi: Double) = when (UvIndex.risk(uvi)) {
    UvIndex.Risk.LOW -> OnGreen; UvIndex.Risk.MODERATE -> androidx.compose.ui.graphics.Color(0xFFF9A825)
    UvIndex.Risk.HIGH -> androidx.compose.ui.graphics.Color(0xFFEF6C00); UvIndex.Risk.VERY_HIGH -> OffRed
    UvIndex.Risk.EXTREME -> androidx.compose.ui.graphics.Color(0xFF6A1B9A)
}

// ================================= Skin indices =============================================

@Composable
fun SkinTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val strings by engine.tools.strings.collectAsState()
    var label by remember { mutableStateOf(strings["skin.label"] ?: "") }
    var logged by remember { mutableIntStateOf(0) }
    val fr = frame
    val r = fr?.ratio()
    val res = if (fr != null && r != null) SkinOptics.measure(fr.wavelengths, r) else null
    val base = params["skin.base.ei"]?.let { ei -> params["skin.base.mi"]?.let { mi -> ei to mi } }
    ToolPage {
        WellnessNote()
        AcquisitionRow(engine)
        if (r == null) Note(stringResource(R.string.skin_needs_white))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.skin_ei), f(res?.erythema, 1), size = 28.sp)
            BigValue(stringResource(R.string.skin_mi), f(res?.melanin, 1), size = 28.sp)
        }
        if (fr != null && r != null) SpectrumChart(fr.wavelengths, DoubleArray(r.size) { r[it] * 100 }, height = 160.dp, yUnit = "%",
            xRange = 420.0..(fr.wavelengths.last().coerceAtMost(800.0)), markers = listOf(560.0, 650.0))
        Section(stringResource(R.string.skin_details)) {
            KeyValue(stringResource(R.string.skin_car), res?.carotenoid?.let { f(it, 1) } ?: "—", live = true)
            KeyValue(stringResource(R.string.skin_delta), if (res != null && base != null) "ΔEI ${"%+.1f".format(Locale.US, res.erythema - base.first)} · ΔMI ${"%+.1f".format(Locale.US, res.melanin - base.second)}" else "—", live = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { res?.let { engine.tools.setParam("skin.base.ei", it.erythema); engine.tools.setParam("skin.base.mi", it.melanin) } }, enabled = res != null) {
                    Text(stringResource(R.string.skin_set_base))
                }
                if (base != null) ActionButton(onClick = { engine.tools.setParam("skin.base.ei", null); engine.tools.setParam("skin.base.mi", null) }, compact = true) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            OutlinedTextField(label, { label = it.take(40); engine.tools.setString("skin.label", label) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.skin_site)) }, trailingIcon = { ScanButton { label = it; engine.tools.setString("skin.label", it) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(onClick = {
                    val x = res ?: return@ActionButton
                    engine.tools.exposure?.append(ExposureLog.Kind.SKIN, System.currentTimeMillis(), listOf(label, x.erythema, x.melanin, x.carotenoid)); logged++
                }, enabled = res != null, emphasis = true) { Text(stringResource(R.string.skin_log)) }
                if (logged > 0) Text(stringResource(R.string.skin_logged, logged), style = MaterialTheme.typography.bodySmall)
            }
            Hint(stringResource(R.string.skin_help))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val presets = listOf(SimScene.Skin(0.4, 0.4, 1.0), SimScene.Skin(0.4, 0.9, 1.0), SimScene.Skin(1.6, 0.4, 1.0), SimScene.Skin(0.4, 0.4, 3.0))
            val names = listOf(R.string.sim_skin_fair, R.string.sim_skin_red, R.string.sim_skin_dark, R.string.sim_skin_carotene)
            ChipChoices(presets.indices.toList(), presets.indexOfFirst { it == engine.simulator?.scene }.takeIf { it >= 0 }, { stringResource(names[it]) }) { engine.setSimScene(presets[it]) }
        }
    }
}

// ================================= Pulse ====================================================

@Composable
fun PulseTool(engine: AcquisitionEngine) {
    val st by engine.tools.pulse.collectAsState()
    val res = st.result
    ToolPage {
        WellnessNote()
        AcquisitionRow(engine)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.pulse_rate), res?.bpm?.let { "${f(it, 0)} bpm" } ?: "—", size = 30.sp,
                colour = if (res?.bpm != null && res.quality > 0.6) OnGreen else MaterialTheme.colorScheme.primary)
            BigValue(stringResource(R.string.pulse_quality), res?.let { "${f(it.quality * 100, 0)} %" } ?: "—", size = 24.sp)
        }
        if (st.t.size > 10) {
            val t0 = st.t.last() - 8
            val idx = st.t.indices.filter { st.t[it] >= t0 }
            val mean = idx.sumOf { st.v[it] } / idx.size
            // Light absorbed by the pulsing blood, % of the mean level (up = more blood).
            SpectrumChart(idx.map { st.t[it] - t0 }.toDoubleArray(), idx.map { 100 * (mean - st.v[it]) / mean }.toDoubleArray(), height = 160.dp,
                yUnit = stringResource(R.string.pulse_wave))
        }
        Section(stringResource(R.string.pulse_details)) {
            KeyValue(stringResource(R.string.pulse_pi), res?.perfusionIndex?.let { "${f(it, 2)} %" } ?: "—", live = true)
            KeyValue("RMSSD", res?.rmssdMs?.let { "${f(it, 0)} ms" } ?: "—", live = true)
            KeyValue(stringResource(R.string.pulse_beats), (res?.beats ?: 0).toString(), live = true)
            Hint(stringResource(R.string.pulse_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.recording, stringResource(if (st.recording) R.string.dose_logging_on else R.string.dose_logging_off), { engine.tools.pulseRecording(!st.recording) })
                ActionButton(onClick = { engine.tools.pulseClear() }, compact = true) { Text(stringResource(R.string.action_clear)) }
                ActionButton(onClick = { engine.store.update { it.copy(integrationMs = 20.0, scansToAverage = 1) }; engine.setRunning(true) }, compact = true) {
                    Text(stringResource(R.string.pulse_fast))
                }
            }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val sc = engine.simulator?.scene as? SimScene.Finger
            ChipChoices(listOf(55.0, 68.0, 90.0, 120.0), sc?.bpm, { "${f(it, 0)} bpm" }) { b -> engine.setSimScene(SimScene.Finger(b, System.currentTimeMillis())); engine.tools.pulseClear() }
        }
    }
}

// ================================= Exposure diary / export ==================================

private fun dayFiles(log: ExposureLog): Map<String, List<File>> =
    (log.dir.listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray()).groupBy { it.name.substringAfterLast('_').removeSuffix(".csv") }.toSortedMap(compareByDescending { it })

/** Zip of a day's exposure logs with a README naming the participant code, app version and instrument. */
private fun exportZip(context: Context, files: List<File>, day: String, code: String, model: String): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safe = code.replace(Regex("[^A-Za-z0-9_-]"), "").ifEmpty { "anon" }
    val zip = File(dir, "exposure_${safe}_$day.zip")
    ZipOutputStream(zip.outputStream()).use { z ->
        z.putNextEntry(ZipEntry("README.txt"))
        z.write(("PocketSpectro exposure log export\nparticipant=$code\nday=$day\napp=${BuildConfig.VERSION_NAME}\ninstrument=$model\n" +
            "Not a medical device. Wellness / research data; see the study protocol for its use.\n").toByteArray())
        z.closeEntry()
        for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
    }
    return zip
}

/** POSTs the zip to the study's HTTPS endpoint. */
private fun upload(url: String, zip: File, code: String): Int {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    return try {
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/zip")
        c.setRequestProperty("X-Participant", code)
        c.setRequestProperty("Content-Disposition", "attachment; filename=\"${zip.name}\"")
        c.outputStream.use { o -> zip.inputStream().use { it.copyTo(o) } }
        c.responseCode
    } finally { c.disconnect() }
}

@Composable
fun DiaryTool(engine: AcquisitionEngine) {
    val context = LocalContext.current
    val strings by engine.tools.strings.collectAsState()
    val conn by engine.connection.collectAsState()
    val log = engine.tools.exposure
    var code by remember { mutableStateOf(strings["diary.code"] ?: "") }
    var url by remember { mutableStateOf(strings["diary.url"] ?: "") }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val days = remember(refresh) { log?.let { dayFiles(it) } ?: emptyMap() }
    var day by remember { mutableStateOf<String?>(null) }
    val chosen = day ?: days.keys.firstOrNull()
    val model = (conn as? com.rfsat.pocketspectro.core.Connection.Ready)?.info?.model ?: ""
    val uploadFailed = stringResource(R.string.diary_upload_failed)
    val uploadOk = stringResource(R.string.diary_uploaded)
    ToolPage {
        WellnessNote()
        Section(stringResource(R.string.diary_study)) {
            OutlinedTextField(code, { code = it.take(32); engine.tools.setString("diary.code", code) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.diary_code)) }, trailingIcon = { ScanButton { code = it; engine.tools.setString("diary.code", it) } })
            OutlinedTextField(url, { url = it.trim(); engine.tools.setString("diary.url", url) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.diary_url)) })
            Hint(stringResource(R.string.diary_consent))
        }
        Section(stringResource(R.string.diary_logs)) {
            if (days.isEmpty()) Hint(stringResource(R.string.diary_empty))
            ChipChoices(days.keys.take(10).toList(), chosen, { "${it.substring(6, 8)}.${it.substring(4, 6)}." }) { day = it }
            val files = chosen?.let { days[it] } ?: emptyList()
            val rows = remember(files, refresh) { files.map { fl -> try { fl.useLines { l -> l.count() } - 1 } catch (_: Exception) { 0 } } }
            for ((k, fl) in files.withIndex()) KeyValue(fl.name.substringBefore('_').uppercase(Locale.US), stringResource(R.string.diary_rows, rows.getOrElse(k) { 0 }.coerceAtLeast(0)))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = {
                    val z = exportZip(context, files, chosen ?: return@ActionButton, code, model)
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", z)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }, enabled = files.isNotEmpty(), emphasis = true) { Text(stringResource(R.string.diary_share)) }
                ActionButton(onClick = {
                    val d = chosen ?: return@ActionButton
                    scope.launch {
                        status = try {
                            val z = exportZip(context, files, d, code, model)
                            val rc = withContext(Dispatchers.IO) { upload(url, z, code) }
                            if (rc in 200..299) uploadOk else "$uploadFailed (HTTP $rc)"
                        } catch (e: Exception) { "$uploadFailed (${e.javaClass.simpleName})" }
                    }
                }, enabled = files.isNotEmpty() && url.startsWith("https://") && code.isNotBlank()) { Text(stringResource(R.string.diary_upload)) }
                ActionButton(onClick = { refresh++ }, compact = true) { Text(stringResource(R.string.diary_refresh)) }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Hint(stringResource(R.string.diary_help))
        }
    }
}

// ================================= Application kits =========================================

enum class Kit(val title: Int, val text: Int, val tools: List<ToolId>) {
    FOOD_AUTHENTICITY(R.string.kit_food, R.string.kit_food_text, listOf(ToolId.OLIVE, ToolId.DRINKS, ToolId.PREDICT, ToolId.MATCH, ToolId.RAMAN)),
    PLASTICS(R.string.kit_plastics, R.string.kit_plastics_text, listOf(ToolId.MATCH, ToolId.PREDICT, ToolId.RAMAN)),
    MEDICINES(R.string.kit_medicines, R.string.kit_medicines_text, listOf(ToolId.RAMAN, ToolId.MATCH, ToolId.FLUORIMETER, ToolId.CONCENTRATION)),
    WILDFIRE_NETWORK(R.string.kit_wildfire, R.string.kit_wildfire_text, listOf(ToolId.FIRE, ToolId.SUN, ToolId.GAS_COLUMNS, ToolId.VEGETATION)),
    ENACT_EXPOSURE(R.string.kit_enact, R.string.kit_enact_text, listOf(ToolId.LIGHT_DOSE, ToolId.UV_DOSE, ToolId.SKIN, ToolId.PULSE, ToolId.LAMPS, ToolId.DIARY)),
    PRECISION_AGRI(R.string.kit_agri, R.string.kit_agri_text, listOf(ToolId.VEGETATION, ToolId.LEAF_CLIP, ToolId.FLUORESCENCE, ToolId.GREENHOUSE, ToolId.SOIL)),
    WATER_QUALITY(R.string.kit_water, R.string.kit_water_text, listOf(ToolId.WATER, ToolId.WATER_COLOUR, ToolId.TEST_KIT, ToolId.PH, ToolId.FLUORIMETER)),
    HERITAGE(R.string.kit_heritage, R.string.kit_heritage_text, listOf(ToolId.PIGMENT, ToolId.COLOUR, ToolId.RAMAN, ToolId.FILM)),
    SCHOOL_LAB(R.string.kit_school, R.string.kit_school_text, listOf(ToolId.PHYSICS, ToolId.CONCENTRATION, ToolId.KINETICS, ToolId.PH, ToolId.GROWTH, ToolId.LIGHT)),
}

/** Application kits: ready-made combinations of tools for one job, at the top of the Tools tab. */
@Composable
fun KitsRow(engine: AcquisitionEngine) {
    var open by remember { mutableStateOf<Kit?>(null) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.kits_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in Kit.entries) ChoiceChip(false, stringResource(k.title), { open = k })
        }
    }
    open?.let { k ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(stringResource(k.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(k.text), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in k.tools) ActionButton(onClick = { open = null; engine.openTool(t) }, compact = true) { Text(stringResource(t.titleRes)) }
                    }
                }
            },
            confirmButton = { ActionButton(onClick = { open = null }, emphasis = true) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
