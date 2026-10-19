package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimSample
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.tools.Fluorimetry
import com.rfsat.pocketspectro.tools.GrowthCurve
import com.rfsat.pocketspectro.tools.Indicator
import com.rfsat.pocketspectro.tools.NucleicAcids
import com.rfsat.pocketspectro.tools.PhysicsLab
import com.rfsat.pocketspectro.tools.Spectra
import com.rfsat.pocketspectro.tools.TestKits
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.absorbance
import java.util.Locale

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

/** Hint text style used in the tool sections. */
@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ================================= Test-kit reader ==========================================

@Composable
fun TestKitTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val m = TestKits.Method.entries[(params["kit.method"] ?: 0.0).toInt().coerceIn(0, TestKits.Method.entries.size - 1)]
    val fr = frame
    val wl = fr?.wavelengths ?: DoubleArray(0)
    val nm = if (m == TestKits.Method.CUSTOM) params["kit.custom.nm"] ?: 550.0 else if (wl.isNotEmpty()) TestKits.wavelength(m, wl) else m.nm
    val resp = params["kit.${m.name}.resp"] ?: if (wl.isNotEmpty()) TestKits.defaultResponse(m, wl) else m.perUnit
    val abs = fr?.absorbance()
    val a = if (abs != null) TestKits.absorbance(wl, abs, nm, params["kit.path"] ?: 10.0) else null
    val c = a?.let { it / resp }
    var std by remember { mutableDoubleStateOf(1.0) }
    var simConc by remember { mutableDoubleStateOf((engine.simulator?.scene as? SimScene.Kit)?.concentration ?: 1.2) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(TestKits.Method.entries, m, { stringResource(kitRes(it)) }) { engine.tools.setParam("kit.method", it.ordinal.toDouble()) }
        }
        if (abs == null) Note(stringResource(R.string.kit_needs_blank))
        val over = c != null && m != TestKits.Method.CUSTOM && c > m.rangeMax
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.conc_result), c?.let { "${f(it, 2)} ${m.unit}" } ?: "—", colour = if (over) WarnAmber else MaterialTheme.colorScheme.primary)
        }
        Reserve(over) { Text(stringResource(R.string.kit_over_range, f(m.rangeMax, 1)), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = WarnAmber) }
        if (fr != null && abs != null) SpectrumChart(wl, abs, height = 170.dp, yUnit = stringResource(R.string.unit_absorbance), markers = listOf(nm),
            xRange = (nm - 150).coerceAtLeast(wl.first())..(nm + 150).coerceAtMost(wl.last()))
        Section(stringResource(R.string.kit_method)) {
            KeyValue(stringResource(R.string.conc_wavelength), "${f(nm, 0)} nm" + if (m.altNm != null && nm == m.altNm) " " + stringResource(R.string.kit_alt_band) else "", live = true)
            KeyValue(stringResource(R.string.conc_absorbance_at, f(nm, 0)), f(a, 3), live = true)
            KeyValue(stringResource(R.string.kit_response), "${f(resp, 3)} A / ${m.unit.ifEmpty { "unit" }}" +
                if (params["kit.${m.name}.resp"] == null) " " + stringResource(R.string.kit_typical) else "", live = true)
            Hint(stringResource(kitHelpRes(m)))
            if (m == TestKits.Method.CUSTOM) NumberField(stringResource(R.string.conc_wavelength), nm, { engine.tools.setParam("kit.custom.nm", it) }, Modifier.fillMaxWidth(), 0, 200.0, 1100.0, "nm")
            NumberField(stringResource(R.string.drk_path), params["kit.path"] ?: 10.0, { engine.tools.setParam("kit.path", it) }, Modifier.fillMaxWidth(), 0, 1.0, 100.0, "mm")
            Hint(stringResource(R.string.kit_cal_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.conc_standard_value), std, { std = it }, Modifier.weight(1f), 3, 0.0001, 1e6, m.unit)
                ActionButton(onClick = { a?.takeIf { it > 0 }?.let { engine.tools.setParam("kit.${m.name}.resp", it / std) } }, enabled = a != null && a > 0, emphasis = true) {
                    Text(stringResource(R.string.wat_calibrate))
                }
            }
            if (params["kit.${m.name}.resp"] != null) ActionButton(onClick = { engine.tools.setParam("kit.${m.name}.resp", null) }, compact = true) { Text(stringResource(R.string.wat_reset)) }
            if (c != null) SaveResultButton(engine, ToolId.TEST_KIT, "${stringResource(kitRes(m))}: ${f(c, 2)} ${m.unit}", c, "#00897B",
                "%s %.3f %s; A %.3f at %.0f nm".format(Locale.US, m.name, c, m.unit, a, nm))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_kit_help, f(simConc, 2), m.unit))
            ChipChoices(listOf(0.25, 0.5, 1.0), (simConc / m.rangeMax).takeIf { it in listOf(0.25, 0.5, 1.0) }, { "${f(it * m.rangeMax, 2)} ${m.unit}" }) { k ->
                simConc = k * m.rangeMax; engine.setSimScene(SimScene.Kit(m.ordinal, simConc))
            }
            ActionButton(onClick = { std = simConc }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
        }
    }
}

private fun kitRes(m: TestKits.Method) = when (m) {
    TestKits.Method.CHLORINE_DPD -> R.string.kit_chlorine
    TestKits.Method.PHOSPHATE -> R.string.kit_phosphate
    TestKits.Method.AMMONIA -> R.string.kit_ammonia
    TestKits.Method.NITRITE -> R.string.kit_nitrite
    TestKits.Method.IRON -> R.string.kit_iron
    TestKits.Method.CUSTOM -> R.string.em_preset_custom
}

private fun kitHelpRes(m: TestKits.Method) = when (m) {
    TestKits.Method.CHLORINE_DPD -> R.string.kit_chlorine_help
    TestKits.Method.PHOSPHATE -> R.string.kit_phosphate_help
    TestKits.Method.AMMONIA -> R.string.kit_ammonia_help
    TestKits.Method.NITRITE -> R.string.kit_nitrite_help
    TestKits.Method.IRON -> R.string.kit_iron_help
    TestKits.Method.CUSTOM -> R.string.kit_custom_help
}

// ================================= pH (indicator) ===========================================

@Composable
fun PhTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val dye = Indicator.Dye.entries[(params["ph.dye"] ?: 1.0).toInt().coerceIn(0, Indicator.Dye.entries.size - 1)]
    val stored = listOf("e1", "e2", "e3").map { params["ph.${dye.name}.$it"] }
    val ends = if (stored.all { it != null }) Indicator.Ends(stored[0]!!, stored[1]!!, stored[2]!!) else Indicator.defaultEnds(dye)
    var acid by remember(dye) { mutableStateOf<DoubleArray?>(null) }
    var simPh by remember { mutableDoubleStateOf((engine.simulator?.scene as? SimScene.Indicator)?.pH ?: 7.2) }
    val fr = frame
    val abs = fr?.absorbance()
    val r = if (fr != null && abs != null) Indicator.pH(fr.wavelengths, abs, dye, ends) else null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(Indicator.Dye.entries, dye, { stringResource(dyeRes(it)) }) { engine.tools.setParam("ph.dye", it.ordinal.toDouble()) }
        }
        if (abs == null) Note(stringResource(R.string.ph_needs_blank))
        BigValue("pH", f(r?.pH, 2), colour = if (r != null && !r.inRange) WarnAmber else MaterialTheme.colorScheme.primary)
        Reserve(r != null && !r.inRange) {
            Text(stringResource(R.string.ph_out_of_range, f(dye.range.start, 1), f(dye.range.endInclusive, 1)), Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = WarnAmber)
        }
        if (fr != null && abs != null) SpectrumChart(fr.wavelengths, abs, height = 170.dp, yUnit = stringResource(R.string.unit_absorbance), xRange = 380.0..720.0,
            markers = listOf(dye.acidNm, dye.baseNm))
        Section(stringResource(R.string.ph_details)) {
            KeyValue(stringResource(R.string.ph_ratio), f(r?.ratio, 3), live = true)
            KeyValue("pKa", "${f(dye.pKa, 2)} · ${f(dye.acidNm, 0)} / ${f(dye.baseNm, 0)} nm")
            KeyValue(stringResource(R.string.ph_ends), stringResource(if (stored.all { it != null }) R.string.ph_ends_measured else R.string.ph_ends_default))
            Hint(stringResource(R.string.ph_ends_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { acid = abs?.copyOf() }, enabled = abs != null) { Text(stringResource(if (acid != null) R.string.ph_acid_stored else R.string.ph_store_acid)) }
                ActionButton(onClick = {
                    val ac = acid ?: return@ActionButton; val b = abs ?: return@ActionButton; val w = fr.wavelengths
                    val e = Indicator.ends(w, ac, b, dye)
                    engine.tools.setParam("ph.${dye.name}.e1", e.e1); engine.tools.setParam("ph.${dye.name}.e2", e.e2); engine.tools.setParam("ph.${dye.name}.e3", e.e3)
                    acid = null
                }, enabled = acid != null && abs != null, emphasis = true) { Text(stringResource(R.string.ph_store_base)) }
            }
            if (stored.all { it != null }) ActionButton(onClick = { listOf("e1", "e2", "e3").forEach { engine.tools.setParam("ph.${dye.name}.$it", null) } }, compact = true) {
                Text(stringResource(R.string.wat_reset))
            }
            Hint(stringResource(R.string.ph_help))
            if (r != null) SaveResultButton(engine, ToolId.PH, "pH ${f(r.pH, 2)}", r.pH, "#7B1FA2", "pH %.3f; %s; R %.4f".format(Locale.US, r.pH, dye.name, r.ratio))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_ph_help, f(simPh, 2)))
            ChipChoices(listOf(2.0, dye.pKa - 0.6, dye.pKa, dye.pKa + 0.5, 12.0), simPh, { f(it, 1) }) { p -> simPh = p; engine.setSimScene(SimScene.Indicator(dye.ordinal, p)) }
        }
    }
}

private fun dyeRes(d: Indicator.Dye) = when (d) {
    Indicator.Dye.BROMOCRESOL_GREEN -> R.string.dye_bcg
    Indicator.Dye.BROMOTHYMOL_BLUE -> R.string.dye_btb
    Indicator.Dye.PHENOL_RED -> R.string.dye_pr
    Indicator.Dye.M_CRESOL_PURPLE -> R.string.dye_mcp
    Indicator.Dye.THYMOL_BLUE -> R.string.dye_tb
}

// ================================= Fluorimeter ==============================================

@Composable
fun FluorimeterTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val p = Fluorimetry.Preset.entries[(params["fl.preset"] ?: (engine.simulator?.scene as? SimScene.Fluor)?.preset?.toDouble() ?: 0.0).toInt().coerceIn(0, Fluorimetry.Preset.entries.size - 1)]
    val k = params["fl.${p.name}.k"]
    var std by remember { mutableDoubleStateOf(10.0) }
    var simConc by remember { mutableDoubleStateOf((engine.simulator?.scene as? SimScene.Fluor)?.concentration ?: 10.0) }
    val fr = frame
    val excess = if (fr != null && fr.reference != null && fr.darkCorrected) DoubleArray(fr.counts.size) { fr.counts[it] - fr.reference[it] } else null
    val r = if (fr != null && excess != null) Fluorimetry.measure(fr.wavelengths, excess, fr.integrationUs, p) else null
    val conc = if (r != null && k != null && k > 0) r.signal / k else null
    val quant = p != Fluorimetry.Preset.SECURITY_UV
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(Fluorimetry.Preset.entries, p, { stringResource(fluorRes(it)) }) {
                engine.tools.setParam("fl.preset", it.ordinal.toDouble()); engine.simulator?.let { _ -> engine.setSimScene(SimScene.Fluor(it.ordinal, simConc)) }
            }
        }
        if (excess == null) Note(stringResource(R.string.fl_needs_blank))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (quant) BigValue(stringResource(R.string.conc_result), conc?.let { "${f(it, 2)} ${p.unit}" } ?: if (r != null) stringResource(R.string.fl_not_calibrated) else "—", size = 26.sp)
            BigValue(stringResource(R.string.fl_peak), r?.peakNm?.let { "${f(it, 0)} nm" } ?: "—", size = 26.sp)
        }
        if (fr != null && excess != null) SpectrumChart(fr.wavelengths, excess, height = 190.dp, yUnit = stringResource(R.string.unit_counts),
            bands = listOf(Band(p.emLo, p.emHi, OnGreen.copy(alpha = 0.18f))), markers = listOf(p.ledNm))
        Section(stringResource(R.string.fl_details)) {
            KeyValue(stringResource(R.string.fl_excitation), "${f(p.ledNm, 0)} nm LED · ${stringResource(R.string.fl_window)} ${f(p.emLo, 0)}–${f(p.emHi, 0)} nm")
            KeyValue(stringResource(R.string.fl_signal), r?.let { "%.4g".format(Locale.US, it.signal) + " counts/s" } ?: "—", live = true)
            KeyValue(stringResource(R.string.fl_leak), r?.let { "%.3g".format(Locale.US, it.leakage) + " counts/s" } ?: "—", live = true)
            Hint(stringResource(fluorHelpRes(p)))
            if (quant) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(stringResource(R.string.conc_standard_value), std, { std = it }, Modifier.weight(1f), 3, 0.0001, 1e6, p.unit)
                    ActionButton(onClick = { r?.signal?.takeIf { it > 0 }?.let { engine.tools.setParam("fl.${p.name}.k", it / std) } }, enabled = r != null && r.signal > 0, emphasis = true) {
                        Text(stringResource(R.string.wat_calibrate))
                    }
                }
                Hint(stringResource(R.string.fl_cal_help))
            }
            if (r != null) SaveResultButton(engine, ToolId.FLUORIMETER, conc?.let { "${stringResource(fluorRes(p))}: ${f(it, 2)} ${p.unit}" } ?: stringResource(fluorRes(p)),
                conc ?: r.signal, "#F57F17", "signal %.4g counts/s; peak %s nm".format(Locale.US, r.signal, f(r.peakNm, 0)))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_fl_help))
            ChipChoices(listOf(2.0, 5.0, 10.0, 20.0), simConc, { "${f(it, 0)} ${p.unit}" }) { c -> simConc = c; engine.setSimScene(SimScene.Fluor(p.ordinal, c)) }
            ActionButton(onClick = { std = simConc }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
        }
    }
}

private fun fluorRes(p: Fluorimetry.Preset) = when (p) {
    Fluorimetry.Preset.CHLOROPHYLL_A -> R.string.fl_chl
    Fluorimetry.Preset.PHYCOCYANIN -> R.string.fl_pc
    Fluorimetry.Preset.FLUORESCEIN -> R.string.fl_fluorescein
    Fluorimetry.Preset.QUININE -> R.string.fl_quinine
    Fluorimetry.Preset.OIL -> R.string.fl_oil
    Fluorimetry.Preset.SECURITY_UV -> R.string.fl_security
}

private fun fluorHelpRes(p: Fluorimetry.Preset) = when (p) {
    Fluorimetry.Preset.CHLOROPHYLL_A -> R.string.fl_chl_help
    Fluorimetry.Preset.PHYCOCYANIN -> R.string.fl_pc_help
    Fluorimetry.Preset.FLUORESCEIN -> R.string.fl_fluorescein_help
    Fluorimetry.Preset.QUININE -> R.string.fl_quinine_help
    Fluorimetry.Preset.OIL -> R.string.fl_oil_help
    Fluorimetry.Preset.SECURITY_UV -> R.string.fl_security_help
}

// ================================= DNA and protein ==========================================

@Composable
fun NucleicTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val kind = NucleicAcids.Kind.entries[(params["na.kind"] ?: 0.0).toInt().coerceIn(0, 3)]
    var simSample by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Cuvette)?.sample) }
    val fr = frame
    val abs = fr?.absorbance()
    val r = if (fr != null && abs != null) NucleicAcids.analyse(fr.wavelengths, abs, kind, params["na.path"] ?: 10.0, params["na.dilution"] ?: 1.0) else null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(NucleicAcids.Kind.entries, kind, { stringResource(when (it) { NucleicAcids.Kind.DS_DNA -> R.string.na_dsdna; NucleicAcids.Kind.RNA -> R.string.na_rna
                NucleicAcids.Kind.SS_DNA -> R.string.na_ssdna; NucleicAcids.Kind.PROTEIN -> R.string.na_protein }) }) { engine.tools.setParam("na.kind", it.ordinal.toDouble()) }
        }
        if (abs == null) Note(stringResource(R.string.na_needs_blank))
        val good280 = r != null && (if (kind == NucleicAcids.Kind.PROTEIN) r.r260280 < 0.7 else r.r260280 in (if (kind == NucleicAcids.Kind.RNA) 1.9..2.2 else 1.75..2.0))
        val good230 = r != null && kind != NucleicAcids.Kind.PROTEIN && r.r260230 in 1.8..2.4
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.conc_result), r?.let { "${f(it.concentration, if (kind == NucleicAcids.Kind.PROTEIN) 0 else 1)} µg/mL" } ?: "—", size = 26.sp)
            BigValue("A260/A280", f(r?.r260280, 2), size = 26.sp, colour = if (r == null) MaterialTheme.colorScheme.primary else if (good280) OnGreen else WarnAmber)
        }
        if (fr != null && abs != null) SpectrumChart(fr.wavelengths, abs, height = 180.dp, yUnit = stringResource(R.string.unit_absorbance), xRange = 220.0..340.0,
            markers = listOf(230.0, 260.0, 280.0, 320.0))
        Section(stringResource(R.string.na_details)) {
            KeyValue("A260/A230", f(r?.r260230, 2) + if (r != null && kind != NucleicAcids.Kind.PROTEIN) "  " + stringResource(if (good230) R.string.colour_pass else R.string.na_check) else "", live = true)
            KeyValue("A230 · A260 · A280", r?.let { "${f(it.a230, 3)} · ${f(it.a260, 3)} · ${f(it.a280, 3)}" } ?: "—", live = true)
            KeyValue(stringResource(R.string.na_background), f(r?.a320, 3), live = true)
            Hint(stringResource(R.string.na_help))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.drk_path), params["na.path"] ?: 10.0, { engine.tools.setParam("na.path", it) }, Modifier.weight(1f), 1, 0.1, 100.0, "mm")
                NumberField(stringResource(R.string.drk_dilution), params["na.dilution"] ?: 1.0, { engine.tools.setParam("na.dilution", it) }, Modifier.weight(1f), 1, 1.0, 1e4, "×")
            }
            if (r != null) SaveResultButton(engine, ToolId.NUCLEIC, "${f(r.concentration, 1)} µg/mL · ${f(r.r260280, 2)}", r.concentration, if (good280) "#2E7D32" else "#F9A825",
                "%s %.2f ug/mL; 260/280 %.2f; 260/230 %.2f".format(Locale.US, kind.name, r.concentration, r.r260280, r.r260230))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            ChipChoices(listOf(SimSample.DNA_PURE, SimSample.DNA_PROTEIN, SimSample.DNA_GUANIDINE, SimSample.PROTEIN_BSA), simSample, { sampleLabel(it) }) { s ->
                simSample = s; engine.setSimScene(SimScene.Cuvette(0.0, sample = s))
            }
        }
    }
}

// ================================= Growth curve =============================================

@Composable
fun GrowthTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.growth.collectAsState()
    val params by engine.tools.params.collectAsState()
    val fr = frame
    val od = fr?.absorbance()?.let { GrowthCurve.od600(fr.wavelengths, it, params["growth.path"] ?: 10.0) }
    val fit = remember(st.points.size) { GrowthCurve.fit(st.points) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (fr?.reference == null) Note(stringResource(R.string.growth_needs_blank))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue("OD600", f(od, 3), colour = if (od != null && od > 1.0) WarnAmber else MaterialTheme.colorScheme.primary)
            BigValue(stringResource(R.string.growth_doubling), fit?.let { "${f(it.doublingMin, 0)} min" } ?: "—", size = 26.sp)
        }
        Reserve(od != null && od > 1.0) { Text(stringResource(R.string.growth_dilute), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = WarnAmber) }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleButton(st.recording, stringResource(if (st.recording) R.string.kin_recording_on else R.string.kin_recording_off), {
                engine.tools.growthRecording(!st.recording, engine.solarTimeMs(System.currentTimeMillis())); if (!st.recording) engine.setRunning(true)
            }, enabled = od != null || st.recording)
            NumberField(stringResource(R.string.log_interval), params["growth.interval"] ?: 60.0, { engine.tools.setParam("growth.interval", it) }, Modifier.weight(1f), 0, 1.0, 3600.0, "s")
        }
        if (st.points.size >= 2) {
            val xs = st.points.map { it.tSec / 3600 }.toDoubleArray()
            SpectrumChart(xs, st.points.map { it.od }.toDoubleArray(), height = 200.dp, yUnit = "OD600", dots = true,
                bands = fit?.let { listOf(Band(it.fromSec / 3600, it.toSec / 3600, OnGreen.copy(alpha = 0.18f))) } ?: emptyList())
            Hint(stringResource(R.string.growth_axis))
        }
        Section(stringResource(R.string.kin_analysis)) {
            KeyValue(stringResource(R.string.kin_points), st.points.size.toString(), live = true)
            KeyValue(stringResource(R.string.growth_mu), fit?.let { "${f(it.muPerHour, 3)} h⁻¹ (R² ${f(it.r2, 3)})" } ?: "—", live = true)
            KeyValue(stringResource(R.string.growth_lag), fit?.let { "${f(it.lagMin, 0)} min" } ?: "—", live = true)
            KeyValue(stringResource(R.string.growth_max), fit?.let { f(it.maxOd, 3) } ?: "—", live = true)
            Hint(stringResource(R.string.growth_help))
            if (fit != null) SaveResultButton(engine, ToolId.GROWTH, "µ ${f(fit.muPerHour, 2)} /h · td ${f(fit.doublingMin, 0)} min", fit.muPerHour, "#6D4C41",
                "mu %.3f 1/h; doubling %.1f min; lag %.0f min; max OD %.3f".format(Locale.US, fit.muPerHour, fit.doublingMin, fit.lagMin, fit.maxOd))
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            Hint(stringResource(R.string.sim_growth_help))
            ActionButton(onClick = { engine.setSimScene(SimScene.Culture(System.currentTimeMillis())); engine.tools.growthRecording(false, 0L) }) { Text(stringResource(R.string.sim_new_culture)) }
        }
    }
}

// ================================= Physics lab ==============================================

@Composable
fun PhysicsTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    var leds by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var volts by remember { mutableDoubleStateOf(2.0) }
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val fr = frame
    val lines = fr?.let { PhysicsLab.lines(it.wavelengths, it.counts) } ?: emptyList()
    val id = if (fr != null) PhysicsLab.identify(lines, fr.wavelengths) else emptyList()
    val best = id.firstOrNull()?.takeIf { it.score >= 0.4 }
    val ryd = PhysicsLab.rydberg(lines)
    val peak = fr?.let { Spectra.peak(it.wavelengths, Spectra.smooth(it.counts, 2)) }
    val h = PhysicsLab.planck(leds)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        BigValue(stringResource(R.string.phys_element), best?.let { "${it.element} (${it.found}/${it.expected})" } ?: if (lines.isEmpty()) "—" else stringResource(R.string.ram_no_match),
            size = 24.sp, colour = if (best != null) OnGreen else MaterialTheme.colorScheme.primary)
        if (fr != null) SpectrumChart(fr.wavelengths, fr.counts, height = 190.dp, yUnit = stringResource(R.string.unit_counts),
            markers = best?.let { b -> PhysicsLab.ELEMENTS.first { it.first == b.element }.second.toList() } ?: emptyList())
        Section(stringResource(R.string.phys_lines)) {
            KeyValue(stringResource(R.string.ram_peaks), if (lines.isEmpty()) "—" else lines.take(6).joinToString(", ") { f(it.nm, 1) } + " nm", live = true)
            for (k in 1..2) { val m = id.getOrNull(k); KeyValue(m?.element ?: "—", m?.let { "${f(it.score * 100, 0)} %" } ?: "", live = true) }
            Hint(stringResource(R.string.phys_lines_help))
        }
        Section(stringResource(R.string.phys_rydberg)) {
            KeyValue("R", ryd?.let { "%.5e m⁻¹".format(Locale.US, it.r) } ?: "—", live = true)
            KeyValue(stringResource(R.string.phys_error), ryd?.let { "${"%+.3f".format(Locale.US, it.errorPercent)} % (R_H 1.09678e7)" } ?: "—", live = true)
            KeyValue(stringResource(R.string.phys_balmer), ryd?.lines?.joinToString(" · ") { (n, nm) -> "n=$n ${f(nm, 2)}" } ?: "—", live = true)
            Hint(stringResource(R.string.phys_rydberg_help))
        }
        Section(stringResource(R.string.phys_planck)) {
            Hint(stringResource(R.string.phys_planck_help))
            KeyValue(stringResource(R.string.light_peak), peak?.let { "${f(it.nm, 1)} nm" } ?: "—", live = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.phys_threshold), volts, { volts = it }, Modifier.weight(1f), 3, 0.5, 5.0, "V")
                ActionButton(onClick = { peak?.let { leds = leds + (it.nm to volts) } }, enabled = peak != null, emphasis = true) { Text(stringResource(R.string.phys_add_led)) }
            }
            for ((nm, v) in leds) KeyValue("${f(nm, 1)} nm", "${f(v, 3)} V")
            KeyValue("h", h?.let { "%.4e J·s (${"%+.2f".format(Locale.US, it.errorPercent)} %)".format(Locale.US, it.h) } ?: stringResource(R.string.phys_two_leds), live = true)
            if (leds.isNotEmpty()) ActionButton(onClick = { leds = emptyList() }, compact = true) { Text(stringResource(R.string.action_clear)) }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val list = listOf(SimEmitter.H_TUBE, SimEmitter.HE_TUBE, SimEmitter.NE_TUBE, SimEmitter.HG_AR_LAMP, SimEmitter.LED_BLUE, SimEmitter.LED_GREEN,
                SimEmitter.LED_YELLOW, SimEmitter.LED_RED)
            ChipChoices(list, simEmitter, { emitterLabel(it) }) { e -> simEmitter = e; engine.setSimScene(SimScene.Emit(e)) }
            peak?.takeIf { simEmitter?.name?.startsWith("LED") == true }?.let { pk ->
                Hint(stringResource(R.string.sim_led_volts, f(1239.84 / pk.nm, 3)))
                ActionButton(onClick = { volts = (1239.84 / pk.nm * 1000).toLong() / 1000.0 }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
            }
        }
    }
}
