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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.sim.SimClock
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimOptics
import com.rfsat.pocketspectro.sim.SimSample
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.tools.Fluorescence
import com.rfsat.pocketspectro.tools.LampClassifier
import com.rfsat.pocketspectro.tools.Solar
import com.rfsat.pocketspectro.tools.SunPhotometer
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.UvIndex
import com.rfsat.pocketspectro.tools.WaterUv
import com.rfsat.pocketspectro.tools.absorbance
import java.util.Locale

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

/** Amber note line used by the tools for missing references or conditions. */
@Composable
fun Note(text: String, colour: Color = WarnAmber) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .background(colour.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(10.dp),
        style = MaterialTheme.typography.bodySmall)
}

/** Simulated sky clock choices shared by the sun tools. */
@Composable
internal fun SimClockChips(engine: AcquisitionEngine, current: SimClock?, onClock: (SimClock) -> Unit) {
    val now = System.currentTimeMillis()
    val lon = engine.site().second
    val hours = listOf(9.0, 12.0, 15.0)
    val selected = current?.takeIf { it.speed == 1.0 }?.let { c ->
        hours.firstOrNull { kotlin.math.abs(c.at(c.realStartMs) - Solar.solarHourToUtcMs(c.realStartMs, lon, it)) < 60_000 }
    }
    Text(stringResource(R.string.sim_sun_time), style = MaterialTheme.typography.bodySmall)
    ChipChoices(hours + (-1.0), if (current?.speed == 120.0) -1.0 else selected, { if (it < 0) stringResource(R.string.sim_sun_morning) else "%02.0f:00".format(Locale.US, it) }) { h ->
        onClock(if (h < 0) SimClock(now, Solar.solarHourToUtcMs(now, lon, 4.5), 120.0) else SimClock(now, Solar.solarHourToUtcMs(now, lon, h)))
    }
}

// ================================= Sun photometer ===========================================

@Composable
fun SunTool(engine: AcquisitionEngine) {
    val st by engine.tools.sun.collectAsState()
    val params by engine.tools.params.collectAsState()
    val frame by engine.frame.collectAsState()
    var knownAod by remember { mutableDoubleStateOf(0.10) }
    var knownAlpha by remember { mutableDoubleStateOf(1.3) }
    var knownPwv by remember { mutableDoubleStateOf(1.5) }
    var simScene by remember { androidx.compose.runtime.mutableStateOf(engine.simulator?.scene as? SimScene.Sun) }
    val r = st.result
    val pos = st.position

    ToolPage {
        AcquisitionRow(engine)
        KeyValueRow(stringResource(R.string.sun_position), pos?.let {
            stringResource(R.string.sun_position_value, f(it.elevationDeg, 1), f(it.azimuthDeg, 0), f(st.airmass, 2))
        } ?: "—")
        // One status line that is always there (its text and colour change), so nothing below jumps.
        when {
            frame != null && frame?.darkCorrected != true -> Note(stringResource(R.string.sun_needs_dark))
            st.saturated -> Note(stringResource(R.string.sun_saturated), OffRed)
            pos != null && pos.zenithDeg >= 85.0 -> Note(stringResource(R.string.sun_too_low))
            st.v0.isEmpty() -> Note(stringResource(R.string.sun_not_calibrated))
            else -> Note(stringResource(R.string.sun_ok), OnGreen)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.sun_aod500), f(r?.aod500, 3), size = 28.sp)
            BigValue(stringResource(R.string.sun_pwv), r?.pwvCm?.let { "${f(it, 2)} cm" } ?: "—", size = 28.sp)
        }
        Section(stringResource(R.string.sun_results)) {
            KeyValue(stringResource(R.string.sun_haze), r?.aod500?.let { stringResource(hazeRes(it)) } ?: "—", live = true)
            KeyValue(stringResource(R.string.sun_angstrom), f(r?.angstrom, 2), live = true)
            for (nm in SunPhotometer.channels(frame?.wavelengths ?: DoubleArray(0)).filter { it != SunPhotometer.WATER_NM }) {
                KeyValue(stringResource(R.string.sun_aod_at, f(nm, 0)), f(r?.aod?.get(nm), 3), live = true)
            }
            Text(stringResource(R.string.sun_results_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.SUN, if (r?.aod500 != null) SaveSpec("AOD ${f(r.aod500, 2)}" + (r.pwvCm?.let { " · ${f(it, 1)} cm" } ?: ""),
                r.aod500, if (r.aod500 < 0.1) "#43A047" else if (r.aod500 < 0.3) "#FDD835" else "#E53935",
                "AOD500 %.3f; alpha %s; PWV %s cm; airmass %.2f".format(Locale.US, r.aod500, f(r.angstrom, 2), f(r.pwvCm, 2), r.airmass)) else null)
        }
        if (st.series.size >= 2) Section(stringResource(R.string.sun_series)) {
            val t0 = st.series.first().first
            val pts = st.series.filter { it.second != null }
            if (pts.size >= 2) SpectrumChart(pts.map { (it.first - t0) / 3_600_000.0 }.toDoubleArray(), pts.map { it.second!! }.toDoubleArray(),
                height = 150.dp, yUnit = "AOD", dots = true)
            Text(stringResource(R.string.sun_series_axis), style = MaterialTheme.typography.labelSmall)
            ActionButton(onClick = { engine.tools.sunClearSeries() }, compact = true) { Text(stringResource(R.string.action_clear)) }
        }
        Section(stringResource(R.string.sun_calibration)) {
            Text(stringResource(R.string.sun_cal_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.sun_cal_known), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.sun_aod500), knownAod, { knownAod = it }, Modifier.weight(1f), 3, 0.0, 5.0)
                NumberField("α", knownAlpha, { knownAlpha = it }, Modifier.weight(0.8f), 2, -1.0, 4.0)
                NumberField(stringResource(R.string.sun_pwv_short), knownPwv, { knownPwv = it }, Modifier.weight(0.9f), 2, 0.0, 8.0, "cm")
            }
            ActionButton(onClick = {
                val m = st.airmass ?: return@ActionButton
                engine.tools.sunCalibration(SunPhotometer.calibrateFromKnown(st.signals, m, st.distFactor, engine.tools.sunAtmosphere(), knownAod, knownAlpha, knownPwv))
            }, enabled = st.signals.isNotEmpty() && (st.airmass ?: 99.0) < 10, emphasis = true) { Text(stringResource(R.string.sun_cal_now)) }
            Text(stringResource(R.string.sun_cal_langley), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.sun_langley_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.langley, stringResource(if (st.langley) R.string.sun_langley_on else R.string.sun_langley_off),
                    { engine.tools.sunLangley(!st.langley); if (!st.langley) engine.setRunning(true) })
                ActionButton(onClick = {
                    SunPhotometer.langley(st.langleyPoints, engine.tools.sunAtmosphere())?.let { engine.tools.sunCalibration(it.v0); engine.tools.sunLangley(false) }
                }, enabled = st.langleyPoints.size >= 8) { Text(stringResource(R.string.sun_langley_fit)) }
            }
            val ms = st.langleyPoints.map { it.airmass }
            KeyValue(stringResource(R.string.sun_langley_points), if (ms.isEmpty()) "0" else
                stringResource(R.string.sun_langley_points_value, ms.size, f(ms.min(), 2), f(ms.max(), 2)), live = true)
            for ((nm, v) in st.v0.toSortedMap()) KeyValue("V₀ ${f(nm, 0)} nm", "%.4g".format(Locale.US, v) + " /s", live = true)
            if (st.v0.isNotEmpty()) ActionButton(onClick = { engine.tools.sunCalibration(emptyMap()) }, compact = true) { Text(stringResource(R.string.sun_cal_clear)) }
        }
        Section(stringResource(R.string.sun_site)) {
            KeyValue(stringResource(R.string.sun_site_source), stringResource(if (st.siteFromGps) R.string.sun_site_gps else R.string.sun_site_manual))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.sun_lat), params["sun.lat"] ?: 53.35, { engine.tools.setParam("sun.lat", it) }, Modifier.weight(1f), 3, -90.0, 90.0, "°")
                NumberField(stringResource(R.string.sun_lon), params["sun.lon"] ?: -6.26, { engine.tools.setParam("sun.lon", it) }, Modifier.weight(1f), 3, -180.0, 180.0, "°")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.sun_height), params["sun.height"] ?: 0.0, { engine.tools.setParam("sun.height", it) }, Modifier.weight(1f), 0, -400.0, 6000.0, "m")
                NumberField(stringResource(R.string.sun_ozone), params["sun.ozone"] ?: 300.0, { engine.tools.setParam("sun.ozone", it) }, Modifier.weight(1f), 0, 150.0, 550.0, "DU")
            }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            Text(stringResource(R.string.sim_sky), style = MaterialTheme.typography.bodySmall)
            val skies = listOf(Triple(0.04, 1.5, 0.8), Triple(0.12, 1.3, 1.5), Triple(0.35, 1.2, 2.5), Triple(0.60, 0.3, 1.2))
            val names = listOf(R.string.sim_sky_clean, R.string.sim_sky_moderate, R.string.sim_sky_hazy, R.string.sim_sky_dust)
            ChipChoices(skies.indices.toList(), simScene?.let { s -> skies.indexOfFirst { it.first == s.aod500 && it.second == s.alpha }.takeIf { it >= 0 } },
                { stringResource(names[it]) }) { i ->
                val c = simScene?.clock ?: SimClock(System.currentTimeMillis(), System.currentTimeMillis())
                val s = SimScene.Sun(skies[i].first, skies[i].second, skies[i].third, c); simScene = s; engine.setSimScene(s)
            }
            SimClockChips(engine, simScene?.clock) { c -> val s = (simScene ?: SimScene.Sun(0.12, 1.3, 1.5, c)).copy(clock = c); simScene = s; engine.setSimScene(s) }
            simScene?.let { s ->
                Text(stringResource(R.string.sim_sun_truth, f(s.aod500, 2), f(s.alpha, 1), f(s.pwvCm, 1)), style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = { knownAod = s.aod500; knownAlpha = s.alpha; knownPwv = s.pwvCm }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { KeyValue(key, value, live = true) }
}

private fun hazeRes(aod: Double) = when {
    aod < 0.05 -> R.string.haze_very_clean
    aod < 0.10 -> R.string.haze_clean
    aod < 0.30 -> R.string.haze_moderate
    aod < 0.60 -> R.string.haze_hazy
    else -> R.string.haze_very_hazy
}

// ================================= UV index =================================================

@Composable
fun UvIndexTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val scale = params["uvi.scale"]
    var refUvi by remember { mutableDoubleStateOf(5.0) }
    var simScene by remember { androidx.compose.runtime.mutableStateOf(engine.simulator?.scene as? SimScene.SunUv) }
    val fr = frame
    val spd = fr?.takeIf { it.darkCorrected }?.let { x -> DoubleArray(x.counts.size) { x.counts[it] * 1e6 / x.integrationUs } }
    val r = if (fr != null && spd != null) UvIndex.analyse(fr.wavelengths, spd, scale) else null
    val site = engine.site()
    val pos = fr?.let { Solar.position(site.first, site.second, engine.solarTimeMs(it.timeMs)) }

    ToolPage {
        AcquisitionRow(engine)
        when {
            fr != null && !fr.darkCorrected -> Note(stringResource(R.string.uvi_needs_dark))
            scale == null -> Note(stringResource(R.string.uvi_not_scaled))
        }
        val uvi = r?.uvi
        val risk = uvi?.let { UvIndex.risk(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.uvi_index), f(uvi, 1), colour = risk?.let { riskColour(it) } ?: MaterialTheme.colorScheme.primary)
            BigValue(stringResource(R.string.uvi_risk), risk?.let { stringResource(riskRes(it)) } ?: "—", size = 22.sp,
                colour = risk?.let { riskColour(it) } ?: MaterialTheme.colorScheme.primary)
        }
        if (fr != null && spd != null) {
            val w = DoubleArray(fr.wavelengths.size) { UvIndex.erythemal(fr.wavelengths[it]) }
            val ymax = fr.wavelengths.indices.filter { fr.wavelengths[it] in 290.0..400.0 }.maxOfOrNull { spd[it] } ?: 1.0
            SpectrumChart(fr.wavelengths, spd, height = 190.dp, yUnit = stringResource(R.string.unit_counts_per_s), xRange = 285.0..420.0,
                overlay = fr.wavelengths to DoubleArray(w.size) { w[it] * ymax })
            Text(stringResource(R.string.uvi_chart_legend), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = OverlayColor)
        }
        Section(stringResource(R.string.uvi_details)) {
            KeyValue(stringResource(R.string.uvi_uvb_share), f(r?.uvbPercent, 1) + " %", live = true)
            KeyValue(stringResource(R.string.uvi_erythema_uvb), f(r?.erythemaFromUvb, 0) + " %", live = true)
            KeyValue(stringResource(R.string.sun_elevation), pos?.let { f(it.elevationDeg, 1) + "°" } ?: "—", live = true)
            SaveResultButton(engine, ToolId.UV_INDEX, if (uvi != null) SaveSpec("UVI ${f(uvi, 1)}", uvi, hex(riskColour(risk!!)),
                "UV index %.1f; UV-B %.1f %% of UV".format(Locale.US, uvi, r.uvbPercent)) else null)
        }
        Section(stringResource(R.string.uvi_scale)) {
            Text(stringResource(R.string.uvi_scale_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.uvi_reference), refUvi, { refUvi = it }, Modifier.weight(1f), 1, 0.1, 20.0)
                ActionButton(onClick = { r?.let { UvIndex.scaleFrom(it, refUvi) }?.let { engine.tools.setParam("uvi.scale", it) } },
                    enabled = r != null && r.weighted > 0, emphasis = true) { Text(stringResource(R.string.uvi_set_scale)) }
            }
            if (scale != null) ActionButton(onClick = { engine.tools.setParam("uvi.scale", null) }, compact = true) { Text(stringResource(R.string.uvi_clear_scale)) }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            Text(stringResource(R.string.sim_ozone), style = MaterialTheme.typography.bodySmall)
            ChipChoices(listOf(250.0, 300.0, 350.0), simScene?.ozoneDu, { "${f(it, 0)} DU" }) { du ->
                val s = (simScene ?: SimScene.SunUv(300.0, SimClock(System.currentTimeMillis(), System.currentTimeMillis()))).copy(ozoneDu = du); simScene = s; engine.setSimScene(s)
            }
            ChipChoices(listOf(1.0, 0.6, 0.3), simScene?.cloud, { stringResource(when (it) { 1.0 -> R.string.sim_clear_sky; 0.6 -> R.string.sim_thin_cloud; else -> R.string.sim_overcast }) }) { c ->
                val s = (simScene ?: SimScene.SunUv(300.0, SimClock(System.currentTimeMillis(), System.currentTimeMillis()))).copy(cloud = c); simScene = s; engine.setSimScene(s)
            }
            SimClockChips(engine, simScene?.clock) { c -> val s = (simScene ?: SimScene.SunUv(300.0, c)).copy(clock = c); simScene = s; engine.setSimScene(s) }
            simScene?.let { s ->
                val t = s.clock.at(System.currentTimeMillis())
                val z = Solar.position(site.first, site.second, t).zenithDeg
                val truth = SimOptics.trueUvIndex(kotlin.math.cos(Math.toRadians(z)), Solar.airmass(z), Solar.distanceFactor(t), s)
                Text(stringResource(R.string.sim_uvi_truth, f(truth, 1)), style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = { refUvi = (truth * 10).toLong() / 10.0 }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
            }
        }
    }
}

private fun riskRes(r: UvIndex.Risk) = when (r) {
    UvIndex.Risk.LOW -> R.string.uvi_low
    UvIndex.Risk.MODERATE -> R.string.uvi_moderate
    UvIndex.Risk.HIGH -> R.string.uvi_high
    UvIndex.Risk.VERY_HIGH -> R.string.uvi_very_high
    UvIndex.Risk.EXTREME -> R.string.uvi_extreme
}

private fun riskColour(r: UvIndex.Risk) = when (r) {
    UvIndex.Risk.LOW -> Color(0xFF43A047)
    UvIndex.Risk.MODERATE -> Color(0xFFF9A825)
    UvIndex.Risk.HIGH -> Color(0xFFEF6C00)
    UvIndex.Risk.VERY_HIGH -> Color(0xFFD32F2F)
    UvIndex.Risk.EXTREME -> Color(0xFF7B1FA2)
}

fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

// ================================= Lamp survey ==============================================

@Composable
fun LampsTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.lamps.collectAsState()
    val light by engine.tools.light.collectAsState()
    val fix by engine.location.fix.collectAsState()
    var simEmitter by remember { androidx.compose.runtime.mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val res = st.result
    ToolPage {
        AcquisitionRow(engine)
        frame?.let { fr -> SpectrumChart(fr.wavelengths, engine.tools.spd(fr), height = 200.dp,
            yUnit = stringResource(if (light.response != null) R.string.unit_relative else R.string.unit_counts), xRange = 380.0..800.0) }
        val type = res?.type
        BigValue(stringResource(R.string.lamp_type), type?.let { stringResource(lampRes(it)) } ?: "—", size = 24.sp,
            colour = type?.let { Color(android.graphics.Color.parseColor(it.colour)) } ?: MaterialTheme.colorScheme.primary)
        Section(stringResource(R.string.lamp_details)) {
            KeyValue(stringResource(R.string.light_cct), st.light?.cct?.let { "${f(it, 0)} K" } ?: "—", live = true)
            KeyValue(stringResource(R.string.lamp_blue), st.health?.let { "${f(it.bluePercent, 0)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.lh_mel_der), f(st.health?.melanopicDer, 2), live = true)
            KeyValue(stringResource(R.string.lamp_clues), res?.let { lampClues(it) } ?: "—")
            if (light.response == null) Text(stringResource(R.string.lamp_uncorrected), style = MaterialTheme.typography.bodySmall, color = WarnAmber)
            SaveResultButton(engine, ToolId.LAMPS, if (res != null && type != LampClassifier.Type.UNKNOWN) SaveSpec(stringResource(lampRes(res.type)), st.light?.cct ?: Double.NaN,
                res.type.colour, listOfNotNull(st.light?.cct?.let { "CCT %.0f K".format(Locale.US, it) }, st.health?.let { "mel-DER %.2f".format(Locale.US, it.melanopicDer) }).joinToString("; ")) else null)
        }
        Section(stringResource(R.string.lamp_survey)) {
            Text(stringResource(R.string.lamp_survey_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.recording, stringResource(if (st.recording) R.string.veg_survey_on else R.string.veg_survey_off),
                    { engine.tools.lampRecording(!st.recording); if (!st.recording) engine.setRunning(true) })
                NumberField(stringResource(R.string.log_interval), st.intervalSec, { engine.tools.lampInterval(it) }, Modifier.weight(1f), 0, 1.0, 3600.0, "s")
            }
            KeyValue(stringResource(R.string.veg_points), st.points.toString(), live = true)
            KeyValue(stringResource(R.string.log_gps), fix?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) } ?: stringResource(R.string.gps_waiting), live = true)
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val lamps = listOf(SimEmitter.LAMP_LPS, SimEmitter.LAMP_HPS, SimEmitter.LAMP_MERCURY, SimEmitter.LAMP_METAL_HALIDE, SimEmitter.LAMP_FLUORESCENT,
                SimEmitter.LED_WHITE_COOL, SimEmitter.LED_WHITE_WARM, SimEmitter.LAMP_2856K)
            ChipChoices(lamps, simEmitter, { emitterLabel(it) }) { e -> simEmitter = e; engine.setSimScene(SimScene.Emit(e)) }
        }
    }
}

fun lampRes(t: LampClassifier.Type) = when (t) {
    LampClassifier.Type.LOW_PRESSURE_SODIUM -> R.string.lamp_lps
    LampClassifier.Type.HIGH_PRESSURE_SODIUM -> R.string.lamp_hps
    LampClassifier.Type.MERCURY -> R.string.lamp_mercury
    LampClassifier.Type.METAL_HALIDE -> R.string.lamp_metal_halide
    LampClassifier.Type.FLUORESCENT -> R.string.lamp_fluorescent
    LampClassifier.Type.LED -> R.string.lamp_led
    LampClassifier.Type.INCANDESCENT -> R.string.lamp_incandescent
    LampClassifier.Type.DAYLIGHT -> R.string.lamp_daylight
    LampClassifier.Type.UNKNOWN -> R.string.lamp_unknown
}

@Composable
private fun lampClues(r: LampClassifier.Result): String {
    val f = r.features
    val clues = buildList {
        if (f.naCore > 0.45) add(stringResource(R.string.lamp_clue_na_lines))
        else if (f.naBroad > 0.35) add(stringResource(R.string.lamp_clue_na_broad))
        if (f.hg436 > 0.4 && f.hg546 > 0.4) add(stringResource(R.string.lamp_clue_hg))
        if (f.eu611 > 0.4) add(stringResource(R.string.lamp_clue_eu))
        if (f.ledBlue) add(stringResource(R.string.lamp_clue_led))
        if (f.lines >= 5) add(stringResource(R.string.lamp_clue_lines, f.lines))
        if (f.maxContrast < 0.15) add(stringResource(R.string.lamp_clue_smooth))
    }
    return if (clues.isEmpty()) "—" else clues.joinToString(", ")
}

// ================================= Plant fluorescence =======================================

@Composable
fun FluorescenceTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    var simScene by remember { androidx.compose.runtime.mutableStateOf(engine.simulator?.scene as? SimScene.Reflect) }
    val fr = frame
    val ref = fr?.reference?.takeIf { fr.darkCorrected }
    val a = if (fr != null && ref != null) Fluorescence.measure(fr.wavelengths, fr.counts, ref, Fluorescence.Band.O2A) else null
    val b = if (fr != null && ref != null) Fluorescence.measure(fr.wavelengths, fr.counts, ref, Fluorescence.Band.O2B) else null
    ToolPage {
        AcquisitionRow(engine)
        if (ref == null) Note(stringResource(R.string.sif_needs_white))
        else if ((a?.referenceDepth ?: 0.0) < 0.2) Note(stringResource(R.string.sif_weak_band))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.sif_f760), a?.let { "${f(it.percentOfIrradiance, 2)} %" } ?: "—", size = 28.sp, colour = OnGreen)
            BigValue(stringResource(R.string.sif_f687), b?.let { "${f(it.percentOfIrradiance, 2)} %" } ?: "—", size = 28.sp, colour = OffRed)
        }
        if (fr != null) SpectrumChart(fr.wavelengths, fr.counts, height = 200.dp, yUnit = stringResource(R.string.unit_counts), xRange = 675.0..790.0,
            bands = listOf(Band(758.5, 763.0, OnGreen.copy(alpha = 0.2f)), Band(685.5, 689.0, OffRed.copy(alpha = 0.2f))),
            overlay = ref?.let { fr.wavelengths to DoubleArray(it.size) { i -> it[i] * (a?.reflectance ?: 0.5) } })
        Section(stringResource(R.string.sif_details)) {
            KeyValue(stringResource(R.string.sif_of_radiance), a?.let { "${f(it.percentOfRadiance, 2)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.sif_reflectance), a?.let { "${f(it.reflectance * 100, 1)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.sif_depth), a?.let { "${f(it.referenceDepth * 100, 0)} %" } ?: "—", live = true)
            Text(stringResource(R.string.sif_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.FLUORESCENCE, if (a != null) SaveSpec("F760 ${f(a.percentOfIrradiance, 2)} %", a.percentOfIrradiance, "#2E7D32",
                "F760 %.2f %% of irradiance; F687 %s %%; reflectance %.1f %%".format(Locale.US, a.percentOfIrradiance, f(b?.percentOfIrradiance, 2), a.reflectance * 100)) else null)
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val targets = listOf(SimTarget.LEAF_HEALTHY, SimTarget.LEAF_STRESSED, SimTarget.TOMATO_UNRIPE, SimTarget.SOIL, SimTarget.FIELD_TRANSECT)
            ChipChoices(targets, simScene?.target, { targetLabel(it) }) { t -> val s = SimScene.Reflect(t, SimLight.DAYLIGHT); simScene = s; engine.setSimScene(s) }
        }
    }
}

// ================================= Water (UV) ===============================================

@Composable
fun WaterTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val path = params["water.path"] ?: 10.0
    val factor = params["water.factor"] ?: WaterUv.NITRATE_A220_PER_MG_N
    val doc = params["water.doc"] ?: 0.0
    var std by remember { mutableDoubleStateOf(5.0) }
    var simSample by remember { androidx.compose.runtime.mutableStateOf((engine.simulator?.scene as? SimScene.Cuvette)?.sample) }
    val fr = frame
    val abs = fr?.absorbance()
    val r = if (fr != null && abs != null) WaterUv.analyse(fr.wavelengths, abs, path, factor, doc.takeIf { it > 0 }) else null
    ToolPage {
        AcquisitionRow(engine)
        if (abs == null) Note(stringResource(R.string.conc_needs_blank))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.wat_nitrate_n), r?.let { "${f(it.nitrateN, 2)} mg/L" } ?: "—", size = 26.sp)
            BigValue(stringResource(R.string.wat_uv254), r?.let { "${f(it.uv254, 1)} m⁻¹" } ?: "—", size = 26.sp)
        }
        if (fr != null && abs != null) SpectrumChart(fr.wavelengths, abs, height = 190.dp, yUnit = stringResource(R.string.unit_absorbance), xRange = 200.0..360.0,
            markers = listOf(220.0, 254.0, 275.0))
        Section(stringResource(R.string.wat_details)) {
            KeyValue(stringResource(R.string.wat_nitrate), r?.let { "${f(it.nitrate, 1)} mg/L NO₃⁻" } ?: "—", live = true)
            KeyValue("A220 / A254 / A275", r?.let { "${f(it.a220, 3)} / ${f(it.a254, 3)} / ${f(it.a275, 3)}" } ?: "—", live = true)
            KeyValue(stringResource(R.string.wat_suva), r?.suva?.let { "${f(it, 2)} L/(mg·m) · ${stringResource(suvaRes(it))}" } ?: stringResource(R.string.wat_suva_needs_doc), live = true)
            Text(stringResource(R.string.wat_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.WATER, if (r != null) SaveSpec("NO₃-N ${f(r.nitrateN, 1)} mg/L", r.nitrateN, if (r.nitrateN > 11.3) "#C62828" else "#1565C0",
                "nitrate-N %.2f mg/L; UV254 %.1f 1/m".format(Locale.US, r.nitrateN, r.uv254) + (r.suva?.let { "; SUVA %.2f".format(Locale.US, it) } ?: "")) else null)
        }
        Section(stringResource(R.string.wat_settings)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.drk_path), path, { engine.tools.setParam("water.path", it) }, Modifier.weight(1f), 0, 1.0, 100.0, "mm")
                NumberField(stringResource(R.string.wat_doc), doc, { engine.tools.setParam("water.doc", it) }, Modifier.weight(1f), 1, 0.0, 500.0, "mg/L")
            }
            Text(stringResource(R.string.wat_cal_help, "%.3f".format(Locale.US, factor)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.wat_standard), std, { std = it }, Modifier.weight(1f), 2, 0.1, 100.0, "mg/L N")
                ActionButton(onClick = { r?.let { WaterUv.factorFromStandard(it, std) }?.takeIf { it > 0 }?.let { engine.tools.setParam("water.factor", it) } },
                    enabled = r != null, emphasis = true) { Text(stringResource(R.string.wat_calibrate)) }
            }
            if (params["water.factor"] != null) ActionButton(onClick = { engine.tools.setParam("water.factor", null) }, compact = true) { Text(stringResource(R.string.wat_reset)) }
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            val samples = listOf(SimSample.WATER_CLEAN, SimSample.WATER_NITRATE, SimSample.WATER_PEATY)
            ChipChoices(samples, simSample, { sampleLabel(it) }) { s -> simSample = s; engine.setSimScene(SimScene.Cuvette(0.0, sample = s)) }
        }
    }
}

private fun suvaRes(s: Double) = when { s < 2 -> R.string.wat_suva_low; s < 4 -> R.string.wat_suva_mixed; else -> R.string.wat_suva_high }

@Composable
fun sampleLabel(s: SimSample): String = stringResource(when (s) {
    SimSample.DYE -> R.string.sim_s_dye
    SimSample.BEER_LAGER -> R.string.sim_s_lager
    SimSample.BEER_ALE -> R.string.sim_s_ale
    SimSample.BEER_STOUT_DILUTED -> R.string.sim_s_stout
    SimSample.WINE_RED_1MM -> R.string.sim_s_red_wine
    SimSample.WINE_ROSE -> R.string.sim_s_rose
    SimSample.HONEY_LIGHT -> R.string.sim_s_honey_light
    SimSample.HONEY_AMBER -> R.string.sim_s_honey_amber
    SimSample.OLIVE_EXTRA_VIRGIN -> R.string.sim_s_evoo
    SimSample.OLIVE_VIRGIN -> R.string.sim_s_voo
    SimSample.OLIVE_OXIDISED -> R.string.sim_s_oxidised
    SimSample.WATER_CLEAN -> R.string.sim_s_water_clean
    SimSample.WATER_NITRATE -> R.string.sim_s_water_nitrate
    SimSample.WATER_PEATY -> R.string.sim_s_water_peaty
    SimSample.DNA_PURE -> R.string.sim_s_dna
    SimSample.DNA_PROTEIN -> R.string.sim_s_dna_protein
    SimSample.DNA_GUANIDINE -> R.string.sim_s_dna_guanidine
    SimSample.PROTEIN_BSA -> R.string.sim_s_bsa
})
