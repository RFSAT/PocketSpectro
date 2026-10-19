package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.tools.EmissionLines
import com.rfsat.pocketspectro.tools.SpectralLibrary
import com.rfsat.pocketspectro.tools.Spectra
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.Vegetation
import com.rfsat.pocketspectro.tools.ratio
import com.rfsat.pocketspectro.sim.SimOptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

// ================================= Emission line monitor =================================

@Composable
fun EmissionTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.emission.collectAsState()
    val cfg = st.config
    var customText by remember { mutableStateOf(EmissionLines.formatCustom(cfg.custom)) }
    var simChoice by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        val fr = frame
        if (fr != null) {
            val present = st.lines.filter { it.present }
            SpectrumChart(fr.wavelengths, fr.counts, height = 230.dp, yUnit = stringResource(R.string.unit_counts),
                markers = st.lines.map { it.line.nm },
                bands = present.map { Band(it.line.nm - 1.5, it.line.nm + 1.5, OnGreen.copy(alpha = 0.22f)) })
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(st.armed, stringResource(if (st.armed) R.string.em_alarm_on else R.string.em_alarm_off), { engine.tools.emissionArm(!st.armed) })
        }
        Section(stringResource(R.string.em_lines)) {
            ChipChoices(EmissionLines.Preset.entries, cfg.preset, { stringResource(when (it) {
                EmissionLines.Preset.FLAME -> R.string.em_preset_flame
                EmissionLines.Preset.LAMP -> R.string.em_preset_lamp
                EmissionLines.Preset.LASER -> R.string.em_preset_laser
                EmissionLines.Preset.CUSTOM -> R.string.em_preset_custom
            }) }) { p -> engine.tools.emissionConfig(cfg.copy(preset = p)) }
            if (cfg.preset == EmissionLines.Preset.CUSTOM) {
                OutlinedTextField(customText, { customText = it }, label = { Text(stringResource(R.string.em_custom_label)) },
                    modifier = Modifier.fillMaxWidth())
                ActionButton(onClick = { engine.tools.emissionConfig(cfg.copy(custom = EmissionLines.parseCustom(customText))) }, compact = true) {
                    Text(stringResource(R.string.em_apply))
                }
            }
            if (st.lines.isEmpty()) Text(stringResource(R.string.em_none_in_range), style = MaterialTheme.typography.bodySmall)
            for (m in st.lines) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.line.label} ${f(m.line.nm, 1)}", Modifier.weight(0.30f), style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (m.present) FontWeight.Bold else FontWeight.Normal)
                    Text(stringResource(R.string.em_line_value, f(m.centroidNm, 1), "%+.1f".format(Locale.US, m.shiftNm),
                        if (m.pctAbove.isInfinite()) "∞" else "%+.0f".format(Locale.US, m.pctAbove), f(m.snr, 0)),
                        Modifier.weight(0.58f), style = MaterialTheme.typography.bodySmall)
                    Text(if (m.present) "●" else "○", Modifier.weight(0.12f), color = if (m.present) OnGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.set_threshold), cfg.thresholdPct, { v -> engine.tools.emissionConfig(cfg.copy(thresholdPct = v)) },
                    Modifier.weight(1f), 1, 0.5, 1000.0, "%")
                NumberField(stringResource(R.string.em_min_snr), cfg.minSnr, { v -> engine.tools.emissionConfig(cfg.copy(minSnr = v)) },
                    Modifier.weight(1f), 1, 1.0, 1000.0)
            }
            Text(stringResource(R.string.em_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.history.size > 2) Section(stringResource(R.string.em_history)) {
            SpectrumChart(DoubleArray(st.history.size) { it.toDouble() }, st.history.toDoubleArray(), height = 140.dp,
                yUnit = stringResource(R.string.unit_counts))
        }
        SimPanel(engine) {
            val emitters = listOf(SimEmitter.FLAME_NA, SimEmitter.FLAME_LI, SimEmitter.FLAME_K, SimEmitter.FLAME_SR, SimEmitter.FLAME_CA,
                SimEmitter.FLAME_MIX, SimEmitter.HG_AR_LAMP, SimEmitter.LASER_405, SimEmitter.LASER_532, SimEmitter.LASER_650,
                SimEmitter.LASER_785, SimEmitter.LASER_808)
            ChipChoices(emitters, simChoice, { emitterLabel(it) }) { e -> simChoice = e; engine.setSimScene(SimScene.Emit(e)) }
        }
    }
}

@Composable
fun emitterLabel(e: SimEmitter): String = when (e) {
    SimEmitter.FLAME_NA -> stringResource(R.string.sim_flame, "Na")
    SimEmitter.FLAME_LI -> stringResource(R.string.sim_flame, "Li")
    SimEmitter.FLAME_K -> stringResource(R.string.sim_flame, "K")
    SimEmitter.FLAME_SR -> stringResource(R.string.sim_flame, "Sr")
    SimEmitter.FLAME_CA -> stringResource(R.string.sim_flame, "Ca")
    SimEmitter.FLAME_MIX -> stringResource(R.string.sim_flame, "Na + Li + K")
    SimEmitter.HG_AR_LAMP -> stringResource(R.string.sim_hg_lamp)
    SimEmitter.LASER_405 -> stringResource(R.string.sim_laser, "405")
    SimEmitter.LASER_532 -> stringResource(R.string.sim_laser, "532")
    SimEmitter.LASER_650 -> stringResource(R.string.sim_laser, "650")
    SimEmitter.LASER_785 -> stringResource(R.string.sim_laser, "785")
    SimEmitter.LASER_808 -> stringResource(R.string.sim_laser, "808")
    SimEmitter.LED_WHITE_COOL -> stringResource(R.string.sim_led_cool)
    SimEmitter.LED_WHITE_WARM -> stringResource(R.string.sim_led_warm)
    SimEmitter.LED_RED -> stringResource(R.string.sim_led_colour, stringResource(R.string.colour_red))
    SimEmitter.LED_GREEN -> stringResource(R.string.sim_led_colour, stringResource(R.string.colour_green))
    SimEmitter.LED_BLUE -> stringResource(R.string.sim_led_colour, stringResource(R.string.colour_blue))
    SimEmitter.LED_GROW -> stringResource(R.string.sim_led_grow)
    SimEmitter.LED_IR_850 -> stringResource(R.string.sim_led_ir, "850")
    SimEmitter.LED_IR_940 -> stringResource(R.string.sim_led_ir, "940")
    SimEmitter.LAMP_2856K -> stringResource(R.string.sim_lamp_a)
}

// ================================= Vegetation ==============================================

@Composable
fun VegetationTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.vegetation.collectAsState()
    val fix by engine.location.fix.collectAsState()
    val r = st.last
    var simScene by remember { mutableStateOf(engine.simulator?.scene as? SimScene.Reflect) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (r == null) {
            Text(stringResource(R.string.veg_needs_ref), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = WarnAmber)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigValue("NDVI", f(r.ndvi), colour = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(Vegetation.colour(r.ndvi))))
                Text(stringResource(when (r.health) {
                    Vegetation.Health.NONE -> R.string.veg_none
                    Vegetation.Health.SPARSE -> R.string.veg_sparse
                    Vegetation.Health.MODERATE -> R.string.veg_moderate
                    Vegetation.Health.DENSE -> R.string.veg_dense
                }), fontWeight = FontWeight.SemiBold)
            }
            Section(stringResource(R.string.veg_indices)) {
                KeyValue("NDVI", stringResource(R.string.veg_ndvi_bands, f(r.ndvi), "%.0f–%.0f".format(Locale.US, r.redNm.start, r.redNm.endInclusive),
                    "%.0f–%.0f".format(Locale.US, r.nirNm.start, r.nirNm.endInclusive)))
                KeyValue(stringResource(R.string.veg_rep), r.rep?.let { "${f(it, 1)} nm" } ?: "—")
                KeyValue(stringResource(R.string.veg_cire), f(r.cire))
                KeyValue(stringResource(R.string.veg_wbi), f(r.wbi, 3))
                KeyValue(stringResource(R.string.veg_pri), f(r.pri, 3))
                Text(stringResource(R.string.veg_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        frame?.ratio()?.let { rr ->
            SpectrumChart(frame!!.wavelengths, rr, height = 200.dp, yUnit = stringResource(R.string.unit_reflectance),
                bands = r?.let { listOf(Band(it.redNm.start, it.redNm.endInclusive, OffRed.copy(alpha = 0.18f)),
                    Band(it.nirNm.start, it.nirNm.endInclusive, OnGreen.copy(alpha = 0.18f))) } ?: emptyList())
        }
        Section(stringResource(R.string.veg_survey)) {
            Text(stringResource(R.string.veg_survey_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.recording, stringResource(if (st.recording) R.string.veg_survey_on else R.string.veg_survey_off),
                    { engine.tools.vegRecording(!st.recording); if (!st.recording) engine.setRunning(true) })
                NumberField(stringResource(R.string.log_interval), st.intervalSec, { engine.tools.vegInterval(it) }, Modifier.weight(1f), 0, 1.0, 3600.0, "s")
            }
            KeyValue(stringResource(R.string.veg_points), st.points.toString())
            KeyValue(stringResource(R.string.log_gps), fix?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) } ?: stringResource(R.string.gps_waiting))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val targets = listOf(SimTarget.LEAF_HEALTHY, SimTarget.LEAF_STRESSED, SimTarget.DRY_GRASS, SimTarget.SOIL, SimTarget.FIELD_TRANSECT)
            ChipChoices(targets, simScene?.target, { targetLabel(it) }) { t ->
                val s = SimScene.Reflect(t, simScene?.light ?: SimLight.DAYLIGHT); simScene = s; engine.setSimScene(s)
            }
            ChipChoices(SimLight.entries, simScene?.light, { stringResource(if (it == SimLight.DAYLIGHT) R.string.sim_daylight else R.string.sim_halogen) }) { l ->
                val s = SimScene.Reflect(simScene?.target ?: SimTarget.LEAF_HEALTHY, l); simScene = s; engine.setSimScene(s)
            }
        }
    }
}

@Composable
fun targetLabel(t: SimTarget): String = stringResource(when (t) {
    SimTarget.WHITE -> R.string.sim_t_white
    SimTarget.LEAF_HEALTHY -> R.string.sim_t_leaf
    SimTarget.LEAF_STRESSED -> R.string.sim_t_leaf_stressed
    SimTarget.DRY_GRASS -> R.string.sim_t_dry_grass
    SimTarget.SOIL -> R.string.sim_t_soil
    SimTarget.FIELD_TRANSECT -> R.string.sim_t_transect
    SimTarget.PAPER -> R.string.sim_t_paper
    SimTarget.COTTON -> R.string.sim_t_cotton
    SimTarget.POLYESTER -> R.string.sim_t_polyester
    SimTarget.POLYETHYLENE -> R.string.sim_t_polyethylene
    SimTarget.NYLON -> R.string.sim_t_nylon
    SimTarget.TILE_RED -> R.string.colour_red
    SimTarget.TILE_ORANGE -> R.string.colour_orange
    SimTarget.TILE_YELLOW -> R.string.colour_yellow
    SimTarget.TILE_GREEN -> R.string.colour_green
    SimTarget.TILE_BLUE -> R.string.colour_blue
    SimTarget.TILE_GREY -> R.string.colour_grey
    SimTarget.TOMATO_UNRIPE -> R.string.sim_t_tomato_unripe
    SimTarget.TOMATO_RIPE -> R.string.sim_t_tomato_ripe
})

// ================================= Spectral match ===========================================

@Composable
fun MatchTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val thr by engine.tools.matchThreshold.collectAsState()
    val libVersion by engine.tools.libraryVersion.collectAsState()
    val root = engine.sessionsRoot.parentFile ?: engine.sessionsRoot
    var library by remember { mutableStateOf(emptyList<SpectralLibrary.Entry>()) }
    LaunchedEffect(libVersion) { library = withContext(Dispatchers.IO) { SpectralLibrary.load(root) } }
    var name by remember { mutableStateOf("") }
    var simTarget by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Reflect)?.target) }
    var showLib by remember { mutableIntStateOf(0) }

    val fr = frame
    val refl = fr?.ratio()
    val quantity = if (refl != null) SpectralLibrary.Quantity.REFLECTANCE else SpectralLibrary.Quantity.COUNTS
    val current = refl ?: fr?.counts
    val matches = if (fr != null && current != null) SpectralLibrary.match(fr.wavelengths, current, quantity, library) else emptyList()
    val best = matches.firstOrNull()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (fr != null && current != null) {
            // Library spectrum scaled onto the current one for comparison.
            val overlay = best?.let { m ->
                val y = Spectra.resample(m.entry.wavelengths, m.entry.values, fr.wavelengths)
                val fit = Spectra.lineFit(y, current)
                fr.wavelengths to DoubleArray(y.size) { fit?.y(y[it]) ?: y[it] }
            }
            SpectrumChart(fr.wavelengths, current, height = 220.dp,
                yUnit = stringResource(if (refl != null) R.string.unit_reflectance else R.string.unit_counts), overlay = overlay)
            if (overlay != null) Text(stringResource(R.string.match_overlay_legend, best.entry.name), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = OverlayColor)
        }
        val verdict = when {
            library.none { it.quantity == quantity } -> stringResource(R.string.match_empty_library)
            best == null -> "—"
            best.score >= thr -> stringResource(R.string.match_yes, best.entry.name, f(best.score * 100, 1))
            else -> stringResource(R.string.match_no, f(thr * 100, 0))
        }
        BigValue(stringResource(R.string.match_result), verdict, colour = if (best != null && best.score >= thr) OnGreen else OffRed, size = 22.sp)
        if (matches.isNotEmpty()) Section(stringResource(R.string.match_candidates)) {
            for (m in matches.take(5)) KeyValue(m.entry.name, "${f(m.score * 100, 1)} %")
            NumberField(stringResource(R.string.match_threshold), thr * 100, { engine.tools.matchThreshold(it / 100) }, Modifier.fillMaxWidth(), 1, 50.0, 99.9, "%")
            Text(stringResource(R.string.match_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (best != null) SaveResultButton(engine, ToolId.MATCH, if (best.score >= thr) best.entry.name else "?",
                best.score * 100, if (best.score >= thr) "#2E7D32" else "#C62828", "score %.1f %%".format(Locale.US, best.score * 100))
        }
        Section(stringResource(R.string.match_library)) {
            Text(stringResource(if (refl != null) R.string.match_lib_help_refl else R.string.match_lib_help_counts),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.match_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                ActionButton(onClick = {
                    val c = current ?: return@ActionButton
                    val frm = fr ?: return@ActionButton
                    val model = (engine.connection.value as? com.rfsat.pocketspectro.core.Connection.Ready)?.info?.model ?: ""
                    SpectralLibrary.save(root, name.ifBlank { "Sample" }, quantity, model, frm.wavelengths, c)
                    name = ""; engine.tools.libraryChanged()
                }, enabled = current != null, emphasis = true) { Text(stringResource(R.string.match_add)) }
            }
            for (e in library.take(if (showLib > 0) library.size else 6)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.name}  ·  ${stringResource(if (e.quantity == SpectralLibrary.Quantity.REFLECTANCE) R.string.unit_reflectance else R.string.unit_counts)}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    ActionButton(onClick = { SpectralLibrary.delete(e); engine.tools.libraryChanged() }, compact = true) { Text(stringResource(R.string.action_delete)) }
                }
            }
            if (library.size > 6 && showLib == 0) ActionButton(onClick = { showLib = 1 }, compact = true) {
                Text(stringResource(R.string.fire_show_all, library.size))
            }
        }
        if (refl == null) Text(stringResource(R.string.match_ref_tip), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val mats = listOf(SimTarget.PAPER, SimTarget.COTTON, SimTarget.POLYESTER, SimTarget.POLYETHYLENE, SimTarget.NYLON, SimTarget.LEAF_HEALTHY, SimTarget.SOIL)
            ChipChoices(mats, simTarget, { targetLabel(it) }) { t -> simTarget = t; engine.setSimScene(SimScene.Reflect(t)) }
            val labels = mats.associateWith { targetLabel(it) }
            ActionButton(onClick = {
                val sim = engine.simulator ?: return@ActionButton
                val wl = sim.info.wavelengths()
                for (m in mats) SpectralLibrary.save(root, labels.getValue(m), SpectralLibrary.Quantity.REFLECTANCE, sim.info.model, wl,
                    DoubleArray(wl.size) { SimOptics.reflectance(wl[it], m, 0L) })
                engine.tools.libraryChanged()
            }) { Text(stringResource(R.string.match_demo_library)) }
        }
    }
}

/** Small coloured square for swatches. */
@Composable
fun Swatch(c: Color, size: Int = 56) {
    Row(Modifier.size(size.dp).background(c, RoundedCornerShape(10.dp))) {}
}
