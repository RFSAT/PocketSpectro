package com.rfsat.pocketspectro.tools

import android.content.Context
import com.rfsat.pocketspectro.core.DeviceInfo
import com.rfsat.pocketspectro.core.Frame
import com.rfsat.pocketspectro.data.GeoFix
import com.rfsat.pocketspectro.data.ToolEvent
import com.rfsat.pocketspectro.data.ToolEventJournal
import com.rfsat.pocketspectro.sim.SimEmitter
import com.rfsat.pocketspectro.sim.SimLight
import com.rfsat.pocketspectro.sim.SimModel
import com.rfsat.pocketspectro.sim.SimScene
import com.rfsat.pocketspectro.sim.SimTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10

/** The tools on the Tools tab, in display order. */
enum class ToolId { FIRE, EMISSION, VEGETATION, MATCH, CONCENTRATION, COLOUR, LIGHT, KINETICS }

/** Whether a tool can run on the connected spectrometer. */
sealed interface Availability {
    data object Ok : Availability
    /** Needs a unit covering [lo]..[hi] nm. */
    data class NeedsRange(val lo: Double, val hi: Double) : Availability
}

fun ToolId.availability(info: DeviceInfo?): Availability {
    if (info == null) return Availability.Ok
    val wl = info.wavelengths()
    return when (this) {
        ToolId.FIRE -> if (info.fireCapable) Availability.Ok else Availability.NeedsRange(755.0, 785.0)
        ToolId.VEGETATION -> if (Vegetation.supported(wl)) Availability.Ok else Availability.NeedsRange(660.0, 780.0)
        ToolId.COLOUR -> if (Colorimetry.supported(wl)) Availability.Ok else Availability.NeedsRange(380.0, 780.0)
        else -> Availability.Ok
    }
}

/** What the simulator should show when a tool is opened. */
fun ToolId.defaultScene(model: SimModel, nowMs: Long): SimScene = when (this) {
    ToolId.FIRE -> SimScene.Field
    ToolId.EMISSION -> SimScene.Emit(if (model == SimModel.UV) SimEmitter.HG_AR_LAMP else SimEmitter.FLAME_MIX)
    ToolId.VEGETATION -> SimScene.Reflect(SimTarget.FIELD_TRANSECT, SimLight.DAYLIGHT)
    ToolId.MATCH -> SimScene.Reflect(SimTarget.COTTON)
    ToolId.CONCENTRATION -> SimScene.Cuvette(5.0)
    ToolId.COLOUR -> SimScene.Reflect(SimTarget.TILE_RED)
    ToolId.LIGHT -> SimScene.Emit(if (model == SimModel.NIR) SimEmitter.LED_IR_850 else SimEmitter.LED_WHITE_COOL)
    ToolId.KINETICS -> SimScene.Cuvette(8.0, SimScene.Reaction(8.0, 0.01, nowMs))
}

/** Reference the simulator should show while a white/blank reference is stored for [scene]. */
fun blankFor(scene: SimScene): SimScene? = when (scene) {
    is SimScene.Reflect -> SimScene.Reflect(SimTarget.WHITE, scene.light)
    is SimScene.Cuvette -> SimScene.Cuvette(0.0)
    else -> null
}

/** Ratio of a frame's processed counts to its stored reference (reflectance / transmittance), or null. */
fun Frame.ratio(): DoubleArray? {
    val ref = reference ?: return null
    if (ref.size != counts.size || !darkCorrected) return null
    return DoubleArray(counts.size) { if (ref[it] > 1.0) counts[it] / ref[it] else Double.NaN }
}

fun Frame.absorbance(): DoubleArray? = ratio()?.let { r -> DoubleArray(r.size) { if (r[it] > 0) -log10(r[it]) else Double.NaN } }

/**
 * Per-frame work of the tools that must keep running while another screen is shown: vegetation
 * survey recording, the emission-line alarm and kinetics recording. Runs on the device thread.
 */
class ToolHub(context: Context, private val journal: ToolEventJournal, private val alarm: (Boolean) -> Unit) {
    private val prefs = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(ToolId.entries.firstOrNull { it.name == prefs.getString("active", null) })
    /** Tool shown on the Tools tab (null = the list). */
    val active: StateFlow<ToolId?> = _active.asStateFlow()
    fun setActive(t: ToolId?) { _active.value = t; prefs.edit().putString("active", t?.name).apply() }

    private val _eventsVersion = MutableStateFlow(0)
    val eventsVersion: StateFlow<Int> = _eventsVersion.asStateFlow()

    fun record(e: ToolEvent) { journal.record(e); _eventsVersion.value++ }
    fun clearEvents() { journal.clear(); _eventsVersion.value++ }

    // ---- vegetation survey -------------------------------------------------------------------

    data class VegState(val recording: Boolean = false, val intervalSec: Double = 5.0, val points: Int = 0, val last: Vegetation.Result? = null)
    private val _veg = MutableStateFlow(VegState(intervalSec = prefs.getFloat("vegInterval", 5f).toDouble()))
    val vegetation: StateFlow<VegState> = _veg.asStateFlow()
    private var vegLastMs = 0L
    fun vegRecording(on: Boolean) { _veg.value = _veg.value.copy(recording = on); vegLastMs = 0L }
    fun vegInterval(s: Double) { _veg.value = _veg.value.copy(intervalSec = s); prefs.edit().putFloat("vegInterval", s.toFloat()).apply() }

    // ---- emission lines -----------------------------------------------------------------------

    data class EmissionState(
        val config: EmissionLines.Config,
        val armed: Boolean = false,
        val lines: List<EmissionLines.Measurement> = emptyList(),
        /** Height above continuum of the strongest line over the last frames. */
        val history: List<Double> = emptyList(),
        val alarmCount: Int = 0,
    )
    private val _emission = MutableStateFlow(EmissionState(loadEmissionConfig()))
    val emission: StateFlow<EmissionState> = _emission.asStateFlow()
    private var lastPresent = emptySet<String>()

    fun emissionConfig(c: EmissionLines.Config) {
        _emission.value = _emission.value.copy(config = c, history = emptyList())
        prefs.edit().putString("emPreset", c.preset.name).putString("emCustom", EmissionLines.formatCustom(c.custom))
            .putFloat("emThr", c.thresholdPct.toFloat()).putFloat("emSnr", c.minSnr.toFloat()).apply()
    }
    fun emissionArm(on: Boolean) { _emission.value = _emission.value.copy(armed = on); if (!on) alarm(false); lastPresent = emptySet() }

    private fun loadEmissionConfig() = EmissionLines.Config(
        preset = EmissionLines.Preset.entries.firstOrNull { it.name == prefs.getString("emPreset", null) } ?: EmissionLines.Preset.FLAME,
        custom = EmissionLines.parseCustom(prefs.getString("emCustom", "") ?: ""),
        thresholdPct = prefs.getFloat("emThr", 5f).toDouble(),
        minSnr = prefs.getFloat("emSnr", 5f).toDouble(),
    )

    // ---- kinetics -------------------------------------------------------------------------------

    enum class KQuantity { ABSORBANCE, RATIO, COUNTS }
    data class KineticsState(
        val recording: Boolean = false,
        val quantity: KQuantity = KQuantity.ABSORBANCE,
        val nm: Double = 664.0,
        val halfWidthNm: Double = 2.0,
        val startMs: Long = 0L,
        val points: List<Kinetics.Point> = emptyList(),
    )
    private val _kin = MutableStateFlow(KineticsState(nm = prefs.getFloat("kinNm", 664f).toDouble()))
    val kinetics: StateFlow<KineticsState> = _kin.asStateFlow()
    fun kineticsSetup(q: KQuantity, nm: Double) { _kin.value = _kin.value.copy(quantity = q, nm = nm); prefs.edit().putFloat("kinNm", nm.toFloat()).apply() }
    fun kineticsRecording(on: Boolean) {
        val k = _kin.value
        _kin.value = if (on) k.copy(recording = true, startMs = System.currentTimeMillis(), points = emptyList()) else k.copy(recording = false)
    }
    fun kineticsClear() { _kin.value = _kin.value.copy(points = emptyList(), recording = false) }

    fun kineticsValue(f: Frame, k: KineticsState): Double? {
        val lo = k.nm - k.halfWidthNm; val hi = k.nm + k.halfWidthNm
        return when (k.quantity) {
            KQuantity.COUNTS -> Spectra.bandMean(f.wavelengths, f.counts, lo, hi)
            KQuantity.RATIO -> f.ratio()?.let { Spectra.bandMean(f.wavelengths, it, lo, hi) }
            KQuantity.ABSORBANCE -> f.reference?.takeIf { f.darkCorrected }?.let { ref ->
                BeerLambert.absorbance(f.wavelengths, f.counts, ref, k.nm, k.halfWidthNm)
            }
        }?.takeIf { it.isFinite() }
    }

    // ---- concentration (Beer-Lambert) ---------------------------------------------------------

    data class ConcState(
        val nm: Double = 664.0,
        val halfWidthNm: Double = 2.0,
        val units: String = "mg/L",
        val standards: List<BeerLambert.Standard> = emptyList(),
    ) {
        val calibration get() = BeerLambert.calibrate(standards)
    }
    private val _conc = MutableStateFlow(ConcState(
        nm = prefs.getFloat("concNm", 664f).toDouble(),
        halfWidthNm = prefs.getFloat("concHw", 2f).toDouble(),
        units = prefs.getString("concUnits", "mg/L") ?: "mg/L",
        standards = (prefs.getString("concStd", "") ?: "").split(';').mapNotNull { s ->
            val p = s.split(':'); val c = p.getOrNull(0)?.toDoubleOrNull(); val a = p.getOrNull(1)?.toDoubleOrNull()
            if (c != null && a != null) BeerLambert.Standard(c, a) else null
        },
    ))
    val concentration: StateFlow<ConcState> = _conc.asStateFlow()
    fun concUpdate(change: (ConcState) -> ConcState) {
        val c = change(_conc.value); _conc.value = c
        prefs.edit().putFloat("concNm", c.nm.toFloat()).putFloat("concHw", c.halfWidthNm.toFloat()).putString("concUnits", c.units)
            .putString("concStd", c.standards.joinToString(";") { "${it.concentration}:${it.absorbance}" }).apply()
    }

    // ---- colour -----------------------------------------------------------------------------------

    data class ColourState(val standard: Colorimetry.Lab? = null, val tolerance: Double = 2.0)
    private val _colour = MutableStateFlow(ColourState(
        standard = prefs.getString("colStd", null)?.split(':')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }
            ?.let { Colorimetry.Lab(it[0], it[1], it[2]) },
        tolerance = prefs.getFloat("colTol", 2f).toDouble(),
    ))
    val colour: StateFlow<ColourState> = _colour.asStateFlow()
    fun colourUpdate(change: (ColourState) -> ColourState) {
        val c = change(_colour.value); _colour.value = c
        prefs.edit().putString("colStd", c.standard?.let { "${it.l}:${it.a}:${it.b}" }).putFloat("colTol", c.tolerance.toFloat()).apply()
    }

    // ---- light source ------------------------------------------------------------------------------

    data class LightState(val lampKelvin: Double = 2856.0, val response: DoubleArray? = null, val luxScale: Double? = null)
    private val _light = MutableStateFlow(LightState(
        lampKelvin = prefs.getFloat("lampK", 2856f).toDouble(),
        response = prefs.getString("lightResp", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.toDoubleArray()?.takeIf { it.size > 10 },
        luxScale = prefs.getFloat("luxScale", -1f).toDouble().takeIf { it > 0 },
    ))
    val light: StateFlow<LightState> = _light.asStateFlow()
    fun lightUpdate(change: (LightState) -> LightState) {
        val c = change(_light.value); _light.value = c
        prefs.edit().putFloat("lampK", c.lampKelvin.toFloat())
            .putString("lightResp", c.response?.joinToString(",") { "%.5g".format(java.util.Locale.US, it) })
            .putFloat("luxScale", (c.luxScale ?: -1.0).toFloat()).apply()
    }

    // ---- spectral match ------------------------------------------------------------------------------

    private val _matchThreshold = MutableStateFlow(prefs.getFloat("matchThr", 0.95f).toDouble())
    val matchThreshold: StateFlow<Double> = _matchThreshold.asStateFlow()
    fun matchThreshold(v: Double) { _matchThreshold.value = v; prefs.edit().putFloat("matchThr", v.toFloat()).apply() }
    private val _libraryVersion = MutableStateFlow(0)
    /** Increments when the library changes (screens reload it). */
    val libraryVersion: StateFlow<Int> = _libraryVersion.asStateFlow()
    fun libraryChanged() { _libraryVersion.value++ }

    // ---- per frame ------------------------------------------------------------------------------

    fun onFrame(f: Frame, fix: GeoFix?, info: DeviceInfo) {
        // Vegetation survey
        val v = _veg.value
        if (v.recording || _active.value == ToolId.VEGETATION) {
            val r = f.ratio()?.let { Vegetation.analyse(f.wavelengths, it) }
            var points = v.points
            if (v.recording && r != null && fix != null && r.ndvi.isFinite() && f.timeMs - vegLastMs >= v.intervalSec * 1000) {
                vegLastMs = f.timeMs
                record(ToolEvent(f.timeMs, fix.latitude, fix.longitude, fix.accuracyM?.toDouble(), ToolId.VEGETATION.name,
                    "NDVI %.2f".format(java.util.Locale.US, r.ndvi), r.ndvi, Vegetation.colour(r.ndvi),
                    listOfNotNull(r.rep?.let { "REP %.1f nm".format(java.util.Locale.US, it) }, r.cire?.let { "CIre %.2f".format(java.util.Locale.US, it) },
                        r.wbi?.let { "WBI %.3f".format(java.util.Locale.US, it) }, r.pri?.let { "PRI %.3f".format(java.util.Locale.US, it) }).joinToString("; "),
                    info.model))
                points++
            }
            _veg.value = _veg.value.copy(last = r, points = points)
        }

        // Emission lines
        val e = _emission.value
        if (e.armed || _active.value == ToolId.EMISSION) {
            val m = EmissionLines.measure(f.wavelengths, f.counts, e.config)
            val strongest = m.maxByOrNull { it.height }?.height ?: 0.0
            var alarms = e.alarmCount
            val present = m.filter { it.present }.map { it.line.label + it.line.nm }.toSet()
            if (e.armed) {
                val new = m.filter { it.present && (it.line.label + it.line.nm) !in lastPresent }
                if (new.isNotEmpty()) {
                    alarm(true); alarms++
                    if (fix != null) new.forEach { line ->
                        record(ToolEvent(f.timeMs, fix.latitude, fix.longitude, fix.accuracyM?.toDouble(), ToolId.EMISSION.name,
                            "${line.line.label} ${"%.1f".format(java.util.Locale.US, line.line.nm)} nm", line.pctAbove, "#8E24AA",
                            "+%.0f %% above continuum; S/N %.0f".format(java.util.Locale.US, line.pctAbove, line.snr), info.model))
                    }
                } else if (present.isEmpty() && lastPresent.isNotEmpty()) alarm(false)
            }
            lastPresent = present
            _emission.value = e.copy(lines = m, history = (e.history + strongest).takeLast(300), alarmCount = alarms)
        }

        // Kinetics
        val k = _kin.value
        if (k.recording) {
            kineticsValue(f, k)?.let { value ->
                _kin.value = k.copy(points = (k.points + Kinetics.Point((f.timeMs - k.startMs) / 1000.0, value)).takeLast(20_000))
            }
        }
    }
}
