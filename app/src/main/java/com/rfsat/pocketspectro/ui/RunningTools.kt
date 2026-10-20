package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.tools.Jobs
import com.rfsat.pocketspectro.tools.ToolId
import java.util.Locale

/** One line of the running-tools sheet. */
data class Job(
    /** Null: acquisition or the session log (not a tool). */
    val tool: ToolId?,
    val icon: ImageVector,
    val shortName: String,
    val running: Boolean,
    val status: String,
    val toggle: () -> Unit,
    val needs: Jobs.Needs? = null,
    val kind: Kind = if (tool != null) Kind.TOOL else Kind.ACQUISITION,
) {
    enum class Kind { ACQUISITION, TOOL, LOG }
}

/** True when anything besides plain acquisition runs: the work that must go on with the screen off. */
fun List<Job>.background() = any { it.running && it.kind != Job.Kind.ACQUISITION }

/** Clash between two running jobs, for the warning lines. */
data class JobClash(val a: Job, val b: Job, val why: Set<Jobs.Clash>)

/** Collects the state of every background job (acquisition, tool recorders and loggers, alarms, the session log). */
@Composable
fun rememberJobs(engine: AcquisitionEngine): List<Job> {
    val running by engine.running.collectAsState()
    val settings by engine.store.settings.collectAsState()
    val log by engine.log.collectAsState()
    val armed by engine.fireArmed.collectAsState()
    val frame by engine.frame.collectAsState()
    val veg by engine.tools.vegetation.collectAsState()
    val em by engine.tools.emission.collectAsState()
    val lamps by engine.tools.lamps.collectAsState()
    val kin by engine.tools.kinetics.collectAsState()
    val sun by engine.tools.sun.collectAsState()
    val growth by engine.tools.growth.collectAsState()
    val ld by engine.tools.lightDose.collectAsState()
    val uv by engine.tools.uvDose.collectAsState()
    val plant by engine.tools.plantLight.collectAsState()
    val pulse by engine.tools.pulse.collectAsState()
    val darks by engine.tools.darks.collectAsState()
    // Reading every state here makes the caller recompose when any job changes.
    listOf<Any?>(running, settings, log, armed, frame, veg, em, lamps, kin, sun, growth, ld, uv, plant, pulse, darks)
    return jobsSnapshot(androidx.compose.ui.platform.LocalContext.current, engine)
}

/** The jobs as they are now, outside Compose too (the background notification). */
fun jobsSnapshot(context: android.content.Context, engine: AcquisitionEngine): List<Job> {
    val running = engine.running.value
    val settings = engine.store.settings.value
    val log = engine.log.value
    val armed = engine.fireArmed.value
    val frame = engine.frame.value
    val veg = engine.tools.vegetation.value
    val em = engine.tools.emission.value
    val lamps = engine.tools.lamps.value
    val kin = engine.tools.kinetics.value
    val sun = engine.tools.sun.value
    val growth = engine.tools.growth.value
    val ld = engine.tools.lightDose.value
    val uv = engine.tools.uvDose.value
    val plant = engine.tools.plantLight.value
    val pulse = engine.tools.pulse.value
    val darks = engine.tools.darks.value
    val t = engine.tools
    /** Starting a job also starts acquisition (a recorder without spectra records nothing). */
    fun start(block: () -> Unit) = { block(); if (!engine.running.value) engine.setRunning(true) }
    fun f(v: Double?, d: Int) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)
    val owner = engine.acquisitionOwner
    return listOf(
        Job(null, Icons.Filled.Sensors, context.getString(R.string.job_acquisition), running,
            if (running) context.getString(R.string.job_acq_status, f(settings.integrationMs, 0), settings.scansToAverage,
                owner?.let { context.getString(it.jobNameRes) } ?: context.getString(R.string.job_acq_manual)) else context.getString(R.string.job_idle),
            { engine.setRunning(!running) }),
        Job(ToolId.FIRE, ToolId.FIRE.icon, context.getString(ToolId.FIRE.jobNameRes), armed,
            if (!armed) context.getString(R.string.job_idle) else context.getString(if (frame?.fireConfirmed == true) R.string.job_fire_alarm else R.string.job_fire_clear),
            if (armed) ({ engine.armFire(false) }) else start { engine.armFire(true) }, Jobs.needs(ToolId.FIRE)),
        Job(ToolId.EMISSION, ToolId.EMISSION.icon, context.getString(ToolId.EMISSION.jobNameRes), em.armed,
            context.getString(R.string.job_alarms, em.alarmCount), if (em.armed) ({ t.emissionArm(false) }) else start { t.emissionArm(true) }, Jobs.needs(ToolId.EMISSION)),
        Job(ToolId.VEGETATION, ToolId.VEGETATION.icon, context.getString(ToolId.VEGETATION.jobNameRes), veg.recording,
            context.getString(R.string.job_points, veg.points), if (veg.recording) ({ t.vegRecording(false) }) else start { t.vegRecording(true) }, Jobs.needs(ToolId.VEGETATION)),
        Job(ToolId.LAMPS, ToolId.LAMPS.icon, context.getString(ToolId.LAMPS.jobNameRes), lamps.recording,
            context.getString(R.string.job_points, lamps.points), if (lamps.recording) ({ t.lampRecording(false) }) else start { t.lampRecording(true) }, Jobs.needs(ToolId.LAMPS)),
        Job(ToolId.SUN, ToolId.SUN.icon, context.getString(ToolId.SUN.jobNameRes), sun.langley,
            context.getString(R.string.job_points, sun.langleyPoints.size), if (sun.langley) ({ t.sunLangley(false) }) else start { t.sunLangley(true) }, Jobs.needs(ToolId.SUN)),
        Job(ToolId.KINETICS, ToolId.KINETICS.icon, context.getString(ToolId.KINETICS.jobNameRes), kin.recording,
            context.getString(R.string.job_points, kin.points.size), if (kin.recording) ({ t.kineticsRecording(false) }) else start { t.kineticsRecording(true) }, Jobs.needs(ToolId.KINETICS)),
        Job(ToolId.GROWTH, ToolId.GROWTH.icon, context.getString(ToolId.GROWTH.jobNameRes), growth.recording,
            context.getString(R.string.job_growth, growth.points.size, f(growth.points.lastOrNull()?.od, 3)),
            if (growth.recording) ({ t.growthRecording(false, 0L) }) else start { t.growthRecording(true, engine.solarTimeMs(System.currentTimeMillis())) },
            Jobs.needs(ToolId.GROWTH)),
        Job(ToolId.LIGHT_DOSE, ToolId.LIGHT_DOSE.icon, context.getString(ToolId.LIGHT_DOSE.jobNameRes), ld.logging,
            context.getString(R.string.job_light_dose, ld.samples.size, f(ld.melEdi, 0)), if (ld.logging) ({ t.lightDoseLogging(false) }) else start { t.lightDoseLogging(true) },
            Jobs.needs(ToolId.LIGHT_DOSE, ld.auto && darks.isNotEmpty())),
        Job(ToolId.UV_DOSE, ToolId.UV_DOSE.icon, context.getString(ToolId.UV_DOSE.jobNameRes), uv.logging,
            context.getString(R.string.job_uv_dose, f(uv.sedToday, 2), f(uv.uvi, 1)), if (uv.logging) ({ t.uvDoseLogging(false) }) else start { t.uvDoseLogging(true) },
            Jobs.needs(ToolId.UV_DOSE)),
        Job(ToolId.GREENHOUSE, ToolId.GREENHOUSE.icon, context.getString(ToolId.GREENHOUSE.jobNameRes), plant.logging,
            context.getString(R.string.job_dli, f(plant.dli, 2), f(plant.ppfd, 0)), if (plant.logging) ({ t.plantLogging(false) }) else start { t.plantLogging(true) },
            Jobs.needs(ToolId.GREENHOUSE, darks.isNotEmpty())),
        Job(ToolId.PULSE, ToolId.PULSE.icon, context.getString(ToolId.PULSE.jobNameRes), pulse.recording,
            context.getString(R.string.job_pulse, f(pulse.result?.bpm, 0)), if (pulse.recording) ({ t.pulseRecording(false) }) else start { t.pulseRecording(true) },
            Jobs.needs(ToolId.PULSE)),
        Job(null, Icons.Filled.Sensors, context.getString(R.string.job_log), log.active && !log.paused,
            if (log.active) context.getString(R.string.job_log_status, log.spectra) else context.getString(R.string.job_idle),
            { if (log.active) engine.stopLog() else engine.startLog() }, kind = Job.Kind.LOG),
    )
}

/** Pairs of running tool jobs that cannot share the spectra. */
fun clashes(jobs: List<Job>): List<JobClash> {
    val on = jobs.filter { it.running && it.needs != null }
    val out = ArrayList<JobClash>()
    for (i in on.indices) for (k in i + 1 until on.size) {
        val why = Jobs.clash(on[i].needs!!, on[k].needs!!)
        if (why.isNotEmpty()) out += JobClash(on[i], on[k], why)
    }
    return out
}

/** Short name of a tool in the running-tools table (one row each). */
val ToolId.jobNameRes: Int get() = when (this) {
    ToolId.FIRE -> R.string.job_fire
    ToolId.EMISSION -> R.string.job_emission
    ToolId.VEGETATION -> R.string.job_vegetation
    ToolId.LAMPS -> R.string.job_lamps
    ToolId.SUN -> R.string.job_sun
    ToolId.KINETICS -> R.string.job_kinetics
    ToolId.GROWTH -> R.string.job_growth_name
    ToolId.LIGHT_DOSE -> R.string.job_light_dose_name
    ToolId.UV_DOSE -> R.string.job_uv_dose_name
    ToolId.GREENHOUSE -> R.string.job_greenhouse
    ToolId.PULSE -> R.string.job_pulse_name
    else -> titleRes
}

@Composable
private fun clashText(c: JobClash): String {
    val names = mapOf(Jobs.Clash.VIEW to stringResource(R.string.clash_view), Jobs.Clash.REFERENCE to stringResource(R.string.clash_reference),
        Jobs.Clash.EXPOSURE to stringResource(R.string.clash_exposure))
    val why = c.why.sortedBy { it.ordinal }.joinToString(", ") { names.getValue(it) }
    return stringResource(R.string.clash_line, c.a.shortName, c.b.shortName, why)
}

/** Top-bar chip: how many jobs run (green; amber with a clash; grey when idle). Opens the sheet. */
@Composable
fun RunningBadge(engine: AcquisitionEngine) {
    val jobs = rememberJobs(engine)
    val n = jobs.count { it.running }
    val clash = clashes(jobs).isNotEmpty()
    var open by remember { mutableStateOf(false) }
    val colour = when { n == 0 -> MaterialTheme.colorScheme.onSurfaceVariant; clash -> WarnAmber; else -> OnGreen }
    OutlinedButton(onClick = { open = true }, border = BorderStroke(1.5.dp, colour), shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), modifier = Modifier.padding(end = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colour)) {
        Text(if (clash) "⚠ " else "● ", color = colour)
        Text(stringResource(R.string.jobs_badge, n), fontWeight = FontWeight.SemiBold)
    }
    if (open) RunningSheet(engine, jobs) { open = false }
}

/** Task manager: every background job in one aligned table with its status and ON/OFF, clash warnings, Stop all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningSheet(engine: AcquisitionEngine, jobs: List<Job>, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state) {
        RunningTable(engine, jobs, onClose)
    }
}

/** Fixed column widths keep the rows aligned so the states can be compared at a glance. */
private val NAME_W = 104.dp
private val TOGGLE_W = 64.dp

@Composable
fun RunningTable(engine: AcquisitionEngine, jobs: List<Job>, onClose: () -> Unit) {
    val clashes = clashes(jobs)
    // Running jobs first, then the others, each in the fixed order.
    val rows = jobs.sortedBy { if (it.running) 0 else 1 }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.jobs_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ActionButton(onClick = { engine.stopAll() }, compact = true, enabled = jobs.any { it.running }) { Text(stringResource(R.string.jobs_stop_all)) }
        }
        Text(stringResource(R.string.jobs_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        // Header
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            Text(stringResource(R.string.jobs_col_tool), Modifier.width(NAME_W), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.jobs_col_status), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.jobs_col_state), Modifier.width(TOGGLE_W), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        for (j in rows) {
            val dim = if (j.running) 1f else 0.6f
            val inClash = clashes.any { it.a === j || it.b === j }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(j.icon, null, Modifier.size(20.dp), tint = (if (inClash) WarnAmber else MaterialTheme.colorScheme.primary).copy(alpha = dim))
                Spacer(Modifier.width(6.dp))
                // Short name, one line; tap opens the tool.
                Text(j.shortName, Modifier.width(NAME_W).let { m -> j.tool?.let { tl -> m.clickable { onClose(); engine.openTool(tl) } } ?: m },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (j.running) FontWeight.SemiBold else FontWeight.Normal,
                    color = (if (j.tool != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = dim),
                    style = MaterialTheme.typography.bodyMedium)
                FitText(j.status, Modifier.weight(1f).padding(end = 6.dp), MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
                ToggleButton(j.running, stringResource(if (j.running) R.string.state_on else R.string.state_off), j.toggle,
                    Modifier.width(TOGGLE_W), compact = true)
            }
        }
        HorizontalDivider()
        if (clashes.isEmpty()) Text(stringResource(R.string.jobs_no_clash), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = OnGreen)
        else {
            // One line per clash (shrinks to fit), with the meaning of the short reasons once below.
            for (c in clashes) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .background(WarnAmber.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = WarnAmber)
                Spacer(Modifier.width(6.dp))
                FitText(clashText(c), Modifier.weight(1f), MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.clash_legend), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
    }
}

/** On a tool page: warns when this tool's job clashes with jobs already running. */
@Composable
fun ClashNote(engine: AcquisitionEngine, tool: ToolId) {
    val jobs = rememberJobs(engine)
    val mine = jobs.firstOrNull { it.tool == tool } ?: return
    val needs = mine.needs ?: return
    val others = jobs.filter { it.running && it.tool != tool && it.needs != null && Jobs.clash(needs, it.needs).isNotEmpty() }
    if (others.isEmpty()) return
    Note(stringResource(R.string.clash_tool, others.joinToString(", ") { it.shortName }))
}


/**
 * Starts the background service (notification + wake lock) as soon as a logger, recorder or alarm
 * runs on a real spectrometer, while the app is in front; asks once for the notification permission (Android 13+).
 */
@Composable
fun JobServiceStarter(engine: AcquisitionEngine) {
    val bg = rememberJobs(engine).background()
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val real = engine.simulator == null
    androidx.compose.runtime.LaunchedEffect(bg, real) {
        if (!bg || !real) return@LaunchedEffect
        val prefs = context.getSharedPreferences("tools", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notifAsked", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notifAsked", true).apply()
            try { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) {}
        }
        com.rfsat.pocketspectro.core.JobService.start(context, engine)
    }
}
