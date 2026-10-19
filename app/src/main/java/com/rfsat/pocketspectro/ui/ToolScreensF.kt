@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.rfsat.pocketspectro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.Frame
import com.rfsat.pocketspectro.sim.SimClock
import com.rfsat.pocketspectro.sim.SimDisplay
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import com.rfsat.pocketspectro.sim.SimWater
import com.rfsat.pocketspectro.tools.Colorimetry
import com.rfsat.pocketspectro.tools.ColourQuality
import com.rfsat.pocketspectro.tools.DisplayCheck
import com.rfsat.pocketspectro.tools.ForelUle
import com.rfsat.pocketspectro.tools.GasColumns
import com.rfsat.pocketspectro.tools.Gems
import com.rfsat.pocketspectro.tools.LeafClip
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.Munsell
import com.rfsat.pocketspectro.tools.PhysicsLab
import com.rfsat.pocketspectro.tools.Pigments
import com.rfsat.pocketspectro.tools.Solar
import com.rfsat.pocketspectro.tools.Spectra
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.ratio
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

/**
 * Reflectance with the integration time taken into account: water and dark surfaces need a longer
 * exposure than the white panel, so both are put on a per-second basis.
 */
fun AcquisitionEngine.ratioPerSecond(fr: Frame): DoubleArray? {
    val ref = fr.reference ?: return null
    if (ref.size != fr.counts.size || !fr.darkCorrected) return null
    val refUs = reference.value?.integrationUs ?: fr.integrationUs
    val k = refUs.toDouble() / fr.integrationUs
    return DoubleArray(fr.counts.size) { if (ref[it] > 1.0) fr.counts[it] * k / ref[it] else Double.NaN }
}

/** Colour swatch of a CIE xy chromaticity (luminance normalised for display). */
fun xyColour(x: Double, y: Double): Color {
    val yy = 0.6; val xx = x / y * yy; val zz = (1 - x - y) / y * yy
    val rgb = Colorimetry.srgb(Colorimetry.XYZ(xx * 100, yy * 100, zz * 100))
    return Color(rgb[0], rgb[1], rgb[2])
}

// ================================= Water colour (Forel-Ule) =================================

@Composable
fun WaterColourTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    var simWater by remember { mutableStateOf((engine.simulator?.scene as? SimScene.WaterSurface)?.water) }
    val fr = frame
    val r = fr?.let { engine.ratioPerSecond(it) }
    val res = if (fr != null && r != null) ForelUle.analyse(fr.wavelengths, r) else null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (r == null) Note(stringResource(R.string.fu_needs_white))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.fu_index), res?.let { "FU ${it.fu}" } ?: "—")
            res?.let { Swatch(xyColour(ForelUle.XY[it.fu - 1].first, ForelUle.XY[it.fu - 1].second), 48) }
            Column(Modifier.padding(start = 10.dp)) {
                Text(res?.let { stringResource(fuClassRes(it.fu)) } ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (fr != null && r != null) SpectrumChart(fr.wavelengths, DoubleArray(r.size) { r[it] * 100 }, height = 180.dp, yUnit = "%",
            xRange = 400.0..(fr.wavelengths.last().coerceAtMost(900.0)), markers = listOf(620.0, 665.0, 705.0))
        Section(stringResource(R.string.fu_details)) {
            KeyValue(stringResource(R.string.fu_hue), res?.let { "${f(it.hueAngle, 1)}° · x ${f(it.x, 3)} y ${f(it.y, 3)}" } ?: "—", live = true)
            KeyValue(stringResource(R.string.fu_chl), res?.chlRatio?.let { f(it, 2) + "  " + stringResource(if (it > 1.0) R.string.fu_chl_high else R.string.fu_chl_low) } ?: "—", live = true)
            KeyValue(stringResource(R.string.fu_pc), res?.pcIndex?.let { f(it, 3) + "  " + stringResource(if (it > 0.25) R.string.fu_pc_present else R.string.fu_pc_absent) } ?: "—", live = true)
            KeyValue(stringResource(R.string.fu_nir), res?.nirPercent?.let { "${f(it, 2)} %" } ?: "—", live = true)
            Hint(stringResource(R.string.fu_help))
            if (res != null) SaveResultButton(engine, ToolId.WATER_COLOUR, "FU ${res.fu}", res.fu.toDouble(),
                hex(xyColour(ForelUle.XY[res.fu - 1].first, ForelUle.XY[res.fu - 1].second)),
                "FU %d; hue %.1f deg; R705/R665 %s; PC %s".format(Locale.US, res.fu, res.hueAngle, f(res.chlRatio), f(res.pcIndex, 3)))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            ChipChoices(SimWater.entries, simWater, { stringResource(when (it) {
                SimWater.CLEAR_OCEAN -> R.string.sim_w_ocean; SimWater.COASTAL -> R.string.sim_w_coastal; SimWater.ALGAL_BLOOM -> R.string.sim_w_algae
                SimWater.CYANO_BLOOM -> R.string.sim_w_cyano; SimWater.HUMIC_LAKE -> R.string.sim_w_humic }) }) { w -> simWater = w; engine.setSimScene(SimScene.WaterSurface(w)) }
        }
    }
}

private fun fuClassRes(fu: Int) = when {
    fu <= 3 -> R.string.fu_class_blue
    fu <= 5 -> R.string.fu_class_bluegreen
    fu <= 9 -> R.string.fu_class_green
    fu <= 13 -> R.string.fu_class_greenbrown
    else -> R.string.fu_class_brown
}

// ================================= Leaf clip (chlorophyll) ==================================

@Composable
fun LeafClipTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val k = params["leaf.k"] ?: LeafClip.DEFAULT_K
    var spad by remember { mutableDoubleStateOf(40.0) }
    var simChl by remember { mutableDoubleStateOf((engine.simulator?.scene as? SimScene.LeafClip)?.chlorophyll ?: 42.0) }
    val fr = frame
    val t = fr?.ratio()
    val r = if (fr != null && t != null) LeafClip.measure(fr.wavelengths, t, k) else null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (t == null) Note(stringResource(R.string.leaf_needs_empty))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.leaf_index), f(r?.spadLike, 1), colour = r?.let { if (it.spadLike < 30) WarnAmber else OnGreen } ?: MaterialTheme.colorScheme.primary)
            BigValue(stringResource(R.string.leaf_chl), r?.let { "${f(it.chlorophyll, 1)} µg/cm²" } ?: "—", size = 24.sp)
        }
        if (fr != null && t != null) SpectrumChart(fr.wavelengths, DoubleArray(t.size) { t[it] * 100 }, height = 170.dp, yUnit = "%",
            markers = listOfNotNull(650.0, r?.nirNm))
        Section(stringResource(R.string.leaf_details)) {
            KeyValue(stringResource(R.string.leaf_ratio), r?.let { "${f(it.logRatio, 3)} (650 / ${f(it.nirNm, 0)} nm)" } ?: "—", live = true)
            KeyValue(stringResource(R.string.leaf_scale), f(k, 1) + if (params["leaf.k"] == null) " " + stringResource(R.string.kit_typical) else "")
            Hint(stringResource(R.string.leaf_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.leaf_meter), spad, { spad = it }, Modifier.weight(1f), 1, 0.0, 100.0)
                ActionButton(onClick = { r?.logRatio?.takeIf { it > 0 }?.let { engine.tools.setParam("leaf.k", spad / it) } }, enabled = r != null && r.logRatio > 0, emphasis = true) {
                    Text(stringResource(R.string.wat_calibrate))
                }
            }
            if (params["leaf.k"] != null) ActionButton(onClick = { engine.tools.setParam("leaf.k", null) }, compact = true) { Text(stringResource(R.string.wat_reset)) }
            if (r != null) SaveResultButton(engine, ToolId.LEAF_CLIP, "${stringResource(R.string.leaf_index)} ${f(r.spadLike, 1)}", r.spadLike,
                if (r.spadLike < 30) "#F9A825" else "#2E7D32", "index %.1f; chl %.1f ug/cm2; log ratio %.3f".format(Locale.US, r.spadLike, r.chlorophyll, r.logRatio))
        }
        ReferencePanel(engine, RefKind.OPEN)
        SimPanel(engine) {
            ChipChoices(listOf(15.0, 30.0, 45.0, 60.0), simChl, { "${f(it, 0)} µg/cm²" }) { c -> simChl = c; engine.setSimScene(SimScene.LeafClip(c)) }
        }
    }
}

// ================================= Greenhouse light =========================================

/** Records a dark for each auto-exposure step (shutter closed or input covered). */
private suspend fun prepareDarks(engine: AcquisitionEngine) {
    val saved = engine.store.value.integrationMs
    engine.setRunning(true)
    val out = HashMap<Long, DoubleArray>()
    try {
        engine.requestShutter(false)
        for (ms in engine.tools.exposureSteps) {
            engine.store.update { it.copy(integrationMs = ms) }
            val us = engine.store.value.integrationUs
            val fs = engine.nextFrames(2) { it.integrationUs == us }
            out[us] = DoubleArray(fs[0].meanRaw.size) { i -> fs.sumOf { it.meanRaw[i] } / fs.size }
        }
        engine.tools.setDarks(out)
    } finally {
        engine.requestShutter(true)
        engine.store.update { it.copy(integrationMs = saved) }
    }
}

/** Light-tool calibration status with the dark-per-step routine; shared by the exposure loggers. */
@Composable
fun CalibrationPanel(engine: AcquisitionEngine, simTruth: (() -> Double?)? = null, truthIsPpfd: Boolean = false) {
    val light by engine.tools.light.collectAsState()
    val darks by engine.tools.darks.collectAsState()
    val shutter by engine.shutterAvailable.collectAsState()
    val frame by engine.frame.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Section(stringResource(R.string.cal_title)) {
        KeyValue(stringResource(R.string.cal_response), stringResource(if (light.response != null) R.string.ref_stored else R.string.ref_missing))
        KeyValue(stringResource(R.string.cal_scale), stringResource(if (light.luxScale != null) R.string.ref_stored else R.string.ref_missing))
        KeyValue(stringResource(R.string.cal_darks), if (darks.isEmpty()) stringResource(R.string.ref_missing) else stringResource(R.string.cal_darks_n, darks.size))
        Hint(stringResource(R.string.cal_help))
        if (!shutter) Hint(stringResource(R.string.cal_cover))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(onClick = { scope.launch { busy = true; try { prepareDarks(engine) } catch (_: Exception) {} finally { busy = false } } }, enabled = !busy, emphasis = darks.isEmpty()) {
                Text(stringResource(if (busy) R.string.cal_darks_busy else R.string.cal_prepare_darks))
            }
            ActionButton(onClick = { engine.openTool(ToolId.LIGHT) }) { Text(stringResource(R.string.cal_open_light)) }
        }
        if (engine.simulator != null && simTruth != null) {
            Hint(stringResource(if (truthIsPpfd) R.string.cal_sim_help_ppfd else R.string.cal_sim_help))
            ActionButton(onClick = { scope.launch {
                busy = true
                try {
                    calibrateOnSimLamp(engine)
                    val fr = engine.nextFrames(2).last()
                    val lm = LightMetrics.analyse(fr.wavelengths, engine.tools.spdPerSecond(fr))
                    val rel = if (truthIsPpfd) lm.relPpfd else lm.relLux
                    val truth = simTruth()
                    if (rel != null && rel > 0 && truth != null) engine.tools.lightUpdate { it.copy(luxScale = truth / rel) }
                } catch (_: Exception) {} finally { busy = false }
            } }, enabled = !busy && frame != null) { Text(stringResource(R.string.cal_sim_meter)) }
        }
    }
}

private fun simPpfd(e: SimEmitter?) = when (e) { SimEmitter.SUNLIGHT -> 1500.0; SimEmitter.CANOPY_SHADE -> 120.0; SimEmitter.LED_GROW -> 400.0; SimEmitter.LED_WHITE_WARM -> 250.0; else -> null }

@Composable
fun GreenhouseTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val st by engine.tools.plantLight.collectAsState()
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val fr = frame
    val r = st.result
    val spd = fr?.let { engine.tools.spdPerSecond(it) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (st.ppfd == null) Note(stringResource(R.string.gh_needs_cal))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue("PPFD", st.ppfd?.let { "${f(it, 0)} µmol/m²/s" } ?: "—", size = 26.sp)
            BigValue("R:FR", f(r?.rfr, 2), size = 26.sp)
        }
        if (fr != null && spd != null) SpectrumChart(fr.wavelengths, spd, height = 170.dp, yUnit = stringResource(R.string.unit_relative), xRange = 380.0..(fr.wavelengths.last().coerceAtMost(800.0)),
            bands = listOf(Band(400.0, 500.0, Color(0x223F51B5)), Band(500.0, 600.0, Color(0x2243A047)), Band(600.0, 700.0, Color(0x22E53935)), Band(700.0, 750.0, Color(0x22880E4F))))
        Section(stringResource(R.string.gh_bands)) {
            KeyValue(stringResource(R.string.gh_blue), r?.let { "${f(it.bluePercent, 1)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.gh_green), r?.let { "${f(it.greenPercent, 1)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.gh_red), r?.let { "${f(it.redPercent, 1)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.gh_farred), r?.let { "${f(it.farRedPercent, 1)} %" } ?: "—", live = true)
            KeyValue(stringResource(R.string.gh_uv), r?.let { "${f(it.uvPercent, 1)} %" } ?: "—", live = true)
            Hint(stringResource(R.string.gh_help))
        }
        Section(stringResource(R.string.gh_dli)) {
            KeyValue("DLI", "${f(st.dli, 2)} mol/m²/d", live = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(st.logging, stringResource(if (st.logging) R.string.dose_logging_on else R.string.dose_logging_off), { engine.tools.plantLogging(!st.logging); if (!st.logging) engine.setRunning(true) },
                    enabled = st.ppfd != null || st.logging)
                ActionButton(onClick = { engine.tools.plantReset() }, compact = true) { Text(stringResource(R.string.action_reset)) }
            }
            Hint(stringResource(R.string.gh_dli_help))
            val p = st.ppfd
            if (p != null) SaveResultButton(engine, ToolId.GREENHOUSE, "PPFD ${f(p, 0)}", p, "#7CB342",
                "PPFD %.0f umol/m2/s; R:FR %s; blue %s %%; DLI %.2f".format(Locale.US, p, f(r?.rfr), f(r?.bluePercent, 1), st.dli))
        }
        CalibrationPanel(engine, { simPpfd(simEmitter) }, truthIsPpfd = true)
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            ChipChoices(listOf(SimEmitter.SUNLIGHT, SimEmitter.CANOPY_SHADE, SimEmitter.LED_GROW, SimEmitter.LED_WHITE_WARM), simEmitter, { emitterLabel(it) }) { e ->
                simEmitter = e; engine.setSimScene(SimScene.Emit(e))
            }
        }
    }
}

// ================================= Ozone and NO2 columns ====================================

@Composable
fun GasColumnsTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val fix by engine.location.fix.collectAsState()
    val context = LocalContext.current
    val xsFile = remember { File(context.getExternalFilesDir(null) ?: context.filesDir, "no2_cross_section.csv") }
    var xs by remember { mutableStateOf(if (xsFile.exists()) GasColumns.parseCrossSection(xsFile.readText()) else null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            GasColumns.parseCrossSection(text)?.let { xs = it; xsFile.writeText(text) }
        } catch (_: Exception) {}
    }
    var simScene by remember { mutableStateOf(engine.simulator?.scene as? SimScene.Sun) }
    var knownDu by remember { mutableDoubleStateOf(300.0) }
    var langley by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
    var langleyOn by remember { mutableStateOf(false) }
    val fr = frame
    val site = engine.site()
    val t = fr?.let { engine.solarTimeMs(it.timeMs) }
    val pos = t?.let { Solar.position(site.first, site.second, it) }
    val m = pos?.takeIf { it.zenithDeg < 85 }?.let { Solar.airmass(it.zenithDeg) }
    val hPa = Solar.pressureAt(params["sun.height"] ?: 0.0)
    val wl = fr?.wavelengths ?: DoubleArray(0)
    val o3 = wl.isNotEmpty() && GasColumns.ozoneSupported(wl)
    val no2 = wl.isNotEmpty() && Spectra.covers(wl, GasColumns.NO2_LO, GasColumns.NO2_HI)
    val l = if (o3 && fr!!.darkCorrected) GasColumns.ozoneRatio(wl, fr.counts) else null
    val l0 = params["gas.l0"]
    val du = if (l != null && l0 != null && m != null) GasColumns.ozone(l, l0, m, hPa) else null
    LaunchedEffect(l, m, langleyOn) {
        if (langleyOn && l != null && m != null && (langley.isEmpty() || kotlin.math.abs(langley.last().first - m) > 0.02)) langley = langley + (m to l)
    }
    // NO2: differential column against the stored reference sun spectrum.
    val sigma: ((Double) -> Double)? = xs?.let { (x, y) -> { nm: Double -> Spectra.at(x, y, nm) } } ?: if (engine.simulator != null) GasColumns::syntheticNo2 else null
    val refUs = engine.reference.collectAsState().value?.integrationUs
    val no2Res = if (no2 && sigma != null && fr?.reference != null && fr.darkCorrected && refUs != null) {
        val i = DoubleArray(fr.counts.size) { fr.counts[it] / fr.integrationUs }
        val i0 = DoubleArray(fr.reference.size) { fr.reference[it] / refUs }
        GasColumns.no2Slant(wl, i, i0, sigma)
    } else null
    val mRef = params["gas.no2.mref"]
    val clean = (params["gas.no2.clean"] ?: 0.0) > 0.5
    val vcd = no2Res?.let { (scd, _) -> val dm = if (clean) m else if (m != null && mRef != null) m - mRef else null
        if (dm != null && kotlin.math.abs(dm) >= (if (clean) 0.9 else 0.5)) scd / GasColumns.MOLEC_PER_DU / dm else null }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Note(stringResource(R.string.gas_experimental))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (o3) BigValue(stringResource(R.string.gas_ozone), du?.let { "${f(it, 0)} DU" } ?: "—", size = 26.sp)
            if (no2) BigValue("NO₂", vcd?.let { "${f(it, 2)} DU" } ?: "—", size = 26.sp)
        }
        if (fr != null) SpectrumChart(wl, fr.counts, height = 170.dp, yUnit = stringResource(R.string.unit_counts),
            xRange = if (o3) 300.0..(if (no2) 500.0 else 350.0) else 420.0..500.0, markers = if (o3) listOf(GasColumns.O3_A, GasColumns.O3_B) else emptyList(),
            bands = if (no2) listOf(Band(GasColumns.NO2_LO, GasColumns.NO2_HI, OnGreen.copy(alpha = 0.12f))) else emptyList())
        Section(stringResource(R.string.gas_geometry)) {
            KeyValue(stringResource(R.string.sun_elevation), pos?.let { f(it.elevationDeg, 1) + "°" } ?: "—", live = true)
            KeyValue(stringResource(R.string.gas_airmass), f(m, 3), live = true)
            KeyValue(stringResource(R.string.gas_site), if (fix != null) stringResource(R.string.gas_site_gps) else stringResource(R.string.gas_site_manual, f(site.first, 2), f(site.second, 2)))
        }
        if (o3) Section(stringResource(R.string.gas_ozone)) {
            KeyValue("ln(I310 / I330)", f(l, 4), live = true)
            KeyValue(stringResource(R.string.gas_l0), f(l0, 4) + if (l0 == null) "  " + stringResource(R.string.gas_not_calibrated) else "")
            Hint(stringResource(R.string.gas_ozone_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.gas_known_du), knownDu, { knownDu = it }, Modifier.weight(1f), 0, 100.0, 600.0, "DU")
                ActionButton(onClick = { if (l != null && m != null) engine.tools.setParam("gas.l0", GasColumns.l0FromKnown(l, m, knownDu, hPa)) },
                    enabled = l != null && m != null, emphasis = true) { Text(stringResource(R.string.wat_calibrate)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(langleyOn, stringResource(if (langleyOn) R.string.sun_langley_on else R.string.sun_langley_off), {
                    if (!langleyOn) { langley = emptyList(); engine.setRunning(true) }; langleyOn = !langleyOn })
                ActionButton(onClick = { GasColumns.l0Langley(langley, hPa)?.let { engine.tools.setParam("gas.l0", it) } }, enabled = langley.size >= 8) {
                    Text(stringResource(R.string.gas_langley_use, langley.size))
                }
            }
            if (du != null) SaveResultButton(engine, ToolId.GAS_COLUMNS, "O₃ ${f(du, 0)} DU", du, "#5E35B1", "ozone %.0f DU; airmass %.3f".format(Locale.US, du, m))
        }
        if (no2) Section("NO₂ (DOAS)") {
            KeyValue(stringResource(R.string.gas_scd), no2Res?.let { (s, e) -> "%.2e ± %.1e cm⁻²".format(Locale.US, s, e) } ?: "—", live = true)
            KeyValue(stringResource(R.string.gas_mref), f(mRef, 3))
            KeyValue(stringResource(R.string.gas_xs), stringResource(when { xs != null -> R.string.gas_xs_imported; engine.simulator != null -> R.string.gas_xs_synthetic; else -> R.string.gas_xs_missing }))
            if (sigma == null) Note(stringResource(R.string.gas_xs_needed))
            Hint(stringResource(R.string.gas_no2_help))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { engine.captureReference(); m?.let { engine.tools.setParam("gas.no2.mref", it) } }, enabled = fr?.darkCorrected == true && m != null, emphasis = true) {
                    Text(stringResource(R.string.gas_store_ref))
                }
                ActionButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) }) { Text(stringResource(R.string.gas_import_xs)) }
            }
            SwitchRow(stringResource(R.string.gas_clean_ref), clean, { engine.tools.setParam("gas.no2.clean", if (it) 1.0 else 0.0) }, stringResource(R.string.gas_clean_ref_help))
            if (vcd != null) SaveResultButton(engine, ToolId.GAS_COLUMNS, "NO₂ ${f(vcd, 2)} DU", vcd, "#6D4C41",
                "NO2 %.3f DU; dSCD %.3e; airmass %.3f".format(Locale.US, vcd, no2Res.first, m))
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val now = System.currentTimeMillis()
            val base = simScene ?: SimScene.Sun(0.10, 1.3, 1.5, SimClock(now, Solar.solarHourToUtcMs(now, site.second, 12.0)), 320.0)
            Text(stringResource(R.string.sim_ozone_col), style = MaterialTheme.typography.bodySmall)
            ChipChoices(listOf(250.0, 300.0, 350.0, 400.0), simScene?.ozoneDu, { "${f(it, 0)} DU" }) { v -> val s = base.copy(ozoneDu = v); simScene = s; engine.setSimScene(s) }
            Text(stringResource(R.string.sim_no2), style = MaterialTheme.typography.bodySmall)
            ChipChoices(listOf(0.0, 0.3, 0.6, 1.0), simScene?.no2Du, { "${f(it, 1)} DU" }) { v -> val s = base.copy(no2Du = v); simScene = s; engine.setSimScene(s) }
            SimClockChips(engine, simScene?.clock) { c -> val s = base.copy(clock = c); simScene = s; engine.setSimScene(s) }
            ActionButton(onClick = { knownDu = base.ozoneDu }, compact = true) { Text(stringResource(R.string.sim_use_truth)) }
        }
    }
}

// ================================= Soil colour (Munsell) ====================================

@Composable
fun SoilTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    var simTarget by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Reflect)?.target) }
    var allChips by remember { mutableStateOf(false) }
    val fr = frame
    val r = fr?.ratio()
    val lab = if (fr != null && r != null) Colorimetry.lab(Colorimetry.reflectanceXYZ(fr.wavelengths, r)) else null
    val matches = remember(lab?.l?.let { (it * 4).toInt() }, lab?.a?.let { (it * 4).toInt() }, lab?.b?.let { (it * 4).toInt() }, allChips) {
        lab?.let { Munsell.nearest(it, !allChips, 3) } ?: emptyList()
    }
    val best = matches.firstOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (r == null) Note(stringResource(R.string.soil_needs_white))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.soil_munsell), best?.chip?.notation ?: "—")
            lab?.let { val c = Colorimetry.srgb(Colorimetry.reflectanceXYZ(fr!!.wavelengths, r!!)); Swatch(Color(c[0], c[1], c[2]), 48) }
        }
        Text(best?.let { stringResource(soilHueRes(it.chip)) } ?: "", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        if (fr != null && r != null) SpectrumChart(fr.wavelengths, DoubleArray(r.size) { r[it] * 100 }, height = 160.dp, yUnit = "%", xRange = 380.0..780.0)
        Section(stringResource(R.string.soil_details)) {
            KeyValue("L* a* b*", lab?.let { "${f(it.l, 1)}  ${f(it.a, 1)}  ${f(it.b, 1)}" } ?: "—", live = true)
            for (k in 0..2) { val mm = matches.getOrNull(k); KeyValue(stringResource(R.string.soil_match_n, k + 1), mm?.let { "${it.chip.notation} · ΔE00 ${f(it.deltaE, 1)}" } ?: "—", live = true) }
            SwitchRow(stringResource(R.string.soil_all_chips), allChips, { allChips = it })
            Hint(stringResource(R.string.soil_help))
            if (best != null) SaveResultButton(engine, ToolId.SOIL, best.chip.notation, best.chip.value, hex(Colorimetry.srgb(Colorimetry.reflectanceXYZ(fr!!.wavelengths, r!!))),
                "Munsell %s (dE00 %.1f); L* %.1f a* %.1f b* %.1f".format(Locale.US, best.chip.notation, best.deltaE, lab!!.l, lab.a, lab.b))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            ChipChoices(listOf(SimTarget.SOIL, SimTarget.DRY_GRASS, SimTarget.TILE_RED, SimTarget.TILE_GREY), simTarget, { targetLabel(it) }) { tg ->
                simTarget = tg; engine.setSimScene(SimScene.Reflect(tg))
            }
        }
    }
}

private fun soilHueRes(c: Munsell.Chip) = when {
    c.hue == "N" -> R.string.soil_hue_neutral
    c.hue.endsWith("GY") || c.hue.endsWith("G") || c.hue.endsWith("BG") || c.hue.endsWith("B") || c.hue.endsWith("PB") -> R.string.soil_hue_gley
    c.value <= 3.5 && c.chroma <= 3 -> R.string.soil_hue_dark
    c.hue.endsWith("R") && !c.hue.endsWith("YR") || c.hue == "2.5YR" || c.hue == "5YR" -> R.string.soil_hue_red
    else -> R.string.soil_hue_brown
}

// ================================= Colour rendering (CRI / TM-30) ===========================

@Composable
fun CriTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val light by engine.tools.light.collectAsState()
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val scope = rememberCoroutineScope()
    val fr = frame
    val resp = light.response?.takeIf { fr != null && it.size == fr.counts.size }
    val ok = fr != null && fr.darkCorrected && !fr.saturated && fr.maxRaw > 1500
    val res = remember(fr?.timeMs, resp) {
        if (fr == null || !ok) null else {
            val g = ColourQuality.grid(fr.wavelengths, LightMetrics.corrected(fr.counts, resp))
            if (g.sum() <= 0) null else try { ColourQuality.cri(g) to ColourQuality.tm30(g) } catch (_: Exception) { null }
        }
    }
    val cri = res?.first; val tm = res?.second
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (resp == null) Note(stringResource(R.string.cri_needs_response))
        if (fr != null && !ok) Note(stringResource(if (fr.saturated) R.string.cri_saturated else R.string.cri_weak))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue("CRI Ra", f(cri?.ra, 0), size = 30.sp, colour = cri?.let { if (it.ra >= 90) OnGreen else if (it.ra >= 80) MaterialTheme.colorScheme.primary else WarnAmber } ?: MaterialTheme.colorScheme.primary)
            BigValue("R9", f(cri?.r9, 0), size = 30.sp)
            BigValue("Rf · Rg", tm?.let { "${f(it.rf, 0)} · ${f(it.rg, 0)}" } ?: "—", size = 26.sp)
        }
        if (cri != null) SpectrumChart(DoubleArray(14) { it + 1.0 }, cri.r, height = 150.dp, yUnit = "Ri", dots = true, hLine = 80.0)
        Section(stringResource(R.string.cri_details)) {
            KeyValue("CCT · Duv", cri?.let { "${f(it.cct.kelvin, 0)} K · ${"%+.4f".format(Locale.US, it.cct.duv)}" } ?: "—", live = true)
            KeyValue("R1–R8", cri?.r?.take(8)?.joinToString(" ") { f(it, 0) } ?: "—", live = true)
            KeyValue("R9–R14", cri?.r?.drop(8)?.joinToString(" ") { f(it, 0) } ?: "—", live = true)
            KeyValue(stringResource(R.string.cri_rcs), tm?.rcs?.let { a -> listOf(0, 4, 8, 12).joinToString(" · ") { i -> "h${i + 1} ${"%+.0f".format(Locale.US, a[i])} %" } } ?: "—", live = true)
            Hint(stringResource(R.string.cri_help))
            if (cri != null && tm != null) SaveResultButton(engine, ToolId.CRI, "Ra ${f(cri.ra, 0)} · Rf ${f(tm.rf, 0)}", cri.ra, "#FB8C00",
                "Ra %.1f; R9 %.1f; Rf %.1f; Rg %.1f; CCT %.0f K; Duv %.4f".format(Locale.US, cri.ra, cri.r9, tm.rf, tm.rg, cri.cct.kelvin, cri.cct.duv))
        }
        Section(stringResource(R.string.light_calibration)) {
            Hint(stringResource(R.string.cri_cal_help))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { engine.openTool(ToolId.LIGHT) }) { Text(stringResource(R.string.cal_open_light)) }
                if (engine.simulator != null) ActionButton(onClick = { scope.launch { try { calibrateOnSimLamp(engine) } catch (_: Exception) {} } }, emphasis = resp == null) {
                    Text(stringResource(R.string.sim_calibrate_lamp))
                }
            }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            ChipChoices(listOf(SimEmitter.LED_WHITE_WARM, SimEmitter.LED_WHITE_COOL, SimEmitter.LAMP_FLUORESCENT, SimEmitter.LAMP_2856K, SimEmitter.LAMP_HPS), simEmitter, { emitterLabel(it) }) { e ->
                simEmitter = e; engine.setSimScene(SimScene.Emit(e))
            }
        }
    }
}

// ================================= Display check ============================================

@Composable
fun DisplayTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    var patch by remember { mutableStateOf(DisplayCheck.Patch.WHITE) }
    var self by remember { mutableStateOf(false) }
    var simDisplay by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Display)?.display ?: SimDisplay.OLED) }
    val fr = frame
    val xy = if (fr != null && fr.darkCorrected) DisplayCheck.xy(fr.wavelengths, fr.counts) else null
    val stored = DisplayCheck.Patch.entries.associateWith { p -> params["disp.${p.name}.x"]?.let { x -> params["disp.${p.name}.y"]?.let { y -> x to y } } }
    val prim = listOf(DisplayCheck.Patch.RED, DisplayCheck.Patch.GREEN, DisplayCheck.Patch.BLUE).mapNotNull { stored[it] }
    val gamut = if (prim.size == 3) DisplayCheck.gamut(prim) else null
    val white = stored[DisplayCheck.Patch.WHITE]
    fun choose(p: DisplayCheck.Patch) { patch = p; engine.simulator?.let { engine.setSimScene(SimScene.Display(simDisplay, p.ordinal)) } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(DisplayCheck.Patch.entries, patch, { stringResource(patchRes(it)) }) { choose(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue("x y", xy?.let { "${f(it.first, 4)}  ${f(it.second, 4)}" } ?: "—", size = 26.sp)
            xy?.let { Swatch(xyColour(it.first, it.second), 44) }
        }
        if (fr != null) SpectrumChart(fr.wavelengths, fr.counts, height = 160.dp, yUnit = stringResource(R.string.unit_counts), xRange = 380.0..780.0)
        Section(stringResource(R.string.disp_measure)) {
            Hint(stringResource(R.string.disp_help))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(onClick = { xy?.let { engine.tools.setParam("disp.${patch.name}.x", it.first); engine.tools.setParam("disp.${patch.name}.y", it.second) } },
                    enabled = xy != null, emphasis = true) { Text(stringResource(R.string.disp_store, stringResource(patchRes(patch)))) }
                ActionButton(onClick = { self = true }) { Text(stringResource(R.string.disp_self)) }
                ActionButton(onClick = { DisplayCheck.Patch.entries.forEach { p -> engine.tools.setParam("disp.${p.name}.x", null); engine.tools.setParam("disp.${p.name}.y", null) } }, compact = true) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            for (p in DisplayCheck.Patch.entries) KeyValue(stringResource(patchRes(p)), stored[p]?.let { "x ${f(it.first, 4)}  y ${f(it.second, 4)}" } ?: "—")
        }
        Section(stringResource(R.string.disp_gamut)) {
            KeyValue(stringResource(R.string.disp_area), gamut?.let { "${f(it.areaVsSrgb, 0)} % sRGB" } ?: "—")
            KeyValue("sRGB · DCI-P3 · Rec.2020", gamut?.let { "${f(it.coverSrgb, 0)} · ${f(it.coverP3, 0)} · ${f(it.cover2020, 0)} %" } ?: "—")
            KeyValue(stringResource(R.string.disp_white), white?.let { "${f(com.rfsat.pocketspectro.tools.Colorimetry.cct(it.first, it.second), 0)} K (D65 6504 K)" } ?: "—")
            Hint(stringResource(R.string.disp_gamut_help))
            if (gamut != null) SaveResultButton(engine, ToolId.DISPLAY, "${f(gamut.coverP3, 0)} % P3", gamut.coverP3, "#1E88E5",
                "area %.0f%% sRGB; cover sRGB %.0f%%, P3 %.0f%%, Rec2020 %.0f%%".format(Locale.US, gamut.areaVsSrgb, gamut.coverSrgb, gamut.coverP3, gamut.cover2020))
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            ChipChoices(SimDisplay.entries, simDisplay, { stringResource(if (it == SimDisplay.LCD) R.string.sim_lcd else R.string.sim_oled) }) { d ->
                simDisplay = d; engine.setSimScene(SimScene.Display(d, patch.ordinal))
            }
        }
    }
    if (self) Dialog(onDismissRequest = { self = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = when (patch) { DisplayCheck.Patch.WHITE -> Color.White; DisplayCheck.Patch.RED -> Color.Red; DisplayCheck.Patch.GREEN -> Color.Green; DisplayCheck.Patch.BLUE -> Color.Blue }
        Box(Modifier.fillMaxSize().background(c).clickable {
            // Tap: store this colour (measured with the probe on the screen) and show the next one.
            xy?.let { engine.tools.setParam("disp.${patch.name}.x", it.first); engine.tools.setParam("disp.${patch.name}.y", it.second) }
            val next = DisplayCheck.Patch.entries[(patch.ordinal + 1) % 4]; if (next == DisplayCheck.Patch.WHITE) self = false else choose(next)
        }, contentAlignment = Alignment.BottomCenter) {
            Text(stringResource(R.string.disp_self_hint), Modifier.padding(24.dp), color = if (patch == DisplayCheck.Patch.WHITE) Color.Black else Color.White)
        }
    }
}

private fun patchRes(p: DisplayCheck.Patch) = when (p) {
    DisplayCheck.Patch.WHITE -> R.string.disp_white_patch; DisplayCheck.Patch.RED -> R.string.disp_red
    DisplayCheck.Patch.GREEN -> R.string.disp_green; DisplayCheck.Patch.BLUE -> R.string.disp_blue
}

// ================================= Pigments (FORS) ==========================================

@Composable
fun PigmentTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    var simName by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Pigment)?.name) }
    val fr = frame
    val r = fr?.ratio()
    val ft = remember(fr?.timeMs) { if (fr != null && r != null) Pigments.features(fr.wavelengths, r) else null }
    val m = remember(ft) { if (ft != null && fr != null) Pigments.match(fr.wavelengths, ft) else emptyList() }
    val best = m.firstOrNull()?.takeIf { it.score >= 0.55 }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        if (r == null) Note(stringResource(R.string.pig_needs_white))
        BigValue(stringResource(R.string.pig_best), best?.pigment?.name ?: if (ft == null) "—" else stringResource(R.string.ram_no_match), size = 22.sp,
            colour = if (best != null) OnGreen else MaterialTheme.colorScheme.primary)
        if (fr != null && r != null) SpectrumChart(fr.wavelengths, DoubleArray(r.size) { r[it] * 100 }, height = 170.dp, yUnit = "%",
            markers = (ft?.inflections ?: emptyList()) + (ft?.minima ?: emptyList()))
        Section(stringResource(R.string.pig_details)) {
            KeyValue(stringResource(R.string.pig_inflections), ft?.inflections?.takeIf { it.isNotEmpty() }?.joinToString(", ") { f(it, 0) }?.plus(" nm") ?: "—", live = true)
            KeyValue(stringResource(R.string.pig_minima), ft?.minima?.takeIf { it.isNotEmpty() }?.joinToString(", ") { f(it, 0) }?.plus(" nm") ?: "—", live = true)
            for (k in 0..2) { val x = m.getOrNull(k); KeyValue(x?.pigment?.name ?: "—", x?.let { "${f(it.score * 100, 0)} %" } ?: "", live = true) }
            Hint(stringResource(R.string.pig_help))
            if (best != null) SaveResultButton(engine, ToolId.PIGMENT, best.pigment.name, best.score, "#3949AB",
                "%s %.0f%%; inflections %s; minima %s".format(Locale.US, best.pigment.name, best.score * 100, ft!!.inflections.joinToString("/") { f(it, 0) }, ft.minima.joinToString("/") { f(it, 0) }))
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            ChipChoices(Pigments.LIBRARY.map { it.name }, simName, { it.substringBefore(" (") }) { n -> simName = n; engine.setSimScene(SimScene.Pigment(n)) }
        }
    }
}

// ================================= Gem tester ===============================================

@Composable
fun GemTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val fluo = (params["gem.fluo"] ?: 0.0) > 0.5
    var simName by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Gem)?.name) }
    val fr = frame
    val t = if (!fluo) fr?.ratio() else null
    val features = remember(fr?.timeMs, fluo) {
        when {
            fr == null -> emptyList()
            fluo -> PhysicsLab.lines(fr.wavelengths, fr.counts, 6.0).map { Gems.Feature(it.nm, it.height) }
            t != null -> Gems.absorptionBands(fr.wavelengths, t)
            else -> emptyList()
        }
    }
    val m = if (fr != null && (fluo || t != null)) Gems.match(features, if (fluo) Gems.Mode.FLUORESCENCE else Gems.Mode.ABSORPTION, fr.wavelengths) else emptyList()
    val best = m.firstOrNull()?.takeIf { it.score >= 0.6 }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(listOf(false, true), fluo, { stringResource(if (it) R.string.gem_fluorescence else R.string.gem_absorption) }) { v ->
                engine.tools.setParam("gem.fluo", if (v) 1.0 else 0.0); simName?.let { engine.setSimScene(SimScene.Gem(it, v)) }
            }
        }
        if (!fluo && t == null) Note(stringResource(R.string.gem_needs_open))
        BigValue(stringResource(R.string.gem_best), best?.gem?.name ?: if (fr == null) "—" else stringResource(R.string.ram_no_match), size = 22.sp,
            colour = if (best != null) OnGreen else MaterialTheme.colorScheme.primary)
        if (fr != null) SpectrumChart(fr.wavelengths, if (t != null) DoubleArray(t.size) { t[it] * 100 } else fr.counts, height = 170.dp,
            yUnit = if (t != null) "%" else stringResource(R.string.unit_counts), markers = best?.gem?.lines?.map { it.first } ?: emptyList(),
            xRange = 400.0..(fr.wavelengths.last().coerceAtMost(820.0)))
        Section(stringResource(R.string.gem_details)) {
            KeyValue(stringResource(R.string.gem_lines), features.take(6).joinToString(", ") { f(it.nm, 1) }.ifEmpty { "—" }, live = true)
            for (k in 0..2) { val x = m.getOrNull(k); KeyValue(x?.gem?.name ?: "—", x?.let { "${f(it.score * 100, 0)} %" } ?: "", live = true) }
            Hint(stringResource(if (fluo) R.string.gem_help_fluo else R.string.gem_help_abs))
            Hint(stringResource(R.string.gem_caution))
            if (best != null) SaveResultButton(engine, ToolId.GEM, best.gem.name, best.score, "#00838F",
                "%s %.0f%%; lines %s".format(Locale.US, best.gem.name, best.score * 100, features.take(6).joinToString("/") { f(it.nm, 1) }))
        }
        ReferencePanel(engine, if (fluo) RefKind.DARK_ONLY else RefKind.OPEN)
        SimPanel(engine) {
            val names = Gems.LIBRARY.filter { it.mode == (if (fluo) Gems.Mode.FLUORESCENCE else Gems.Mode.ABSORPTION) }.map { it.name }.distinct()
            ChipChoices(names, simName, { it.substringBefore(" (").substringBefore(" /") }) { n -> simName = n; engine.setSimScene(SimScene.Gem(n, fluo)) }
        }
    }
}
