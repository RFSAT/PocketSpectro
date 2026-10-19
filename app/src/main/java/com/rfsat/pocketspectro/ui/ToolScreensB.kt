package com.rfsat.pocketspectro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.tools.BeerLambert
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.Kinetics
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.ToolHub
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.absorbance
import com.rfsat.pocketspectro.tools.ratio
import java.io.File
import java.util.Locale
import kotlin.random.Random

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

// ================================= Concentration ============================================

@Composable
fun ConcentrationTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.concentration.collectAsState()
    var stdValue by remember { mutableDoubleStateOf(1.0) }
    var sampleName by remember { mutableStateOf("") }
    var simConc by remember { mutableDoubleStateOf((engine.simulator?.scene as? SimScene.Cuvette)?.concentration ?: 5.0) }
    var simHidden by remember { mutableStateOf(false) }

    val fr = frame
    val absSpectrum = fr?.absorbance()
    val a = if (fr?.reference != null && fr.darkCorrected) BeerLambert.absorbance(fr.wavelengths, fr.counts, fr.reference, st.nm, st.halfWidthNm) else Double.NaN
    val cal = st.calibration
    val conc = cal?.concentration(a) ?: Double.NaN
    val inRange = cal != null && st.standards.isNotEmpty() && conc in st.standards.minOf { it.concentration } * 0.9..st.standards.maxOf { it.concentration } * 1.1

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (absSpectrum == null) {
            Text(stringResource(R.string.conc_needs_blank), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = WarnAmber)
        } else {
            SpectrumChart(fr.wavelengths, absSpectrum, height = 200.dp, yUnit = stringResource(R.string.unit_absorbance),
                bands = listOf(Band(st.nm - st.halfWidthNm, st.nm + st.halfWidthNm, OnGreen.copy(alpha = 0.25f))))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigValue(stringResource(R.string.conc_absorbance_at, f(st.nm, 0)), f(a, 3), size = 26.sp)
                if (cal != null) BigValue(stringResource(R.string.conc_result), "${f(conc, 2)} ${st.units}",
                    colour = if (inRange) OnGreen else WarnAmber, size = 26.sp)
            }
            if (cal != null && !inRange && conc.isFinite()) Text(stringResource(R.string.conc_out_of_range), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
        Section(stringResource(R.string.conc_method)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.conc_wavelength), st.nm, { v -> engine.tools.concUpdate { it.copy(nm = v) } }, Modifier.weight(1f), 1, 150.0, 2600.0, "nm")
                NumberField(stringResource(R.string.set_half_width), st.halfWidthNm, { v -> engine.tools.concUpdate { it.copy(halfWidthNm = v) } }, Modifier.weight(0.8f), 1, 0.2, 50.0, "nm")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(onClick = {
                    val ref = fr?.reference ?: return@ActionButton
                    BeerLambert.peakAbsorbanceNm(fr.wavelengths, fr.counts, ref)?.let { p -> engine.tools.concUpdate { it.copy(nm = p) } }
                }, enabled = absSpectrum != null, compact = true) { Text(stringResource(R.string.conc_use_peak)) }
                var units by remember(st.units) { mutableStateOf(st.units) }
                OutlinedTextField(units, { units = it; engine.tools.concUpdate { s -> s.copy(units = it.take(12)) } }, singleLine = true,
                    label = { Text(stringResource(R.string.conc_units)) }, modifier = Modifier.weight(1f))
            }
        }
        Section(stringResource(R.string.conc_standards)) {
            Text(stringResource(R.string.conc_standards_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.conc_standard_value), stdValue, { stdValue = it }, Modifier.weight(1f), 3, 0.0, 1e9, st.units)
                ActionButton(onClick = { if (a.isFinite()) engine.tools.concUpdate { it.copy(standards = it.standards + BeerLambert.Standard(stdValue, a)) } },
                    enabled = a.isFinite(), emphasis = true) { Text(stringResource(R.string.conc_add_standard)) }
            }
            for (s in st.standards.sortedBy { it.concentration }) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f(s.concentration, 3)} ${st.units}  →  A ${f(s.absorbance, 3)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = { engine.tools.concUpdate { it.copy(standards = it.standards - s) } }, compact = true) { Text(stringResource(R.string.action_delete)) }
            }
            if (cal != null) {
                KeyValue(stringResource(R.string.conc_fit), stringResource(R.string.conc_fit_value, "%.4g".format(Locale.US, cal.fit.slope),
                    "%.4f".format(Locale.US, cal.fit.intercept), "%.4f".format(Locale.US, cal.fit.r2)))
                val pts = st.standards.sortedBy { it.concentration }
                val xs = pts.map { it.concentration }.toDoubleArray()
                SpectrumChart(xs, pts.map { it.absorbance }.toDoubleArray(), height = 150.dp, yUnit = "A", dots = true,
                    overlay = xs to DoubleArray(xs.size) { cal.fit.y(xs[it]) })
            } else if (st.standards.isNotEmpty()) Text(stringResource(R.string.conc_need_two), style = MaterialTheme.typography.bodySmall)
            if (st.standards.isNotEmpty()) ActionButton(onClick = { engine.tools.concUpdate { it.copy(standards = emptyList()) } }, compact = true) {
                Text(stringResource(R.string.conc_clear_standards))
            }
        }
        if (cal != null && conc.isFinite()) Section(stringResource(R.string.conc_sample)) {
            OutlinedTextField(sampleName, { sampleName = it }, singleLine = true, label = { Text(stringResource(R.string.conc_sample_name)) }, modifier = Modifier.fillMaxWidth())
            SaveResultButton(engine, ToolId.CONCENTRATION, "${sampleName.ifBlank { "Sample" }}: ${f(conc, 2)} ${st.units}", conc, "#1565C0",
                "A %.3f at %.0f nm; R² %.4f".format(Locale.US, a, st.nm, cal.fit.r2))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Text(stringResource(R.string.sim_cuvette_help), style = MaterialTheme.typography.bodySmall)
            ChipChoices(listOf(0.0, 1.0, 2.0, 4.0, 8.0), if (simHidden) null else simConc, { if (it == 0.0) stringResource(R.string.sim_blank) else "${f(it, 0)} mg/L" }) { c ->
                simConc = c; simHidden = false; engine.setSimScene(SimScene.Cuvette(c))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = {
                    simConc = Random.nextDouble(0.5, 9.0); simHidden = true; engine.setSimScene(SimScene.Cuvette(simConc))
                }) { Text(stringResource(R.string.sim_unknown)) }
                if (simHidden) Text(stringResource(R.string.sim_true_value, f(simConc, 2)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ================================= Colour ===================================================

@Composable
fun ColourTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.colour.collectAsState()
    var simTarget by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Reflect)?.target) }
    val fr = frame
    val refl = fr?.ratio()
    val xyz = refl?.let { Colorimetry.reflectanceXYZ(fr.wavelengths, it) }
    val lab = xyz?.let { Colorimetry.lab(it) }
    val rgb = xyz?.let { Colorimetry.srgb(it) }
    val de = if (lab != null && st.standard != null) Colorimetry.deltaE2000(st.standard!!, lab) else null
    val conn by engine.connection.collectAsState()
    val unitWl = (conn as? com.rfsat.pocketspectro.core.Connection.Ready)?.info?.wavelengths()
    val partial = unitWl != null && unitWl.isNotEmpty() && !Colorimetry.supported(unitWl)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (partial && unitWl != null) {
            // Only part of 380-780 nm: say which colours can still be told apart.
            val r = Colorimetry.coveredRange(unitWl)
            val bands = Colorimetry.coveredBands(unitWl).map { stringResource(hueBandRes(it)) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .background(WarnAmber.copy(alpha = 0.18f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(stringResource(R.string.colour_partial_title), fontWeight = FontWeight.Bold, color = WarnAmber)
                Text(stringResource(R.string.colour_partial_warning, "%.0f".format(Locale.US, r.start), "%.0f".format(Locale.US, r.endInclusive),
                    "%.0f".format(Locale.US, Colorimetry.coverage(unitWl) * 100),
                    if (bands.isEmpty()) "—" else bands.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (lab == null || rgb == null || xyz == null) {
            Text(stringResource(R.string.colour_needs_white), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = WarnAmber)
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // No swatch from a partial range: the missing part of the spectrum would make it a guess.
                if (!partial) { Swatch(Color(rgb[0], rgb[1], rgb[2]), 72); Spacer(Modifier.width(14.dp)) }
                Column {
                    Text((if (partial) "≈ " else "") + "L* ${f(lab.l, 1)}   a* ${f(lab.a, 1)}   b* ${f(lab.b, 1)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(stringResource(R.string.colour_chroma_hue, f(lab.chroma, 1), f(lab.hueDeg, 0)), style = MaterialTheme.typography.bodySmall)
                    if (!partial) Text("X ${f(xyz.x, 1)}  Y ${f(xyz.y, 1)}  Z ${f(xyz.z, 1)}  ·  x ${f(xyz.chromaticity.first, 4)}  y ${f(xyz.chromaticity.second, 4)}",
                        style = MaterialTheme.typography.bodySmall)
                    Text((if (partial) "" else hex(rgb) + "  ·  ") + stringResource(R.string.colour_conditions), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SpectrumChart(fr.wavelengths, refl, height = 170.dp, yUnit = stringResource(R.string.unit_reflectance), xRange = 380.0..780.0)
        }
        Section(stringResource(R.string.colour_standard)) {
            val std = st.standard
            if (std == null) Text(stringResource(R.string.colour_no_standard), style = MaterialTheme.typography.bodySmall)
            else {
                KeyValue(stringResource(R.string.colour_standard), "L* ${f(std.l, 1)}  a* ${f(std.a, 1)}  b* ${f(std.b, 1)}")
                if (de != null) {
                    val pass = de <= st.tolerance
                    BigValue("ΔE₀₀", f(de, 2) + "  " + stringResource(if (pass) R.string.colour_pass else R.string.colour_fail),
                        colour = if (pass) OnGreen else OffRed, size = 24.sp)
                    Text(stringResource(R.string.colour_de_scale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(onClick = { lab?.let { l -> engine.tools.colourUpdate { it.copy(standard = l) } } }, enabled = lab != null, emphasis = true) {
                    Text(stringResource(R.string.colour_set_standard))
                }
                NumberField(stringResource(R.string.colour_tolerance), st.tolerance, { v -> engine.tools.colourUpdate { it.copy(tolerance = v) } },
                    Modifier.weight(1f), 1, 0.1, 50.0, "ΔE")
            }
            if (lab != null && rgb != null) SaveResultButton(engine, ToolId.COLOUR, "L*a*b* %.0f/%.0f/%.0f".format(Locale.US, lab.l, lab.a, lab.b) +
                (de?.let { "  ΔE %.1f".format(Locale.US, it) } ?: ""), de ?: Double.NaN, hex(rgb),
                "L* %.2f; a* %.2f; b* %.2f".format(Locale.US, lab.l, lab.a, lab.b) + (de?.let { "; ΔE00 %.2f".format(Locale.US, it) } ?: ""))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val tiles = listOf(SimTarget.TILE_RED, SimTarget.TILE_ORANGE, SimTarget.TILE_YELLOW, SimTarget.TILE_GREEN, SimTarget.TILE_BLUE,
                SimTarget.TILE_GREY, SimTarget.WHITE, SimTarget.TOMATO_UNRIPE, SimTarget.TOMATO_RIPE)
            ChipChoices(tiles, simTarget, { targetLabel(it) }) { t -> simTarget = t; engine.setSimScene(SimScene.Reflect(t)) }
        }
    }
}

// ================================= Light source / LED ======================================

@Composable
fun LightTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.light.collectAsState()
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    var knownLux by remember { mutableDoubleStateOf(500.0) }
    val fr = frame
    val resp = st.response?.takeIf { fr != null && it.size == fr.counts.size }
    val spd = fr?.let { LightMetrics.corrected(it.counts, resp) }
    val r = if (fr != null && spd != null) LightMetrics.analyse(fr.wavelengths, spd) else null

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (fr != null && spd != null) SpectrumChart(fr.wavelengths, spd, height = 210.dp,
            yUnit = stringResource(if (resp != null) R.string.unit_relative else R.string.unit_counts))
        if (r != null) {
            Row {
                BigValue(stringResource(R.string.light_peak), r.peak?.let { "${f(it.nm, 1)} nm" } ?: "—", size = 24.sp)
                r.cct?.let { BigValue(stringResource(R.string.light_cct), "${f(it, 0)} K", size = 24.sp) }
            }
            Section(stringResource(R.string.light_metrics)) {
                KeyValue(stringResource(R.string.light_fwhm), r.peak?.fwhmNm?.let { "${f(it, 1)} nm" } ?: "—")
                KeyValue(stringResource(R.string.light_centroid), r.peak?.centroidNm?.let { "${f(it, 1)} nm" } ?: "—")
                r.xy?.let { KeyValue(stringResource(R.string.light_xy), "x ${f(it.first, 4)}  y ${f(it.second, 4)}") }
                r.parFraction?.let { KeyValue(stringResource(R.string.light_par), "${f(it * 100, 1)} %") }
                if (st.luxScale != null && r.relLux != null) {
                    KeyValue(stringResource(R.string.light_lux), "${f(r.relLux * st.luxScale!!, 0)} lx")
                    r.relPpfd?.let { KeyValue(stringResource(R.string.light_ppfd), "${f(it * st.luxScale!!, 1)} µmol/m²/s") }
                }
                Text(stringResource(if (resp != null) R.string.light_corrected else R.string.light_uncorrected),
                    style = MaterialTheme.typography.bodySmall, color = if (resp != null) OnGreen else WarnAmber)
                SaveResultButton(engine, ToolId.LIGHT, listOfNotNull(r.peak?.let { "%.0f nm".format(Locale.US, it.nm) },
                    r.cct?.let { "%.0f K".format(Locale.US, it) }).joinToString(" · "), r.cct ?: (r.peak?.nm ?: Double.NaN), "#F9A825",
                    listOfNotNull(r.peak?.let { "FWHM %.1f nm".format(Locale.US, it.fwhmNm) }, r.xy?.let { "x %.4f y %.4f".format(Locale.US, it.first, it.second) },
                        if (st.luxScale != null && r.relLux != null) "%.0f lx".format(Locale.US, r.relLux * st.luxScale!!) else null).joinToString("; "))
            }
        }
        Section(stringResource(R.string.light_calibration)) {
            Text(stringResource(R.string.light_calibration_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.light_lamp_k), st.lampKelvin, { v -> engine.tools.lightUpdate { it.copy(lampKelvin = v) } }, Modifier.weight(1f), 0, 1000.0, 10000.0, "K")
                ActionButton(onClick = {
                    val frm = fr ?: return@ActionButton
                    engine.tools.lightUpdate { it.copy(response = LightMetrics.responseFromLamp(frm.wavelengths, frm.counts, it.lampKelvin)) }
                }, enabled = fr != null && fr.darkCorrected, emphasis = true) { Text(stringResource(R.string.light_use_lamp)) }
            }
            if (resp != null) ActionButton(onClick = { engine.tools.lightUpdate { it.copy(response = null) } }, compact = true) { Text(stringResource(R.string.light_clear_cal)) }
            Text(stringResource(R.string.light_lux_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.light_known_lux), knownLux, { knownLux = it }, Modifier.weight(1f), 0, 0.1, 2e5, "lx")
                ActionButton(onClick = { r?.relLux?.takeIf { it > 0 }?.let { rel -> engine.tools.lightUpdate { it.copy(luxScale = knownLux / rel) } } },
                    enabled = r?.relLux != null && resp != null) { Text(stringResource(R.string.light_set_scale)) }
            }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val list = listOf(SimEmitter.LED_WHITE_COOL, SimEmitter.LED_WHITE_WARM, SimEmitter.LED_RED, SimEmitter.LED_GREEN, SimEmitter.LED_BLUE,
                SimEmitter.LED_GROW, SimEmitter.LED_IR_850, SimEmitter.LED_IR_940, SimEmitter.LASER_532, SimEmitter.LASER_650, SimEmitter.LASER_785,
                SimEmitter.LAMP_2856K)
            ChipChoices(list, simEmitter, { emitterLabel(it) }) { e -> simEmitter = e; engine.setSimScene(SimScene.Emit(e)) }
            Text(stringResource(R.string.sim_lamp_tip), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ================================= Kinetics / time series ==================================

@Composable
fun KineticsTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.kinetics.collectAsState()
    val context = LocalContext.current
    var c0 by remember { mutableDoubleStateOf(8.0) }
    var kRate by remember { mutableDoubleStateOf(0.01) }
    val current = frame?.let { engine.tools.kineticsValue(it, st) }
    val fit = Kinetics.firstOrder(st.points)
    val unit = stringResource(when (st.quantity) {
        ToolHub.KQuantity.ABSORBANCE -> R.string.unit_absorbance
        ToolHub.KQuantity.RATIO -> R.string.unit_ratio
        ToolHub.KQuantity.COUNTS -> R.string.unit_counts
    })

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Section(stringResource(R.string.kin_quantity)) {
            ChipChoices(ToolHub.KQuantity.entries, st.quantity, { stringResource(when (it) {
                ToolHub.KQuantity.ABSORBANCE -> R.string.unit_absorbance
                ToolHub.KQuantity.RATIO -> R.string.unit_ratio
                ToolHub.KQuantity.COUNTS -> R.string.mode_counts
            }) }) { q -> engine.tools.kineticsSetup(q, st.nm) }
            NumberField(stringResource(R.string.conc_wavelength), st.nm, { v -> engine.tools.kineticsSetup(st.quantity, v) }, Modifier.fillMaxWidth(), 1, 150.0, 2600.0, "nm")
            KeyValue(stringResource(R.string.kin_now), current?.let { "${f(it, 4)} $unit" } ?: stringResource(R.string.kin_needs_ref))
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton(st.recording, stringResource(if (st.recording) R.string.kin_recording_on else R.string.kin_recording_off),
                { engine.tools.kineticsRecording(!st.recording); if (!st.recording) engine.setRunning(true) }, enabled = current != null || st.recording)
            ActionButton(onClick = { engine.tools.kineticsClear() }, enabled = st.points.isNotEmpty()) { Text(stringResource(R.string.action_clear)) }
            ActionButton(onClick = { shareSeries(context, engine, st, unit) }, enabled = st.points.isNotEmpty()) { Text(stringResource(R.string.res_share_csv)) }
        }
        if (st.points.size >= 2) {
            val xs = st.points.map { it.tSec }.toDoubleArray()
            val overlay = fit?.let { fr -> xs to DoubleArray(xs.size) { fr.value(xs[it]) } }
            SpectrumChart(xs, st.points.map { it.value }.toDoubleArray(), height = 220.dp, yUnit = unit, overlay = overlay)
            Text(stringResource(R.string.kin_time_axis), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        }
        Section(stringResource(R.string.kin_analysis)) {
            KeyValue(stringResource(R.string.kin_points), st.points.size.toString())
            KeyValue(stringResource(R.string.kin_duration), st.points.lastOrNull()?.let { "${f(it.tSec, 0)} s" } ?: "—")
            KeyValue(stringResource(R.string.kin_initial_rate), Kinetics.initialRate(st.points)?.let { "${"%.3g".format(Locale.US, it)} $unit/s" } ?: "—")
            if (fit != null) {
                KeyValue(stringResource(R.string.kin_k), "${"%.4g".format(Locale.US, fit.k)} 1/s")
                KeyValue(stringResource(R.string.kin_half_life), "${f(fit.halfLifeSec, 1)} s")
                KeyValue("R²", f(fit.r2, 4))
            }
            Text(stringResource(R.string.kin_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Text(stringResource(R.string.sim_reaction_help), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.sim_c0), c0, { c0 = it }, Modifier.weight(1f), 1, 0.1, 20.0, "mg/L")
                NumberField(stringResource(R.string.kin_k), kRate, { kRate = it }, Modifier.weight(1f), 4, 0.0001, 1.0, "1/s")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { engine.setSimScene(SimScene.Cuvette(c0, SimScene.Reaction(c0, kRate, System.currentTimeMillis()))) }, emphasis = true) {
                    Text(stringResource(R.string.sim_start_reaction))
                }
                ActionButton(onClick = { engine.setSimScene(SimScene.Cuvette(c0)) }) { Text(stringResource(R.string.sim_reset_reaction)) }
            }
        }
    }
}

private fun shareSeries(context: android.content.Context, engine: AcquisitionEngine, st: ToolHub.KineticsState, unit: String) {
    val dir = File(engine.sessionsRoot, "kinetics").apply { mkdirs() }
    val f = File(dir, "kinetics_%tY%<tm%<td_%<tH%<tM%<tS.csv".format(java.util.Date(st.startMs)))
    f.writeText(buildString {
        append("# quantity=${st.quantity.name} wavelength_nm=${st.nm} half_width_nm=${st.halfWidthNm} unit=$unit\n")
        append("t_s,value\n")
        for (p in st.points) append("%.3f,%.6g\n".format(Locale.US, p.tSec, p.value))
    })
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(i, f.name)) } catch (_: Exception) {}
}

private fun hueBandRes(b: Colorimetry.HueBand) = when (b) {
    Colorimetry.HueBand.VIOLET -> R.string.colour_band_violet
    Colorimetry.HueBand.BLUE -> R.string.colour_band_blue
    Colorimetry.HueBand.GREEN -> R.string.colour_band_green
    Colorimetry.HueBand.YELLOW -> R.string.colour_band_yellow
    Colorimetry.HueBand.ORANGE -> R.string.colour_band_orange
    Colorimetry.HueBand.RED -> R.string.colour_band_red
}
