package com.rfsat.pocketspectro.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.rfsat.pocketspectro.core.Frame
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimFilter
import com.rfsat.pocketspectro.sim.SimOptics
import com.rfsat.pocketspectro.sim.SimSample
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.tools.Chemometrics
import com.rfsat.pocketspectro.tools.Drinks
import com.rfsat.pocketspectro.tools.FilmThickness
import com.rfsat.pocketspectro.tools.FilterTest
import com.rfsat.pocketspectro.tools.HealthCheck
import com.rfsat.pocketspectro.tools.LightMetrics
import com.rfsat.pocketspectro.tools.OliveOil
import com.rfsat.pocketspectro.tools.Pyrometry
import com.rfsat.pocketspectro.tools.Raman
import com.rfsat.pocketspectro.tools.Spectra
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.absorbance
import com.rfsat.pocketspectro.tools.ratio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

private fun f(v: Double?, d: Int = 2) = if (v == null || !v.isFinite()) "—" else "%.${d}f".format(Locale.US, v)

/** Waits for [n] frames newer than now that satisfy [cond] (30 s each at most). */
suspend fun AcquisitionEngine.nextFrames(n: Int, cond: (Frame) -> Boolean = { true }): List<Frame> {
    val out = ArrayList<Frame>()
    var last = frame.value?.timeMs ?: 0L
    repeat(n) {
        val fr = withTimeout(30_000) { frame.filterNotNull().first { it.timeMs > last && cond(it) } }
        last = fr.timeMs; out += fr
    }
    return out
}

/** Simulator: shows the 2856 K lamp, stores the detector response from it, and puts the scene back. */
internal suspend fun calibrateOnSimLamp(engine: AcquisitionEngine) {
    val sim = engine.simulator ?: return
    val shown = sim.scene
    engine.setRunning(true)
    if (engine.dark.value == null) { engine.captureDark(true); withTimeout(30_000) { engine.dark.filterNotNull().first() } }
    sim.scene = SimScene.Emit(SimEmitter.LAMP_2856K)
    val ms = engine.store.value.integrationMs
    try {
        // A saturated lamp spectrum would flatten the response curve: shorten the exposure until it fits.
        var fr = engine.nextFrames(2) { it.darkCorrected }.last()
        var k = 0
        while (fr.saturated && k++ < 5) {
            engine.store.update { it.copy(integrationMs = it.integrationMs / 2) }
            if (engine.dark.value?.integrationUs != engine.store.value.integrationUs) { engine.captureDark(true); engine.nextFrames(1) }
            fr = engine.nextFrames(2) { it.darkCorrected && it.integrationUs == engine.store.value.integrationUs }.last()
        }
        val ok = fr
        if (!ok.saturated) engine.tools.lightUpdate { it.copy(lampKelvin = 2856.0, response = LightMetrics.responseFromLamp(ok.wavelengths, ok.counts, 2856.0)) }
    } finally {
        sim.scene = shown
        if (engine.store.value.integrationMs != ms) { engine.store.update { it.copy(integrationMs = ms) }; engine.captureDark(true) }
    }
}

// ================================= Temperature (pyrometer) =================================

@Composable
fun TemperatureTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val light by engine.tools.light.collectAsState()
    val params by engine.tools.params.collectAsState()
    val scope = rememberCoroutineScope()
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val fr = frame
    val resp = light.response?.takeIf { fr != null && it.size == fr.counts.size }
    val lo = params["temp.lo"] ?: (fr?.wavelengths?.first() ?: 0.0)
    val hi = params["temp.hi"] ?: (fr?.wavelengths?.last() ?: 0.0)
    val r = if (fr != null && fr.darkCorrected && resp != null && !fr.saturated) Pyrometry.fit(fr.wavelengths, fr.counts, resp, lo, hi) else null
    ToolPage {
        AcquisitionRow(engine)
        when {
            fr != null && !fr.darkCorrected -> Note(stringResource(R.string.temp_needs_dark))
            resp == null -> Note(stringResource(R.string.temp_needs_cal))
            fr?.saturated == true -> Note(stringResource(R.string.sun_saturated), OffRed)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.temp_kelvin), r?.let { "${f(it.kelvin, 0)} K" } ?: "—")
            BigValue(stringResource(R.string.temp_celsius), r?.let { "${f(it.celsius, 0)} °C" } ?: "—", size = 26.sp)
        }
        if (fr != null) {
            val y = LightMetrics.corrected(fr.counts, resp)
            val ov = r?.let { res ->
                val p = DoubleArray(fr.wavelengths.size) { Spectra.planck(fr.wavelengths[it], res.kelvin) }
                val idx = fr.wavelengths.indices.filter { fr.wavelengths[it] in res.fromNm..res.toNm }
                val k = idx.sumOf { y[it] * p[it] } / idx.sumOf { p[it] * p[it] }.coerceAtLeast(1e-300)
                fr.wavelengths to DoubleArray(p.size) { p[it] * k }
            }
            SpectrumChart(fr.wavelengths, y, height = 200.dp, yUnit = stringResource(if (resp != null) R.string.unit_relative else R.string.unit_counts), overlay = ov,
                bands = listOf(Band(lo, hi, OnGreen.copy(alpha = 0.08f))))
            Text(stringResource(R.string.temp_chart_legend), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = OverlayColor)
        }
        Section(stringResource(R.string.temp_details)) {
            KeyValue(stringResource(R.string.temp_fit), r?.let { "R² ${f(it.r2, 4)} · ${it.pixels} px" } ?: "—", live = true)
            KeyValue(stringResource(R.string.temp_range), r?.let { "${f(it.fromNm, 0)}–${f(it.toNm, 0)} nm" } ?: "—", live = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.temp_from), lo, { engine.tools.setParam("temp.lo", it) }, Modifier.weight(1f), 0, 150.0, 2600.0, "nm")
                NumberField(stringResource(R.string.temp_to), hi, { engine.tools.setParam("temp.hi", it) }, Modifier.weight(1f), 0, 150.0, 2600.0, "nm")
            }
            Text(stringResource(R.string.temp_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.TEMPERATURE, if (r != null) SaveSpec("${f(r.kelvin, 0)} K", r.kelvin, "#E64A19",
                "%.0f K (%.0f °C); R² %.4f; %.0f-%.0f nm".format(Locale.US, r.kelvin, r.celsius, r.r2, r.fromNm, r.toNm)) else null)
        }
        Section(stringResource(R.string.light_calibration)) {
            Text(stringResource(R.string.temp_cal_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(stringResource(R.string.light_lamp_k), light.lampKelvin, { v -> engine.tools.lightUpdate { it.copy(lampKelvin = v) } }, Modifier.weight(1f), 0, 1000.0, 10000.0, "K")
                ActionButton(onClick = {
                    val frm = fr ?: return@ActionButton
                    engine.tools.lightUpdate { it.copy(response = LightMetrics.responseFromLamp(frm.wavelengths, frm.counts, it.lampKelvin)) }
                }, enabled = fr != null && fr.darkCorrected && !fr.saturated, emphasis = true) { Text(stringResource(R.string.light_use_lamp)) }
            }
            if (fr?.saturated == true) Text(stringResource(R.string.lamp_cal_saturated), style = MaterialTheme.typography.bodySmall, color = OffRed)
            KeyValue(stringResource(R.string.temp_cal_state), stringResource(if (resp != null) R.string.ref_stored else R.string.ref_missing))
            if (light.response != null) ActionButton(onClick = { engine.tools.lightUpdate { it.copy(response = null) } }, compact = true) { Text(stringResource(R.string.light_clear_cal)) }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            val bodies = listOf(SimEmitter.BODY_1100K, SimEmitter.BODY_1500K, SimEmitter.BODY_1900K, SimEmitter.LAMP_2856K)
            ChipChoices(bodies, simEmitter, { emitterLabel(it) }) { e -> simEmitter = e; engine.setSimScene(SimScene.Emit(e)) }
            ActionButton(onClick = { scope.launch { try { calibrateOnSimLamp(engine) } catch (_: Exception) {} } }, emphasis = resp == null) {
                Text(stringResource(R.string.sim_calibrate_lamp))
            }
        }
    }
}

// ================================= Film thickness ===========================================

@Composable
fun FilmTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    var simScene by remember { mutableStateOf(engine.simulator?.scene as? SimScene.Film) }
    val n = params["film.n"] ?: 1.5
    val sub = FilmThickness.Substrate.entries[(params["film.sub"] ?: 0.0).toInt().coerceIn(0, FilmThickness.Substrate.entries.size - 1)]
    val fr = frame
    val refl = fr?.ratio()
    val r by produceState<FilmThickness.Result?>(null, fr, n, sub) {
        value = if (fr != null && refl != null) withContext(Dispatchers.Default) { FilmThickness.measure(fr.wavelengths, refl, n, sub) } else null
    }
    ToolPage {
        AcquisitionRow(engine)
        if (refl == null) Note(stringResource(R.string.film_needs_ref))
        val res = r
        BigValue(stringResource(R.string.film_thickness), res?.let { if (it.nm >= 2000) "${f(it.micrometres, 2)} µm" else "${f(it.nm, 0)} nm" } ?: "—")
        if (fr != null && refl != null) {
            val ov = if (res != null && sub.n != null) {
                val m = DoubleArray(fr.wavelengths.size) { FilmThickness.model(fr.wavelengths[it], n, res.nm, sub.n) }
                val fit = Spectra.lineFit(m, refl)
                fr.wavelengths to DoubleArray(m.size) { fit?.y(m[it]) ?: m[it] }
            } else null
            SpectrumChart(fr.wavelengths, refl, height = 190.dp, yUnit = stringResource(R.string.unit_reflectance), overlay = ov)
            if (ov != null) Text(stringResource(R.string.film_chart_legend), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = OverlayColor)
        }
        Section(stringResource(R.string.film_details)) {
            KeyValue(stringResource(R.string.film_method), res?.let { stringResource(if (it.method == FilmThickness.Method.FOURIER) R.string.film_fourier else R.string.film_model) } ?: "—", live = true)
            KeyValue(stringResource(R.string.film_fringes), f(res?.fringes, 1), live = true)
            KeyValue(stringResource(R.string.film_fit), res?.fitR2?.let { "R² ${f(it, 3)}" } ?: "—", live = true)
            Text(stringResource(R.string.film_index), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            NumberField(stringResource(R.string.film_n), n, { engine.tools.setParam("film.n", it) }, Modifier.fillMaxWidth(), 3, 1.0, 4.5)
            Text(stringResource(R.string.film_substrate), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            ChipChoices(FilmThickness.Substrate.entries, sub, { stringResource(substrateRes(it)) }) { s -> engine.tools.setParam("film.sub", s.ordinal.toDouble()) }
            Text(stringResource(R.string.film_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.FILM, if (res != null) SaveSpec(if (res.nm >= 2000) "${f(res.micrometres, 2)} µm" else "${f(res.nm, 0)} nm", res.nm, "#6D4C41",
                "thickness %.0f nm; n %.3f; %s; fringes %.1f".format(Locale.US, res.nm, n, sub.name, res.fringes)) else null)
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            val films = listOf(SimScene.Film(700.0, 1.5, 3.7), SimScene.Film(2000.0, 1.5, 1.52), SimScene.Film(5000.0, 1.5, 3.7), SimScene.Film(20000.0, 1.5, 1.52))
            ChipChoices(films, simScene, { s -> stringResource(R.string.sim_film, if (s.thicknessNm >= 1000) "${f(s.thicknessNm / 1000, 1)} µm" else "${f(s.thicknessNm, 0)} nm",
                stringResource(if (s.substrateN > 3) R.string.film_sub_silicon else R.string.film_sub_glass)) }) { s -> simScene = s; engine.setSimScene(s) }
        }
    }
}

private fun substrateRes(s: FilmThickness.Substrate) = when (s) {
    FilmThickness.Substrate.GLASS -> R.string.film_sub_glass
    FilmThickness.Substrate.SILICON -> R.string.film_sub_silicon
    FilmThickness.Substrate.WATER -> R.string.film_sub_water
    FilmThickness.Substrate.METAL -> R.string.film_sub_metal
    FilmThickness.Substrate.UNKNOWN -> R.string.film_sub_unknown
}

// ================================= Filters and eyewear ======================================

@Composable
fun FilterTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    var simFilter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Transmit)?.filter) }
    val odNm = params["filter.od"] ?: 532.0
    val fr = frame
    val t = fr?.ratio()
    val r = if (fr != null && t != null) FilterTest.analyse(fr.wavelengths, t) else null
    val od = if (fr != null && fr.reference != null && fr.darkCorrected && odNm in fr.wavelengths.first()..fr.wavelengths.last())
        FilterTest.opticalDensity(fr.wavelengths, fr.counts, fr.reference, odNm) else null
    ToolPage {
        AcquisitionRow(engine)
        if (t == null) Note(stringResource(R.string.filt_needs_open))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue(stringResource(R.string.filt_luminous), r?.luminousPercent?.let { "${f(it, 1)} %" } ?: "—", size = 26.sp)
            BigValue(stringResource(R.string.filt_category), r?.sunglassCategory?.let { if (it > 4) stringResource(R.string.filt_too_dark) else it.toString() } ?: "—", size = 26.sp)
        }
        if (fr != null && t != null) SpectrumChart(fr.wavelengths, DoubleArray(t.size) { t[it] * 100 }, height = 190.dp, yUnit = "%",
            markers = listOfNotNull(r?.cutOnNm), bands = listOf(Band(415.0, 455.0, Color(0x332196F3)), Band(380.0, 400.0, Color(0x337B1FA2))))
        Section(stringResource(R.string.filt_details)) {
            KeyValue(stringResource(R.string.filt_uv400), r?.uv380to400Percent?.let { "${f(it, 2)} % · " + stringResource(if (r.uv400 == true) R.string.colour_pass else R.string.colour_fail) }
                ?: stringResource(R.string.filt_not_covered), live = true)
            KeyValue(stringResource(R.string.filt_uva), r?.uvaPercent?.let { "${f(it, 2)} %" } ?: stringResource(R.string.filt_not_covered), live = true)
            KeyValue(stringResource(R.string.filt_blue), r?.bluePercent?.let { stringResource(R.string.filt_blue_value, f(it, 1), f(100 - it, 0)) } ?: "—", live = true)
            KeyValue(stringResource(R.string.filt_cut_on), r?.cutOnNm?.let { "${f(it, 1)} nm" } ?: "—", live = true)
            KeyValue(stringResource(R.string.filt_min), r?.let { "${f(it.minPercent, 2)} % @ ${f(it.minNm, 0)} nm" } ?: "—", live = true)
            KeyValue(stringResource(R.string.filt_od, f(odNm, 0)), od?.let { (if (it.atLimit) "≥ " else "") + f(it.od, 1) } ?: "—", live = true)
            NumberField(stringResource(R.string.filt_od_nm), odNm, { engine.tools.setParam("filter.od", it) }, Modifier.fillMaxWidth(), 0, 150.0, 2600.0, "nm")
            Text(stringResource(R.string.filt_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.FILTER, if (r != null) SaveSpec(r.luminousPercent?.let { "τv ${f(it, 0)} %" } ?: (r.cutOnNm?.let { "cut-on ${f(it, 0)} nm" } ?: "T ${f(r.meanPercent, 0)} %"),
                r.luminousPercent ?: r.meanPercent, "#455A64",
                listOfNotNull(r.luminousPercent?.let { "tv %.1f %%".format(Locale.US, it) }, r.sunglassCategory?.let { "cat %d".format(it) },
                    r.uv380to400Percent?.let { "T380-400 %.2f %%".format(Locale.US, it) }, r.bluePercent?.let { "blue %.1f %%".format(Locale.US, it) },
                    r.cutOnNm?.let { "cut-on %.1f nm".format(Locale.US, it) }).joinToString("; ")) else null)
        }
        ReferencePanel(engine, RefKind.OPEN)
        SimPanel(engine) {
            ChipChoices(SimFilter.entries.filter { it != SimFilter.OPEN }, simFilter, { stringResource(filterRes(it)) }) { s -> simFilter = s; engine.setSimScene(SimScene.Transmit(s)) }
        }
    }
}

private fun filterRes(s: SimFilter) = when (s) {
    SimFilter.OPEN -> R.string.sim_f_open
    SimFilter.SUNGLASS_CAT3 -> R.string.sim_f_sunglass
    SimFilter.BLUE_BLOCK -> R.string.sim_f_blue
    SimFilter.UV400_CLEAR -> R.string.sim_f_uv400
    SimFilter.RED_LONGPASS -> R.string.sim_f_red
    SimFilter.LASER_GOGGLE_532 -> R.string.sim_f_goggle
    SimFilter.ND_1 -> R.string.sim_f_nd
}

// ================================= Teach and predict ========================================

@Composable
fun PredictTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val selected by engine.tools.predictModel.collectAsState()
    val version by engine.tools.predictVersion.collectAsState()
    val root = engine.sessionsRoot.parentFile ?: engine.sessionsRoot
    val names by produceState(emptyList<String>(), version) { value = withContext(Dispatchers.IO) { Chemometrics.list(root) } }
    val model by produceState<Chemometrics.Model?>(null, selected, version) { value = selected?.let { s -> withContext(Dispatchers.IO) { Chemometrics.load(root, s) } } }
    val fit by produceState<Chemometrics.Fit?>(null, model) { value = model?.let { m -> withContext(Dispatchers.Default) { Chemometrics.build(m) } } }
    var newName by remember { mutableStateOf("") }
    var newUnit by remember { mutableStateOf("") }
    var newLo by remember { mutableDoubleStateOf(0.0) }
    var newHi by remember { mutableDoubleStateOf(0.0) }
    var newPre by remember { mutableStateOf(Chemometrics.Pre.SNV) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableDoubleStateOf(0.0) }
    var seed by remember { mutableIntStateOf(((engine.simulator?.scene as? SimScene.Fruit)?.seed ?: 1L).toInt()) }
    val scope = rememberCoroutineScope()
    val fr = frame
    val ratio = fr?.ratio()
    LaunchedEffect(fr?.wavelengths?.size) {
        fr?.wavelengths?.let { wl -> if (newLo == 0.0) { newLo = maxOf(wl.first(), 700.0).let { if (it >= wl.last() - 50) wl.first() else it }; newHi = minOf(wl.last(), 1050.0) } }
    }
    val m = model
    val x = if (m != null && fr != null && ratio != null && Spectra.covers(fr.wavelengths, m.lo, m.hi)) Chemometrics.spectrum(fr.wavelengths, ratio, m.lo, m.hi) else null
    val ft = fit
    val pred = if (ft != null && m != null && x != null) Chemometrics.predict(ft, m, x) else null

    ToolPage {
        AcquisitionRow(engine)
        if (ratio == null) Note(stringResource(R.string.pred_needs_ref))
        Section(stringResource(R.string.pred_models)) {
            if (names.isEmpty()) Text(stringResource(R.string.pred_no_models), style = MaterialTheme.typography.bodySmall)
            ChipChoices(names, selected, { it }) { engine.tools.predictSelect(it) }
            Text(stringResource(R.string.pred_new), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(stringResource(R.string.pred_name)) }, modifier = Modifier.weight(1.4f))
                OutlinedTextField(newUnit, { newUnit = it.take(10) }, singleLine = true, label = { Text(stringResource(R.string.pred_unit)) }, modifier = Modifier.weight(0.8f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.temp_from), newLo, { newLo = it }, Modifier.weight(1f), 0, 150.0, 2600.0, "nm")
                NumberField(stringResource(R.string.temp_to), newHi, { newHi = it }, Modifier.weight(1f), 0, 150.0, 2600.0, "nm")
            }
            ChipChoices(Chemometrics.Pre.entries, newPre, { stringResource(when (it) { Chemometrics.Pre.NONE -> R.string.pred_pre_none; Chemometrics.Pre.SNV -> R.string.pred_pre_snv; Chemometrics.Pre.SNV_D1 -> R.string.pred_pre_d1 }) }) { newPre = it }
            ActionButton(onClick = {
                val nm = Chemometrics.safe(newName)
                Chemometrics.save(root, Chemometrics.Model(nm, newUnit, minOf(newLo, newHi), maxOf(newLo, newHi), newPre, emptyList()))
                engine.tools.predictSelect(nm); engine.tools.predictChanged(); newName = ""
            }, enabled = newName.isNotBlank() && kotlin.math.abs(newHi - newLo) >= 40, emphasis = true) { Text(stringResource(R.string.pred_create)) }
        }
        if (m != null) {
            BigValue(stringResource(R.string.pred_prediction, m.name), pred?.let { "${f(it, 2)} ${m.unit}" } ?: "—")
            if (x == null && fr != null && !Spectra.covers(fr.wavelengths, m.lo, m.hi)) Note(stringResource(R.string.pred_range, f(m.lo, 0), f(m.hi, 0)))
            Section(stringResource(R.string.pred_model_quality)) {
                KeyValue(stringResource(R.string.pred_samples), m.samples.size.toString(), live = true)
                KeyValue(stringResource(R.string.pred_lv), ft?.pls?.components?.toString() ?: "—", live = true)
                KeyValue(stringResource(R.string.pred_rmsecv), ft?.let { "${f(it.rmsecv, 3)} ${m.unit}" } ?: stringResource(R.string.pred_need_more), live = true)
                KeyValue("Q²", f(ft?.q2, 3), live = true)
                if (ft != null && m.samples.size >= 3) {
                    val ref = m.samples.map { it.value }.toDoubleArray()
                    val est = m.samples.map { Chemometrics.predict(ft, m, it.x) }.toDoubleArray()
                    val order = ref.indices.sortedBy { ref[it] }
                    SpectrumChart(order.map { ref[it] }.toDoubleArray(), order.map { est[it] }.toDoubleArray(), height = 150.dp, yUnit = m.unit, dots = true,
                        overlay = order.map { ref[it] }.toDoubleArray() to order.map { ref[it] }.toDoubleArray())
                    Text(stringResource(R.string.pred_chart_legend), style = MaterialTheme.typography.labelSmall)
                }
                Text(stringResource(R.string.pred_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SaveResultButton(engine, ToolId.PREDICT, if (pred != null) SaveSpec("${m.name}: ${f(pred, 2)} ${m.unit}", pred, "#00838F",
                    "%s %.3f %s; RMSECV %s".format(Locale.US, m.name, pred, m.unit, f(ft?.rmsecv, 3))) else null)
            }
            Section(stringResource(R.string.pred_add)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.match_name)) }, modifier = Modifier.weight(1f),
                        trailingIcon = { ScanButton { label = it } })
                    NumberField(stringResource(R.string.pred_value), value, { value = it }, Modifier.weight(1f), 2, -1e9, 1e9, m.unit)
                }
                ActionButton(onClick = {
                    val xs = x ?: return@ActionButton
                    Chemometrics.save(root, m.copy(samples = m.samples + Chemometrics.Sample(label.ifBlank { "S${m.samples.size + 1}" }, value, xs)))
                    label = ""; engine.tools.predictChanged()
                }, enabled = x != null, emphasis = true) { Text(stringResource(R.string.pred_add_sample)) }
                for (s in m.samples.takeLast(6).reversed()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.label}: ${f(s.value, 2)} ${m.unit}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    ActionButton(onClick = { Chemometrics.save(root, m.copy(samples = m.samples - s)); engine.tools.predictChanged() }, compact = true) { Text(stringResource(R.string.action_delete)) }
                }
                ActionButton(onClick = { Chemometrics.delete(root, m.name); engine.tools.predictSelect(null); engine.tools.predictChanged() }, compact = true) {
                    Text(stringResource(R.string.pred_delete_model))
                }
            }
        }
        ReferencePanel(engine, RefKind.WHITE)
        SimPanel(engine) {
            Text(stringResource(R.string.sim_fruit_truth, seed, f(SimOptics.fruit(seed.toLong()).brix, 1)), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = { seed++; engine.setSimScene(SimScene.Fruit(seed.toLong())); value = (SimOptics.fruit(seed.toLong()).brix * 10).toLong() / 10.0; label = "Fruit $seed" }) {
                    Text(stringResource(R.string.sim_next_fruit))
                }
                ActionButton(onClick = {
                    val wl = fr?.wavelengths ?: return@ActionButton
                    scope.launch(Dispatchers.Default) {
                        val rnd = java.util.Random(7)
                        val lo = maxOf(wl.first(), 700.0); val hi = minOf(wl.last(), 1050.0)
                        val samples = (1001L..1025L).map { sd ->
                            val p = SimOptics.fruit(sd)
                            val rr = DoubleArray(wl.size) { (SimOptics.fruitReflectance(wl[it], p).coerceAtLeast(0.002) / 0.98) * (1 + 0.002 * rnd.nextGaussian()) }
                            Chemometrics.Sample("Demo $sd", (p.brix * 10).toLong() / 10.0, Chemometrics.spectrum(wl, rr, lo, hi))
                        }
                        val name = "Fruit sugar (demo)"
                        Chemometrics.save(root, Chemometrics.Model(name, "°Bx", lo, hi, Chemometrics.Pre.SNV, samples))
                        engine.tools.predictSelect(name); engine.tools.predictChanged()
                    }
                }, enabled = fr != null && fr.wavelengths.last() > 900) { Text(stringResource(R.string.sim_demo_model)) }
            }
        }
    }
}

// ================================= Raman ====================================================

@Composable
fun RamanTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    val libVersion by engine.tools.libraryVersion.collectAsState()
    val root = engine.sessionsRoot.parentFile ?: engine.sessionsRoot
    val userLib by produceState(emptyList<Raman.Ref>(), libVersion) { value = withContext(Dispatchers.IO) { Raman.userLibrary(root) } }
    var name by remember { mutableStateOf("") }
    var simMaterial by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Raman)?.material) }
    val laser = params["raman.laser"] ?: 785.0
    val fr = frame
    val sp = fr?.let { Raman.prepare(it.wavelengths, it.counts, laser) }
    val peaks = sp?.let { Raman.peaks(it) } ?: emptyList()
    val matches = if (sp != null) Raman.match(peaks, sp.shift.first()..sp.shift.last(), Raman.LIBRARY + userLib) else emptyList()
    val best = matches.firstOrNull()?.takeIf { it.score >= 0.5 }
    val seenLaser = fr?.let { Raman.laserLine(it.wavelengths, it.counts, laser) }
    ToolPage {
        AcquisitionRow(engine)
        if (fr != null && sp == null) Note(stringResource(R.string.ram_no_range, f(laser, 1)))
        BigValue(stringResource(R.string.ram_result), best?.let { "${it.ref.name} (${f(it.score * 100, 0)} %)" } ?: if (peaks.isEmpty()) "—" else stringResource(R.string.ram_no_match),
            colour = if (best != null) OnGreen else WarnAmber, size = 22.sp)
        if (sp != null) {
            val refBands = best?.ref?.bands?.map { it.first + best.offset } ?: emptyList()
            SpectrumChart(sp.shift, sp.y, height = 210.dp, yUnit = stringResource(R.string.unit_counts), markers = refBands)
            Text(stringResource(R.string.ram_axis), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        }
        Section(stringResource(R.string.ram_details)) {
            KeyValue(stringResource(R.string.ram_peaks), if (peaks.isEmpty()) "—" else peaks.take(6).joinToString(", ") { f(it.shift, 0) } + " cm⁻¹", live = true)
            for (k in 0 until 3) {
                val mm = matches.getOrNull(k)
                KeyValue(mm?.ref?.name ?: "—", mm?.let { "${f(it.score * 100, 0)} % · ${it.matched}/${it.total}" } ?: "", live = true)
            }
            SaveResultButton(engine, ToolId.RAMAN, if (best != null) SaveSpec(best.ref.name, best.score * 100, "#6A1B9A",
                "score %.0f %%; peaks %s cm-1".format(Locale.US, best.score * 100, peaks.take(6).joinToString(" ") { f(it.shift, 0) })) else null)
        }
        Section(stringResource(R.string.ram_settings)) {
            NumberField(stringResource(R.string.ram_laser), laser, { engine.tools.setParam("raman.laser", it) }, Modifier.fillMaxWidth(), 2, 200.0, 1100.0, "nm")
            KeyValue(stringResource(R.string.ram_laser_seen), seenLaser?.let { "${f(it, 2)} nm" } ?: "—", live = true)
            if (seenLaser != null && kotlin.math.abs(seenLaser - laser) > 0.05) ActionButton(onClick = { engine.tools.setParam("raman.laser", (seenLaser * 100).toLong() / 100.0) }, compact = true) {
                Text(stringResource(R.string.ram_use_seen))
            }
            Text(stringResource(R.string.ram_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(stringResource(R.string.ram_library)) {
            Text(stringResource(R.string.ram_library_help, Raman.LIBRARY.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.match_name)) }, modifier = Modifier.weight(1f),
                    trailingIcon = { ScanButton { name = it } })
                ActionButton(onClick = { Raman.addToUserLibrary(root, name, peaks); name = ""; engine.tools.libraryChanged() }, enabled = peaks.isNotEmpty(), emphasis = true) {
                    Text(stringResource(R.string.match_add))
                }
            }
            for (e in userLib) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = { Raman.removeFromUserLibrary(root, e.name); engine.tools.libraryChanged() }, compact = true) { Text(stringResource(R.string.action_delete)) }
            }
        }
        ReferencePanel(engine, RefKind.DARK_ONLY)
        SimPanel(engine) {
            ChipChoices(Raman.LIBRARY.map { it.name }, simMaterial, { it }) { m -> simMaterial = m; engine.setSimScene(SimScene.Raman(m)) }
        }
    }
}

// ================================= Olive oil ================================================

@Composable
fun OliveTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    var simSample by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Cuvette)?.sample) }
    val c = params["olive.c"] ?: 1.0
    val path = params["olive.path"] ?: 1.0
    val fr = frame
    val abs = fr?.absorbance()
    val r = if (fr != null && abs != null) OliveOil.analyse(fr.wavelengths, abs, c, path) else null
    ToolPage {
        AcquisitionRow(engine)
        if (abs == null) Note(stringResource(R.string.oil_needs_blank))
        val catColour = when (r?.category) { OliveOil.Category.EXTRA_VIRGIN -> OnGreen; OliveOil.Category.VIRGIN -> WarnAmber; OliveOil.Category.ABOVE_VIRGIN_LIMITS -> OffRed; null -> MaterialTheme.colorScheme.primary }
        BigValue(stringResource(R.string.oil_category), r?.let { stringResource(oilRes(it.category)) } ?: "—", colour = catColour, size = 24.sp)
        if (fr != null && abs != null) SpectrumChart(fr.wavelengths, DoubleArray(abs.size) { abs[it] / (c * path) }, height = 190.dp, yUnit = "K", xRange = 220.0..300.0,
            markers = listOf(232.0, 264.0, 268.0, 272.0))
        Section(stringResource(R.string.oil_indices)) {
            KeyValue("K232", r?.let { "${f(it.k232, 2)}   (≤ 2.50 / 2.60)" } ?: "—", live = true)
            KeyValue("K268", r?.let { "${f(it.k268, 3)}   (≤ 0.22 / 0.25)" } ?: "—", live = true)
            KeyValue("ΔK", r?.let { "${"%+.4f".format(Locale.US, it.deltaK)}   (≤ 0.01)" } ?: "—", live = true)
            Text(stringResource(R.string.oil_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SaveResultButton(engine, ToolId.OLIVE, if (r != null) SaveSpec(stringResource(oilRes(r.category)), r.k232, hex(catColour),
                "K232 %.2f; K268 %.3f; dK %.4f".format(Locale.US, r.k232, r.k268, r.deltaK)) else null)
        }
        Section(stringResource(R.string.oil_preparation)) {
            Text(stringResource(R.string.oil_prep_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.oil_conc), c, { engine.tools.setParam("olive.c", it) }, Modifier.weight(1f), 2, 0.01, 10.0, "g/100 mL")
                NumberField(stringResource(R.string.drk_path), path, { engine.tools.setParam("olive.path", it) }, Modifier.weight(1f), 2, 0.1, 10.0, "cm")
            }
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            ChipChoices(listOf(SimSample.OLIVE_EXTRA_VIRGIN, SimSample.OLIVE_VIRGIN, SimSample.OLIVE_OXIDISED), simSample, { sampleLabel(it) }) { s ->
                simSample = s; engine.setSimScene(SimScene.Cuvette(0.0, sample = s))
            }
        }
    }
}

private fun oilRes(c: OliveOil.Category) = when (c) {
    OliveOil.Category.EXTRA_VIRGIN -> R.string.oil_evoo
    OliveOil.Category.VIRGIN -> R.string.oil_voo
    OliveOil.Category.ABOVE_VIRGIN_LIMITS -> R.string.oil_above
}

// ================================= Drink colour =============================================

@Composable
fun DrinksTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val params by engine.tools.params.collectAsState()
    var simSample by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Cuvette)?.sample) }
    val kind = Drinks.Kind.entries[(params["drinks.kind"] ?: 0.0).toInt().coerceIn(0, 2)]
    val path = params["drinks.path.${kind.name}"] ?: if (kind == Drinks.Kind.WINE) 1.0 else 10.0
    val dilution = params["drinks.dilution"] ?: 1.0
    val fr = frame
    val abs = fr?.absorbance()
    ToolPage {
        AcquisitionRow(engine)
        Column(Modifier.padding(horizontal = 12.dp)) {
            ChipChoices(Drinks.Kind.entries, kind, { stringResource(when (it) { Drinks.Kind.BEER -> R.string.drk_beer; Drinks.Kind.WINE -> R.string.drk_wine; Drinks.Kind.HONEY -> R.string.drk_honey }) }) {
                engine.tools.setParam("drinks.kind", it.ordinal.toDouble())
            }
        }
        if (abs == null) Note(stringResource(R.string.conc_needs_blank))
        var label = ""; var main = Double.NaN; var detail = ""; var colour = "#8D6E63"
        when (kind) {
            Drinks.Kind.BEER -> {
                val b = if (fr != null && abs != null) Drinks.beer(fr.wavelengths, abs, path, dilution) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigValue("EBC", f(b?.ebc, 1), size = 28.sp); BigValue("SRM", f(b?.srm, 1), size = 28.sp)
                }
                Section(stringResource(R.string.drk_details)) {
                    KeyValue(stringResource(R.string.drk_beer_style), b?.let { stringResource(beerRes(it.srm)) } ?: "—", live = true)
                    KeyValue("A430 / A700", b?.let { "${f(it.a430, 3)} / ${f(it.a700, 3)}" } ?: "—", live = true)
                    KeyValue(stringResource(R.string.drk_turbidity), b?.let { stringResource(if (it.turbid) R.string.drk_turbid else R.string.drk_clear) } ?: "—", live = true)
                }
                if (b != null) { label = "EBC ${f(b.ebc, 1)}"; main = b.ebc; detail = "EBC %.1f; SRM %.1f".format(Locale.US, b.ebc, b.srm); colour = srmHex(b.srm) }
            }
            Drinks.Kind.WINE -> {
                val w = if (fr != null && abs != null) Drinks.wine(fr.wavelengths, abs, path) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigValue(stringResource(R.string.drk_intensity), f(w?.intensity, 2), size = 28.sp); BigValue(stringResource(R.string.drk_hue), f(w?.hue, 2), size = 28.sp)
                }
                Section(stringResource(R.string.drk_details)) {
                    KeyValue(stringResource(R.string.drk_composition), w?.let { stringResource(R.string.drk_composition_value, f(it.red, 0), f(it.yellow, 0), f(it.blue, 0)) } ?: "—", live = true)
                    KeyValue("A420 / A520 / A620", w?.let { "${f(it.a420, 2)} / ${f(it.a520, 2)} / ${f(it.a620, 2)}" } ?: "—", live = true)
                    Text(stringResource(R.string.drk_wine_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (w != null) { label = "CI ${f(w.intensity, 1)}"; main = w.intensity; detail = "CI %.2f; hue %.2f".format(Locale.US, w.intensity, w.hue); colour = "#880E4F" }
            }
            Drinks.Kind.HONEY -> {
                val h = if (fr != null && abs != null) Drinks.honey(fr.wavelengths, abs, path) else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigValue("Pfund", h?.let { "${f(it.pfundMm, 0)} mm" } ?: "—", size = 28.sp)
                    BigValue(stringResource(R.string.drk_grade), h?.let { stringResource(honeyRes(it.grade)) } ?: "—", size = 20.sp)
                }
                Section(stringResource(R.string.drk_details)) {
                    KeyValue("A635", f(h?.a635, 3), live = true)
                    Text(stringResource(R.string.drk_honey_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (h != null) { label = "Pfund ${f(h.pfundMm, 0)} mm"; main = h.pfundMm; detail = "Pfund %.0f mm; %s".format(Locale.US, h.pfundMm, h.grade.name); colour = "#F9A825" }
            }
        }
        if (fr != null && abs != null) SpectrumChart(fr.wavelengths, abs, height = 170.dp, yUnit = stringResource(R.string.unit_absorbance), xRange = 400.0..720.0,
            markers = when (kind) { Drinks.Kind.BEER -> listOf(430.0, 700.0); Drinks.Kind.WINE -> listOf(420.0, 520.0, 620.0); Drinks.Kind.HONEY -> listOf(635.0) })
        Section(stringResource(R.string.drk_settings)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(stringResource(R.string.drk_path), path, { engine.tools.setParam("drinks.path.${kind.name}", it) }, Modifier.weight(1f), 1, 0.1, 100.0, "mm")
                if (kind == Drinks.Kind.BEER) NumberField(stringResource(R.string.drk_dilution), dilution, { engine.tools.setParam("drinks.dilution", it) }, Modifier.weight(1f), 1, 1.0, 100.0, "×")
            }
            SaveResultButton(engine, ToolId.DRINKS, if (label.isNotEmpty()) SaveSpec(label, main, colour, detail) else null)
        }
        ReferencePanel(engine, RefKind.BLANK)
        SimPanel(engine) {
            val list = when (kind) {
                Drinks.Kind.BEER -> listOf(SimSample.BEER_LAGER, SimSample.BEER_ALE, SimSample.BEER_STOUT_DILUTED)
                Drinks.Kind.WINE -> listOf(SimSample.WINE_RED_1MM, SimSample.WINE_ROSE)
                Drinks.Kind.HONEY -> listOf(SimSample.HONEY_LIGHT, SimSample.HONEY_AMBER)
            }
            ChipChoices(list, simSample, { sampleLabel(it) }) { s -> simSample = s; engine.setSimScene(SimScene.Cuvette(0.0, sample = s)) }
        }
    }
}

private fun beerRes(srm: Double) = when {
    srm < 4 -> R.string.beer_straw; srm < 7 -> R.string.beer_gold; srm < 11 -> R.string.beer_amber
    srm < 20 -> R.string.beer_copper; srm < 30 -> R.string.beer_brown; else -> R.string.beer_black
}

private fun srmHex(srm: Double) = when {
    srm < 4 -> "#F8E08E"; srm < 7 -> "#E8B341"; srm < 11 -> "#C77C22"; srm < 20 -> "#9B4A1A"; srm < 30 -> "#5C2A0E"; else -> "#1F0D05"
}

private fun honeyRes(g: Drinks.HoneyGrade) = when (g) {
    Drinks.HoneyGrade.WATER_WHITE -> R.string.honey_water_white
    Drinks.HoneyGrade.EXTRA_WHITE -> R.string.honey_extra_white
    Drinks.HoneyGrade.WHITE -> R.string.honey_white
    Drinks.HoneyGrade.EXTRA_LIGHT_AMBER -> R.string.honey_extra_light_amber
    Drinks.HoneyGrade.LIGHT_AMBER -> R.string.honey_light_amber
    Drinks.HoneyGrade.AMBER -> R.string.honey_amber
    Drinks.HoneyGrade.DARK_AMBER -> R.string.honey_dark_amber
}

// ================================= Health check =============================================

@Composable
fun HealthTool(engine: AcquisitionEngine) {
    val frame by engine.frame.collectAsState()
    val conn by engine.connection.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dark by remember { mutableStateOf<HealthCheck.Dark?>(null) }
    var lin by remember { mutableStateOf<HealthCheck.Linearity?>(null) }
    var wave by remember { mutableStateOf<HealthCheck.Wavelength?>(null) }
    var busy by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    var simEmitter by remember { mutableStateOf((engine.simulator?.scene as? SimScene.Emit)?.emitter) }
    val info = (conn as? com.rfsat.pocketspectro.core.Connection.Ready)?.info

    fun run(step: Int, block: suspend () -> Unit) {
        if (busy != null) return
        busy = step; failed = false
        scope.launch {
            val saved = engine.store.value
            try { engine.setRunning(true); block() } catch (_: Exception) { failed = true }
            finally {
                engine.store.update { it.copy(integrationMs = saved.integrationMs, scansToAverage = saved.scansToAverage, boxcarWidth = saved.boxcarWidth) }
                engine.requestShutter(true); busy = null
            }
        }
    }

    ToolPage {
        AcquisitionRow(engine)
        if (busy != null) Note(stringResource(R.string.hc_running), OnGreen)
        if (failed) Note(stringResource(R.string.hc_failed), OffRed)
        Section(stringResource(R.string.hc_dark)) {
            Text(stringResource(R.string.hc_dark_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(onClick = { run(1) {
                engine.store.update { it.copy(scansToAverage = 1, boxcarWidth = 0) }
                engine.requestShutter(false)
                engine.nextFrames(2)
                val fs = engine.nextFrames(10)
                dark = HealthCheck.dark(fs.map { it.meanRaw }, engine.store.value.integrationMs)
            } }, enabled = busy == null && info != null, emphasis = true) { Text(stringResource(R.string.hc_run_dark)) }
            val d = dark
            KeyValue(stringResource(R.string.hc_dark_level), d?.let { "${f(it.level, 1)} counts @ ${f(it.integrationMs, 0)} ms" } ?: "—", live = true)
            KeyValue(stringResource(R.string.hc_read_noise), d?.let { "${f(it.noise, 2)} counts" } ?: "—", live = true)
            KeyValue(stringResource(R.string.hc_hot), d?.let { if (it.hotPixels.isEmpty()) stringResource(R.string.hc_none) else
                it.hotPixels.take(6).joinToString(", ") { p -> info?.wavelengths()?.getOrNull(p)?.let { w -> "$p (${f(w, 0)} nm)" } ?: "$p" } +
                    if (it.hotPixels.size > 6) " …" else "" } ?: "—", live = true)
        }
        Section(stringResource(R.string.hc_linearity)) {
            Text(stringResource(R.string.hc_linearity_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(onClick = { run(2) {
                val t = engine.store.value.integrationMs
                engine.store.update { it.copy(scansToAverage = 2, boxcarWidth = 0) }
                val pts = ArrayList<HealthCheck.LinearityPoint>()
                var idx: List<Int>? = null
                for (ms in listOf(t / 4, t / 2, t).map { it.coerceAtLeast(1.0) }) {
                    engine.store.update { it.copy(integrationMs = ms) }
                    val us = engine.store.value.integrationUs
                    engine.requestShutter(false)
                    val dk = engine.nextFrames(2) { it.integrationUs == us }.last().meanRaw
                    engine.requestShutter(true)
                    val lt = engine.nextFrames(2) { it.integrationUs == us }.last()
                    // Without a shutter the "dark" frame saw the light too: fall back to the dark test level.
                    val offset = if (engine.shutterAvailable.value) null else (dark?.level ?: lt.meanRaw.min())
                    val diff = DoubleArray(dk.size) { lt.meanRaw[it] - (offset ?: dk[it]) }
                    val use = idx ?: diff.indices.sortedByDescending { diff[it] }.take((diff.size / 10).coerceAtLeast(5)).also { idx = it }
                    pts += HealthCheck.LinearityPoint(ms, use.map { diff[it] }.average())
                }
                lin = HealthCheck.linearity(pts)
            } }, enabled = busy == null && info != null, emphasis = true) { Text(stringResource(R.string.hc_run_linearity)) }
            val l = lin
            KeyValue(stringResource(R.string.hc_rates), l?.let { it.points.indices.joinToString(" · ") { i -> "${f(it.points[i].integrationMs, 0)} ms: ${f(it.ratePerMs[i], 1)}/ms" } } ?: "—", live = true)
            KeyValue(stringResource(R.string.hc_deviation), l?.let { "${f(it.maxDeviationPercent, 2)} % · " +
                stringResource(if (it.maxDeviationPercent <= HealthCheck.MAX_LINEARITY_DEV) R.string.colour_pass else R.string.colour_fail) } ?: "—", live = true)
        }
        Section(stringResource(R.string.hc_wavelength)) {
            Text(stringResource(R.string.hc_wavelength_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ActionButton(onClick = { val fr = frame ?: return@ActionButton; wave = HealthCheck.wavelength(fr.wavelengths, fr.counts); if (wave == null) failed = true },
                enabled = frame != null && busy == null, emphasis = true) { Text(stringResource(R.string.hc_run_wavelength)) }
            val w = wave
            KeyValue(stringResource(R.string.hc_lines), w?.lines?.toString() ?: "—", live = true)
            KeyValue(stringResource(R.string.hc_shift), w?.let { "${"%+.2f".format(Locale.US, Math.round(it.meanShiftNm * 100) / 100.0 + 0.0)} nm (max ${f(it.maxShiftNm, 2)}) · " +
                stringResource(if (it.maxShiftNm <= HealthCheck.MAX_WAVELENGTH_SHIFT) R.string.colour_pass else R.string.colour_fail) } ?: "—", live = true)
        }
        Section(stringResource(R.string.hc_report)) {
            ActionButton(onClick = { info?.let { shareHealthReport(context, engine, it, dark, lin, wave) } },
                enabled = info != null && (dark != null || lin != null || wave != null)) { Text(stringResource(R.string.hc_share)) }
        }
        SimPanel(engine) {
            ChipChoices(listOf(SimEmitter.LAMP_2856K, SimEmitter.HG_AR_LAMP), simEmitter, { emitterLabel(it) }) { e -> simEmitter = e; engine.setSimScene(SimScene.Emit(e)) }
        }
    }
}

private fun shareHealthReport(context: android.content.Context, engine: AcquisitionEngine, info: com.rfsat.pocketspectro.core.DeviceInfo,
                              dark: HealthCheck.Dark?, lin: HealthCheck.Linearity?, wave: HealthCheck.Wavelength?) {
    val now = System.currentTimeMillis()
    val text = buildString {
        append("PocketSpectro spectrometer health check\n")
        append("Date: ").append(com.rfsat.pocketspectro.data.SessionLogger.iso(now)).append('\n')
        append("Spectrometer: ${info.model}, serial ${info.serial}, ${info.pixels} pixels, ")
        append("%.1f-%.1f nm\n\n".format(Locale.US, info.wavelengths().first(), info.wavelengths().last()))
        dark?.let { append("Dark: level %.1f counts at %.0f ms, read noise %.2f counts, hot pixels: %s\n".format(Locale.US, it.level, it.integrationMs, it.noise,
            if (it.hotPixels.isEmpty()) "none" else it.hotPixels.joinToString(" "))) }
        lin?.let { l -> append("Linearity: ").append(l.points.indices.joinToString("; ") { "%.1f ms %.2f counts/ms".format(Locale.US, l.points[it].integrationMs, l.ratePerMs[it]) })
            append(", max deviation %.2f %% (%s)\n".format(Locale.US, l.maxDeviationPercent, if (l.maxDeviationPercent <= HealthCheck.MAX_LINEARITY_DEV) "pass" else "fail")) }
        wave?.let { append("Wavelength (Hg-Ar): %d lines, mean shift %+.2f nm, max %.2f nm (%s)\n".format(Locale.US, it.lines, it.meanShiftNm, it.maxShiftNm,
            if (it.maxShiftNm <= HealthCheck.MAX_WAVELENGTH_SHIFT) "pass" else "fail")) }
    }
    val dir = File(engine.sessionsRoot, "health").apply { mkdirs() }
    val file = File(dir, "health_%tY%<tm%<td_%<tH%<tM%<tS.txt".format(java.util.Date(now)))
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(i, file.name)) } catch (_: Exception) {}
}
