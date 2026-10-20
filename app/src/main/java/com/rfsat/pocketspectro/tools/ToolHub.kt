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

/** Groups on the Tools tab. */
enum class ToolGroup { MONITORING, FIELD, LAB, LIGHT, EXPOSURE, INSTRUMENT }

/** The tools on the Tools tab, in display order within their group. */
enum class ToolId(val group: ToolGroup) {
    FIRE(ToolGroup.MONITORING), EMISSION(ToolGroup.MONITORING), SUN(ToolGroup.MONITORING), GAS_COLUMNS(ToolGroup.MONITORING),
    UV_INDEX(ToolGroup.MONITORING), LAMPS(ToolGroup.MONITORING),
    VEGETATION(ToolGroup.FIELD), FLUORESCENCE(ToolGroup.FIELD), LEAF_CLIP(ToolGroup.FIELD), GREENHOUSE(ToolGroup.FIELD),
    WATER(ToolGroup.FIELD), WATER_COLOUR(ToolGroup.FIELD), SOIL(ToolGroup.FIELD),
    CONCENTRATION(ToolGroup.LAB), TEST_KIT(ToolGroup.LAB), PH(ToolGroup.LAB), FLUORIMETER(ToolGroup.LAB), NUCLEIC(ToolGroup.LAB),
    GROWTH(ToolGroup.LAB), KINETICS(ToolGroup.LAB), PREDICT(ToolGroup.LAB), MATCH(ToolGroup.LAB), RAMAN(ToolGroup.LAB),
    OLIVE(ToolGroup.LAB), DRINKS(ToolGroup.LAB), COLOUR(ToolGroup.LAB),
    LIGHT(ToolGroup.LIGHT), CRI(ToolGroup.LIGHT), DISPLAY(ToolGroup.LIGHT), TEMPERATURE(ToolGroup.LIGHT), FILM(ToolGroup.LIGHT),
    FILTER(ToolGroup.LIGHT), PIGMENT(ToolGroup.LIGHT), GEM(ToolGroup.LIGHT),
    LIGHT_DOSE(ToolGroup.EXPOSURE), UV_DOSE(ToolGroup.EXPOSURE), SKIN(ToolGroup.EXPOSURE), PULSE(ToolGroup.EXPOSURE), DIARY(ToolGroup.EXPOSURE),
    HEALTH(ToolGroup.INSTRUMENT), PHYSICS(ToolGroup.INSTRUMENT),
}

/** Whether a tool can run on the connected spectrometer. */
sealed interface Availability {
    data object Ok : Availability
    /** Needs a unit covering [lo]..[hi] nm. */
    data class NeedsRange(val lo: Double, val hi: Double) : Availability
    /** Usable, but the unit covers only [coveredLo]..[coveredHi] of the [lo]..[hi] nm the tool needs. */
    data class Partial(val lo: Double, val hi: Double, val coveredLo: Double, val coveredHi: Double) : Availability
}

/** Wavelength range a tool needs (nm), or null when any unit will do. */
val ToolId.needsRange: ClosedFloatingPointRange<Double>? get() = when (this) {
    ToolId.FIRE -> 755.0..785.0
    ToolId.VEGETATION -> 660.0..780.0
    ToolId.COLOUR, ToolId.SOIL, ToolId.CRI, ToolId.DISPLAY, ToolId.LIGHT_DOSE -> 380.0..780.0
    ToolId.SUN -> 435.0..1025.0
    ToolId.UV_INDEX, ToolId.UV_DOSE -> 290.0..400.0
    ToolId.LAMPS -> 430.0..620.0
    ToolId.FLUORESCENCE -> 752.0..774.0
    ToolId.WATER -> 218.0..277.0
    ToolId.OLIVE -> 230.0..274.0
    ToolId.DRINKS -> 415.0..705.0
    ToolId.GAS_COLUMNS -> 308.0..490.0
    ToolId.LEAF_CLIP -> 645.0..785.0
    ToolId.GREENHOUSE -> 400.0..750.0
    ToolId.WATER_COLOUR -> 400.0..710.0
    ToolId.PH -> 430.0..620.0
    ToolId.NUCLEIC -> 228.0..322.0
    ToolId.GROWTH -> 595.0..605.0
    ToolId.PIGMENT -> 420.0..700.0
    ToolId.SKIN -> 440.0..660.0
    ToolId.PULSE -> 650.0..900.0
    else -> null
}

/**
 * Whether a tool can run on the connected spectrometer. A unit covering the whole range is Ok; one
 * covering part of it is Partial (the tool runs, with a warning that results may be incomplete);
 * only a unit with no useful overlap is NeedsRange.
 */
fun ToolId.availability(info: DeviceInfo?): Availability {
    if (info == null) return Availability.Ok
    val wl = info.wavelengths()
    val need = needsRange ?: return Availability.Ok
    // Tools that work fully on one of alternative bands.
    val fullAlt = when (this) {
        ToolId.FIRE -> info.fireCapable
        ToolId.SUN -> SunPhotometer.channels(wl).size >= 4
        ToolId.GAS_COLUMNS -> GasColumns.ozoneSupported(wl) || Spectra.covers(wl, GasColumns.NO2_LO, GasColumns.NO2_HI)
        ToolId.LEAF_CLIP -> Spectra.covers(wl, 645.0, 655.0) && (Spectra.covers(wl, 935.0, 945.0) || Spectra.covers(wl, 775.0, 785.0))
        ToolId.GREENHOUSE -> Spectra.covers(wl, 400.0, 700.0)
        ToolId.SKIN -> Spectra.covers(wl, 555.0, 660.0) && Spectra.covers(wl, 435.0, 525.0)
        ToolId.PULSE -> Spectra.covers(wl, 650.0, 700.0) || Spectra.covers(wl, 800.0, 900.0)
        else -> false
    }
    if (fullAlt || Spectra.covers(wl, need.start, need.endInclusive)) return Availability.Ok
    val lo = maxOf(wl.first(), need.start); val hi = minOf(wl.last(), need.endInclusive)
    // Useful overlap: at least a quarter of the range, or 20 nm of it.
    return if (hi - lo >= minOf(20.0, (need.endInclusive - need.start) * 0.25)) Availability.Partial(need.start, need.endInclusive, lo, hi)
    else Availability.NeedsRange(need.start, need.endInclusive)
}

/** What the simulator should show when a tool is opened. */
fun ToolId.defaultScene(model: SimModel, nowMs: Long, lonDeg: Double = -6.26): SimScene = when (this) {
    ToolId.FIRE -> SimScene.Field
    ToolId.EMISSION -> SimScene.Emit(if (model == SimModel.UV) SimEmitter.HG_AR_LAMP else SimEmitter.FLAME_MIX)
    ToolId.VEGETATION -> SimScene.Reflect(SimTarget.FIELD_TRANSECT, SimLight.DAYLIGHT)
    ToolId.MATCH -> SimScene.Reflect(SimTarget.COTTON)
    ToolId.CONCENTRATION -> SimScene.Cuvette(5.0)
    ToolId.COLOUR -> SimScene.Reflect(SimTarget.TILE_RED)
    ToolId.LIGHT -> SimScene.Emit(if (model == SimModel.NIR) SimEmitter.LED_IR_850 else SimEmitter.LED_WHITE_COOL)
    ToolId.KINETICS -> SimScene.Cuvette(8.0, SimScene.Reaction(8.0, 0.01, nowMs))
    ToolId.SUN -> SimScene.Sun(0.12, 1.3, 1.5, com.rfsat.pocketspectro.sim.SimClock(nowMs, Solar.solarHourToUtcMs(nowMs, lonDeg, 10.5)))
    ToolId.UV_INDEX -> SimScene.SunUv(300.0, com.rfsat.pocketspectro.sim.SimClock(nowMs, Solar.solarHourToUtcMs(nowMs, lonDeg, 12.5)))
    ToolId.LAMPS -> SimScene.Emit(SimEmitter.LAMP_HPS)
    ToolId.FLUORESCENCE -> SimScene.Reflect(SimTarget.LEAF_HEALTHY, SimLight.DAYLIGHT)
    ToolId.WATER -> SimScene.Cuvette(0.0, sample = com.rfsat.pocketspectro.sim.SimSample.WATER_NITRATE)
    ToolId.PREDICT -> SimScene.Fruit(1L)
    ToolId.RAMAN -> SimScene.Raman(Raman.LIBRARY.first().name)
    ToolId.OLIVE -> SimScene.Cuvette(0.0, sample = com.rfsat.pocketspectro.sim.SimSample.OLIVE_EXTRA_VIRGIN)
    ToolId.DRINKS -> SimScene.Cuvette(0.0, sample = com.rfsat.pocketspectro.sim.SimSample.BEER_LAGER)
    ToolId.TEMPERATURE -> SimScene.Emit(SimEmitter.BODY_1500K)
    ToolId.FILM -> SimScene.Film(5000.0)
    ToolId.FILTER -> SimScene.Transmit(com.rfsat.pocketspectro.sim.SimFilter.SUNGLASS_CAT3)
    ToolId.HEALTH -> SimScene.Emit(SimEmitter.LAMP_2856K)
    ToolId.GAS_COLUMNS -> SimScene.Sun(0.10, 1.3, 1.5, com.rfsat.pocketspectro.sim.SimClock(nowMs, Solar.solarHourToUtcMs(nowMs, lonDeg, 12.0)), 320.0,
        no2Du = if (model == SimModel.UV) 0.0 else 0.0)
    ToolId.LEAF_CLIP -> SimScene.LeafClip(42.0)
    ToolId.GREENHOUSE -> SimScene.Emit(SimEmitter.LED_GROW)
    ToolId.WATER_COLOUR -> SimScene.WaterSurface(com.rfsat.pocketspectro.sim.SimWater.ALGAL_BLOOM)
    ToolId.SOIL -> SimScene.Reflect(SimTarget.SOIL)
    ToolId.TEST_KIT -> SimScene.Kit(TestKits.Method.CHLORINE_DPD.ordinal, 1.2)
    ToolId.PH -> SimScene.Indicator(Indicator.Dye.BROMOTHYMOL_BLUE.ordinal, 7.2)
    ToolId.FLUORIMETER -> SimScene.Fluor(when (model) { SimModel.NIR -> Fluorimetry.Preset.CHLOROPHYLL_A; SimModel.VIS -> Fluorimetry.Preset.FLUORESCEIN; SimModel.UV -> Fluorimetry.Preset.QUININE }.ordinal, 10.0)
    ToolId.NUCLEIC -> SimScene.Cuvette(0.0, sample = com.rfsat.pocketspectro.sim.SimSample.DNA_PURE)
    ToolId.GROWTH -> SimScene.Culture(nowMs)
    ToolId.CRI -> SimScene.Emit(SimEmitter.LED_WHITE_WARM)
    ToolId.DISPLAY -> SimScene.Display(com.rfsat.pocketspectro.sim.SimDisplay.OLED, 0)
    ToolId.PIGMENT -> SimScene.Pigment(Pigments.LIBRARY.first().name)
    ToolId.GEM -> SimScene.Gem("Blue sapphire (Fe)")
    ToolId.LIGHT_DOSE -> SimScene.Day(com.rfsat.pocketspectro.sim.SimClock(nowMs, Solar.solarHourToUtcMs(nowMs, lonDeg, 7.0), 120.0))
    ToolId.UV_DOSE -> SimScene.SunUv(300.0, com.rfsat.pocketspectro.sim.SimClock(nowMs, Solar.solarHourToUtcMs(nowMs, lonDeg, 10.0), 60.0))
    ToolId.SKIN -> SimScene.Skin(0.4, 0.4, 1.0)
    ToolId.PULSE -> SimScene.Finger(68.0, nowMs)
    ToolId.DIARY -> SimScene.Field
    ToolId.PHYSICS -> SimScene.Emit(SimEmitter.H_TUBE)
}

/** Reference the simulator should show while a white/blank reference is stored for [scene]. */
fun blankFor(scene: SimScene): SimScene? = when (scene) {
    is SimScene.Reflect -> SimScene.Reflect(SimTarget.WHITE, scene.light)
    is SimScene.Cuvette -> SimScene.Cuvette(0.0)
    is SimScene.Transmit -> SimScene.Transmit(com.rfsat.pocketspectro.sim.SimFilter.OPEN)
    is SimScene.Film, is SimScene.Fruit, is SimScene.Pigment, is SimScene.Skin -> SimScene.Reflect(SimTarget.WHITE)
    is SimScene.Culture, is SimScene.Kit, is SimScene.Indicator -> SimScene.Cuvette(0.0)
    is SimScene.Fluor -> scene.copy(concentration = 0.0)
    is SimScene.WaterSurface -> SimScene.Reflect(SimTarget.WHITE, SimLight.DAYLIGHT)
    is SimScene.LeafClip -> SimScene.LeafClip(0.0)
    is SimScene.Gem -> if (scene.fluorescence) null else SimScene.Transmit(com.rfsat.pocketspectro.sim.SimFilter.OPEN)
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
class ToolHub(context: Context, private val journal: ToolEventJournal, private val alarm: (Boolean) -> Unit,
              /** Changes the integration time (auto-exposure of the exposure loggers). */
              private val setIntegrationMs: (Double) -> Unit = {},
              /** Daily exposure logs (null in tests). */
              val exposure: com.rfsat.pocketspectro.data.ExposureLog? = null) {
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
        // Per-second scale since 1.8.0 (independent of the integration time); older per-frame scales are not reused.
        luxScale = prefs.getFloat("luxScaleS", -1f).toDouble().takeIf { it > 0 },
    ))
    val light: StateFlow<LightState> = _light.asStateFlow()
    fun lightUpdate(change: (LightState) -> LightState) {
        val c = change(_light.value); _light.value = c
        prefs.edit().putFloat("lampK", c.lampKelvin.toFloat())
            .putString("lightResp", c.response?.joinToString(",") { "%.5g".format(java.util.Locale.US, it) })
            .putFloat("luxScaleS", (c.luxScale ?: -1.0).toFloat()).apply()
    }

    // ---- spectral match ------------------------------------------------------------------------------

    private val _matchThreshold = MutableStateFlow(prefs.getFloat("matchThr", 0.95f).toDouble())
    val matchThreshold: StateFlow<Double> = _matchThreshold.asStateFlow()
    fun matchThreshold(v: Double) { _matchThreshold.value = v; prefs.edit().putFloat("matchThr", v.toFloat()).apply() }
    private val _libraryVersion = MutableStateFlow(0)
    /** Increments when the library changes (screens reload it). */
    val libraryVersion: StateFlow<Int> = _libraryVersion.asStateFlow()
    fun libraryChanged() { _libraryVersion.value++ }

    // ---- simple numeric settings of the newer tools (persisted) -------------------------------------

    private val _params = MutableStateFlow(
        (prefs.getString("params", "") ?: "").split(';').mapNotNull { kv -> kv.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { k to it } } }.toMap())
    /** Tool settings by key (e.g. "olive.c"), see the tool screens for defaults. */
    val params: StateFlow<Map<String, Double>> = _params.asStateFlow()
    fun param(key: String, default: Double) = _params.value[key] ?: default
    fun setParam(key: String, v: Double?) {
        val m = _params.value.toMutableMap(); if (v == null) m.remove(key) else m[key] = v
        _params.value = m
        prefs.edit().putString("params", m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    // ---- sun photometer ------------------------------------------------------------------------------

    data class SunState(
        val v0: Map<Double, Double> = emptyMap(),
        val langley: Boolean = false,
        val langleyPoints: List<SunPhotometer.LangleyPoint> = emptyList(),
        val signals: Map<Double, Double> = emptyMap(),
        val position: Solar.Position? = null,
        val airmass: Double? = null,
        val distFactor: Double = 1.0,
        val result: SunPhotometer.Result? = null,
        val saturated: Boolean = false,
        /** (solar time, AOD 500, PWV) for the chart. */
        val series: List<Triple<Long, Double?, Double?>> = emptyList(),
        val siteFromGps: Boolean = false,
        val sunTimeMs: Long = 0L,
    )
    private val _sun = MutableStateFlow(SunState(v0 = (prefs.getString("sunV0", "") ?: "").split(';').mapNotNull { kv ->
        kv.split(':').takeIf { it.size == 2 }?.let { (k, v) -> val a = k.toDoubleOrNull(); val b = v.toDoubleOrNull(); if (a != null && b != null) a to b else null }
    }.toMap()))
    val sun: StateFlow<SunState> = _sun.asStateFlow()
    private var sunLastPointMs = 0L
    private var sunLastSeriesMs = 0L

    fun sunAtmosphere() = SunPhotometer.Atmosphere(Solar.pressureAt(param("sun.height", 0.0)), param("sun.ozone", 300.0))
    fun sunCalibration(v0: Map<Double, Double>) {
        _sun.value = _sun.value.copy(v0 = v0)
        prefs.edit().putString("sunV0", v0.entries.joinToString(";") { "${it.key}:${it.value}" }).apply()
    }
    fun sunLangley(on: Boolean) { _sun.value = _sun.value.copy(langley = on, langleyPoints = if (on) emptyList() else _sun.value.langleyPoints); sunLastPointMs = 0L }
    fun sunClearSeries() { _sun.value = _sun.value.copy(series = emptyList()) }

    /** Latitude/longitude for the sun: the GPS fix, else the site entered in the tool. */
    fun site(fix: GeoFix?): Pair<Double, Double> = fix?.let { it.latitude to it.longitude } ?: (param("sun.lat", 53.35) to param("sun.lon", -6.26))

    private fun sunFrame(f: Frame, sunTimeMs: Long, site: Pair<Double, Double>, gps: Boolean) {
        val pos = Solar.position(site.first, site.second, sunTimeMs)
        val m = Solar.airmass(pos.zenithDeg)
        val df = Solar.distanceFactor(sunTimeMs)
        val s = _sun.value
        val sig = if (f.darkCorrected) SunPhotometer.signals(f.wavelengths, f.counts, f.integrationUs) else emptyMap()
        val usable = sig.isNotEmpty() && pos.zenithDeg < 85.0 && !f.saturated
        val res = if (usable && s.v0.isNotEmpty()) SunPhotometer.analyse(sig, s.v0, m, df, sunAtmosphere()) else null
        var pts = s.langleyPoints
        if (s.langley && usable && sunTimeMs - sunLastPointMs >= 20_000) { sunLastPointMs = sunTimeMs; pts = (pts + SunPhotometer.LangleyPoint(m, sig, df)).takeLast(3000) }
        var series = s.series
        if (res != null && sunTimeMs - sunLastSeriesMs >= 30_000) { sunLastSeriesMs = sunTimeMs; series = (series + Triple(sunTimeMs, res.aod500, res.pwvCm)).takeLast(600) }
        _sun.value = s.copy(signals = sig, position = pos, airmass = m, distFactor = df, result = res, saturated = f.saturated,
            langleyPoints = pts, series = series, siteFromGps = gps, sunTimeMs = sunTimeMs)
    }

    // ---- lamp survey ----------------------------------------------------------------------------------

    data class LampState(
        val recording: Boolean = false,
        val intervalSec: Double = 5.0,
        val points: Int = 0,
        val result: LampClassifier.Result? = null,
        val light: LightMetrics.Result? = null,
        val health: LightHealth.Result? = null,
    )
    private val _lamps = MutableStateFlow(LampState(intervalSec = prefs.getFloat("lampInterval", 5f).toDouble()))
    val lamps: StateFlow<LampState> = _lamps.asStateFlow()
    private var lampLastMs = 0L
    fun lampRecording(on: Boolean) { _lamps.value = _lamps.value.copy(recording = on); lampLastMs = 0L }
    fun lampInterval(s: Double) { _lamps.value = _lamps.value.copy(intervalSec = s); prefs.edit().putFloat("lampInterval", s.toFloat()).apply() }

    /** Counts divided by the stored lamp response (Light tool), when it fits this spectrometer. */
    fun spd(f: Frame): DoubleArray = LightMetrics.corrected(f.counts, _light.value.response?.takeIf { it.size == f.counts.size })

    // ---- teach and predict ------------------------------------------------------------------------------

    private val _predictModel = MutableStateFlow(prefs.getString("predictModel", null))
    /** Name of the model shown in Teach and predict. */
    val predictModel: StateFlow<String?> = _predictModel.asStateFlow()
    fun predictSelect(name: String?) { _predictModel.value = name; prefs.edit().putString("predictModel", name).apply() }
    private val _predictVersion = MutableStateFlow(0)
    /** Increments whenever a model file changes. */
    val predictVersion: StateFlow<Int> = _predictVersion.asStateFlow()
    fun predictChanged() { _predictVersion.value++ }

    // ---- fire temperature (shown on the Fire tool) ------------------------------------------------------

    data class FireTemp(val result: Pyrometry.Result? = null, val frames: Int = 0, val calibrated: Boolean = false)
    private val _fireTemp = MutableStateFlow(FireTemp())
    val fireTemp: StateFlow<FireTemp> = _fireTemp.asStateFlow()
    /** Recent raw spectra without fire; the background is their per-pixel median (robust to a passing car or a moved probe). */
    private val fireBgFrames = ArrayDeque<DoubleArray>()
    private var fireBg: DoubleArray? = null
    private var fireExcess: DoubleArray? = null
    private var lastActive: ToolId? = null

    private fun fireTempFrame(f: Frame) {
        val r = f.fire ?: return
        val resp = _light.value.response?.takeIf { it.size == f.meanRaw.size }
        if (!r.fire) {
            // Raw counts are used: the dark cancels in the difference.
            if (fireBgFrames.firstOrNull()?.size != f.meanRaw.size) fireBgFrames.clear()
            fireBgFrames.addLast(f.meanRaw.copyOf()); while (fireBgFrames.size > 9) fireBgFrames.removeFirst()
            val n = fireBgFrames.size
            val col = DoubleArray(n); val frames = fireBgFrames.toList()
            fireBg = DoubleArray(f.meanRaw.size) { i -> for (k in 0 until n) col[k] = frames[k][i]; col.sort(); if (n % 2 == 1) col[n / 2] else (col[n / 2 - 1] + col[n / 2]) / 2 }
            fireExcess = null
            _fireTemp.value = FireTemp(null, 0, resp != null)
            return
        }
        val bg = fireBg ?: return
        if (bg.size != f.meanRaw.size) return
        val ex = DoubleArray(bg.size) { f.meanRaw[it] - bg[it] }
        val avg = fireExcess?.let { a -> DoubleArray(a.size) { 0.7 * a[it] + 0.3 * ex[it] } } ?: ex
        fireExcess = avg
        val n = _fireTemp.value.frames + 1
        val fit = if (resp != null) Pyrometry.fit(f.wavelengths, avg, resp, 700.0, 1080.0, Pyrometry.FIRE_EXCLUDE, minFraction = 0.10) else null
        _fireTemp.value = FireTemp(fit, n, resp != null)
    }

    // ---- string settings ---------------------------------------------------------------------------

    private val _strings = MutableStateFlow((prefs.getStringSet("strKeys", emptySet()) ?: emptySet()).associateWith { prefs.getString("str.$it", "") ?: "" })
    val strings: StateFlow<Map<String, String>> = _strings.asStateFlow()
    fun setString(key: String, v: String) {
        _strings.value = _strings.value + (key to v)
        prefs.edit().putStringSet("strKeys", _strings.value.keys).putString("str.$key", v).apply()
    }

    // ---- growth curve -------------------------------------------------------------------------------

    data class GrowthState(val recording: Boolean = false, val startMs: Long = 0L, val points: List<GrowthCurve.Point> = emptyList())
    private val _growth = MutableStateFlow(GrowthState())
    val growth: StateFlow<GrowthState> = _growth.asStateFlow()
    fun growthRecording(on: Boolean, nowMs: Long) { _growth.value = if (on) GrowthState(true, nowMs) else _growth.value.copy(recording = false) }
    private var growthLastMs = 0L

    // ---- exposure control (auto exposure with a dark per step) ---------------------------------------

    /** Integration steps of the auto-exposure, ms. */
    val exposureSteps = doubleArrayOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
    private val _darks = MutableStateFlow<Map<Long, DoubleArray>>(emptyMap())
    /** Dark spectra per integration time (µs), recorded with the input covered. */
    val darks: StateFlow<Map<Long, DoubleArray>> = _darks.asStateFlow()
    fun setDarks(d: Map<Long, DoubleArray>) { _darks.value = d }
    private var lastStepChangeMs = 0L

    /** Dark-corrected counts per second with the matching dark, or null without one. */
    fun perSecond(f: Frame): DoubleArray? {
        val d = _darks.value[f.integrationUs]?.takeIf { it.size == f.meanRaw.size } ?: return null
        val k = 1e6 / f.integrationUs
        return DoubleArray(d.size) { (f.meanRaw[it] - d[it]) * k }
    }

    private fun autoExpose(f: Frame, maxCounts: Int) {
        if (f.timeMs - lastStepChangeMs < 600) return
        val ms = f.integrationUs / 1000.0
        val i = exposureSteps.indexOfFirst { kotlin.math.abs(it - ms) < 1e-6 }
        val target = when {
            i < 0 -> exposureSteps.minByOrNull { kotlin.math.abs(it - ms) }!!
            f.maxRaw > 0.80 * maxCounts && i > 0 -> exposureSteps[i - 1]
            f.maxRaw < 0.15 * maxCounts && i < exposureSteps.size - 1 -> exposureSteps[i + 1]
            else -> return
        }
        lastStepChangeMs = f.timeMs
        setIntegrationMs(target)
    }

    // ---- light exposure, UV dose, plant light (loggers) ------------------------------------------------

    data class LightDoseState(val logging: Boolean = false, val auto: Boolean = true, val lux: Double? = null, val melEdi: Double? = null, val cct: Double? = null,
                              val samples: List<LightExposure.Sample> = emptyList())
    private val _lightDose = MutableStateFlow(LightDoseState())
    val lightDose: StateFlow<LightDoseState> = _lightDose.asStateFlow()
    fun lightDoseLogging(on: Boolean) { _lightDose.value = _lightDose.value.copy(logging = on); lightDoseLastMs = 0L }
    fun lightDoseAuto(on: Boolean) { _lightDose.value = _lightDose.value.copy(auto = on) }
    fun lightDoseLoad(timeMs: Long) {
        val rows = exposure?.read(com.rfsat.pocketspectro.data.ExposureLog.Kind.LIGHT, timeMs) ?: return
        _lightDose.value = _lightDose.value.copy(samples = rows.mapNotNull { r ->
            val t = r["epoch_ms"]?.toLongOrNull(); val l = r["lux"]?.toDoubleOrNull(); val m = r["melanopic_edi_lx"]?.toDoubleOrNull()
            if (t != null && l != null && m != null) LightExposure.Sample(t, l, m) else null
        })
    }
    private var lightDoseLastMs = 0L

    data class UvDoseState(val logging: Boolean = false, val uvi: Double? = null, val sedToday: Double = 0.0, val lastMs: Long = 0L)
    private val _uvDose = MutableStateFlow(UvDoseState())
    val uvDose: StateFlow<UvDoseState> = _uvDose.asStateFlow()
    fun uvDoseLogging(on: Boolean) { _uvDose.value = _uvDose.value.copy(logging = on, lastMs = 0L) }
    fun uvDoseReset() { _uvDose.value = _uvDose.value.copy(sedToday = 0.0) }

    data class PlantLightState(val logging: Boolean = false, val ppfd: Double? = null, val dli: Double = 0.0, val lastMs: Long = 0L, val result: PlantLight.Result? = null)
    private val _plant = MutableStateFlow(PlantLightState())
    val plantLight: StateFlow<PlantLightState> = _plant.asStateFlow()
    fun plantLogging(on: Boolean) { _plant.value = _plant.value.copy(logging = on, lastMs = 0L) }
    fun plantReset() { _plant.value = _plant.value.copy(dli = 0.0) }

    // ---- pulse ------------------------------------------------------------------------------------------

    data class PulseState(val t: List<Double> = emptyList(), val v: List<Double> = emptyList(), val result: Pulse.Result? = null, val recording: Boolean = false, val startMs: Long = 0L)
    private val _pulse = MutableStateFlow(PulseState())
    val pulse: StateFlow<PulseState> = _pulse.asStateFlow()
    private var pulseT0 = 0L
    private var pulseLastLogMs = 0L
    fun pulseRecording(on: Boolean) { _pulse.value = _pulse.value.copy(recording = on); pulseLastLogMs = 0L }
    fun pulseClear() { _pulse.value = PulseState(recording = _pulse.value.recording); pulseT0 = 0L }

    /** Light-tool calibration applied to a frame: response-corrected counts per second, and the lux scale. */
    fun spdPerSecond(f: Frame): DoubleArray {
        val ps = perSecond(f) ?: DoubleArray(f.counts.size) { f.counts[it] * 1e6 / f.integrationUs }
        return LightMetrics.corrected(ps, _light.value.response?.takeIf { it.size == f.counts.size })
    }

    private fun exposureFrame(f: Frame, info: DeviceInfo, sceneTimeMs: Long) {
        val active = _active.value
        // Light exposure
        val ld = _lightDose.value
        if (ld.logging || active == ToolId.LIGHT_DOSE) {
            if (ld.auto && _darks.value.isNotEmpty()) autoExpose(f, info.maxCounts)
            val spd = spdPerSecond(f)
            val lm = LightMetrics.analyse(f.wavelengths, spd)
            val scale = _light.value.luxScale
            val lux = if (scale != null && _light.value.response != null && (f.darkCorrected || perSecond(f) != null)) lm.relLux?.let { it * scale } else null
            val der = LightHealth.analyse(f.wavelengths, spd)?.melanopicDer
            val mel = if (lux != null && der != null) lux * der else null
            var samples = ld.samples
            if (ld.logging && lux != null && mel != null && sceneTimeMs - lightDoseLastMs >= param("dose.interval", 30.0) * 1000 && !f.saturated) {
                lightDoseLastMs = sceneTimeMs
                exposure?.append(com.rfsat.pocketspectro.data.ExposureLog.Kind.LIGHT, sceneTimeMs, listOf(lux, mel, lm.cct, f.integrationUs / 1000.0))
                samples = (samples + LightExposure.Sample(sceneTimeMs, lux, mel)).takeLast(5000)
            }
            _lightDose.value = ld.copy(lux = lux, melEdi = mel, cct = lm.cct, samples = samples)
        }
        // UV dose (UV index scale from the UV index tool)
        val ud = _uvDose.value
        if (ud.logging || active == ToolId.UV_DOSE) {
            val scale = param("uvi.scale", -1.0).takeIf { it > 0 }
            val spd = DoubleArray(f.counts.size) { f.counts[it] * 1e6 / f.integrationUs }
            val uvi = if (f.darkCorrected && scale != null) UvIndex.analyse(f.wavelengths, spd, scale)?.uvi else null
            var st = ud.copy(uvi = uvi)
            if (ud.logging && uvi != null) {
                val dt = if (ud.lastMs > 0) ((sceneTimeMs - ud.lastMs) / 3_600_000.0).coerceIn(0.0, 0.25) else 0.0
                val sed = ud.sedToday + UvDose.sedPerHour(uvi) * dt
                st = st.copy(sedToday = sed, lastMs = sceneTimeMs)
                if ((sceneTimeMs / 30_000) != (ud.lastMs / 30_000)) exposure?.append(com.rfsat.pocketspectro.data.ExposureLog.Kind.UV, sceneTimeMs, listOf(uvi, uvi * 0.025, sed))
            }
            _uvDose.value = st
        }
        // Plant light (PPFD, DLI)
        val pl = _plant.value
        if (pl.logging || active == ToolId.GREENHOUSE) {
            if (pl.logging && _darks.value.isNotEmpty()) autoExpose(f, info.maxCounts)
            val spd = spdPerSecond(f)
            val lm = LightMetrics.analyse(f.wavelengths, spd)
            val scale = _light.value.luxScale
            val ppfd = if (scale != null && _light.value.response != null) lm.relPpfd?.let { it * scale } else null
            val r = PlantLight.analyse(f.wavelengths, spd, ppfd)
            var st = pl.copy(ppfd = ppfd, result = r)
            if (pl.logging && ppfd != null && !f.saturated) {
                val dt = if (pl.lastMs > 0) ((sceneTimeMs - pl.lastMs) / 1000.0).coerceIn(0.0, 900.0) else 0.0
                st = st.copy(dli = pl.dli + ppfd * dt / 1e6, lastMs = sceneTimeMs)
                if ((sceneTimeMs / 60_000) != (pl.lastMs / 60_000)) exposure?.append(com.rfsat.pocketspectro.data.ExposureLog.Kind.PLANT_LIGHT, sceneTimeMs, listOf(ppfd, st.dli, r?.rfr))
            }
            _plant.value = st
        }
        // Pulse: the near-infrared band through the fingertip
        if (active == ToolId.PULSE) {
            if (pulseT0 == 0L) pulseT0 = f.timeMs
            val band = Spectra.bandMean(f.wavelengths, f.meanRaw, 800.0, 900.0).takeIf { Spectra.covers(f.wavelengths, 800.0, 900.0) }
                ?: Spectra.bandMean(f.wavelengths, f.meanRaw, 650.0, 700.0)
            val p = _pulse.value
            val tt = (p.t + (f.timeMs - pulseT0) / 1000.0); val vv = (p.v + band)
            val keep = tt.indexOfFirst { it >= tt.last() - 30.0 }.coerceAtLeast(0)
            val t2 = tt.drop(keep); val v2 = vv.drop(keep)
            val res = Pulse.analyse(t2.toDoubleArray(), v2.toDoubleArray())
            if (p.recording && res.bpm != null && f.timeMs - pulseLastLogMs >= 5000) {
                pulseLastLogMs = f.timeMs
                exposure?.append(com.rfsat.pocketspectro.data.ExposureLog.Kind.PULSE, f.timeMs, listOf(res.bpm, res.perfusionIndex, res.rmssdMs, res.quality))
            }
            _pulse.value = p.copy(t = t2, v = v2, result = res)
        }
    }

    /** Switches off the background work of every tool except [keep] (null: all of them). */
    fun stopAllExcept(keep: ToolId?) {
        if (keep != ToolId.VEGETATION && _veg.value.recording) vegRecording(false)
        if (keep != ToolId.LAMPS && _lamps.value.recording) lampRecording(false)
        if (keep != ToolId.EMISSION && _emission.value.armed) emissionArm(false)
        if (keep != ToolId.KINETICS && _kin.value.recording) kineticsRecording(false)
        if (keep != ToolId.SUN && _sun.value.langley) sunLangley(false)
        if (keep != ToolId.GROWTH && _growth.value.recording) growthRecording(false, 0L)
        if (keep != ToolId.LIGHT_DOSE && _lightDose.value.logging) lightDoseLogging(false)
        if (keep != ToolId.UV_DOSE && _uvDose.value.logging) uvDoseLogging(false)
        if (keep != ToolId.GREENHOUSE && _plant.value.logging) plantLogging(false)
        if (keep != ToolId.PULSE && _pulse.value.recording) pulseRecording(false)
    }

    // ---- per frame ------------------------------------------------------------------------------

    fun onFrame(f: Frame, fix: GeoFix?, info: DeviceInfo, sunTimeMs: Long = f.timeMs, site: Pair<Double, Double> = site(fix), gpsSite: Boolean = fix != null) {
        // A fresh background whenever the Fire tool is opened (the scene in front of the unit may have changed).
        if (_active.value == ToolId.FIRE && lastActive != ToolId.FIRE) { fireBg = null; fireBgFrames.clear(); fireExcess = null; _fireTemp.value = FireTemp() }
        lastActive = _active.value
        if (_active.value == ToolId.SUN) sunFrame(f, sunTimeMs, site, gpsSite)
        if (_active.value == ToolId.FIRE) fireTempFrame(f)
        exposureFrame(f, info, sunTimeMs)
        // Growth curve
        val gr = _growth.value
        if (gr.recording && sunTimeMs - growthLastMs >= param("growth.interval", 60.0) * 1000) {
            f.absorbance()?.let { a -> GrowthCurve.od600(f.wavelengths, a, param("growth.path", 10.0))?.takeIf { it.isFinite() } }?.let { od ->
                growthLastMs = sunTimeMs
                _growth.value = gr.copy(points = (gr.points + GrowthCurve.Point((sunTimeMs - gr.startMs) / 1000.0, od)).takeLast(5000))
            }
        }

        // Lamp survey
        val ls = _lamps.value
        if (ls.recording || _active.value == ToolId.LAMPS) {
            val spd = spd(f)
            val lm = LightMetrics.analyse(f.wavelengths, spd)
            val lr = LampClassifier.classify(f.wavelengths, spd, lm.cct?.takeIf { _light.value.response != null })
            val lh = LightHealth.analyse(f.wavelengths, spd)
            var points = ls.points
            val bright = f.maxRaw > 600 && !f.saturated
            if (ls.recording && lr != null && bright && fix != null && lr.type != LampClassifier.Type.UNKNOWN && f.timeMs - lampLastMs >= ls.intervalSec * 1000) {
                lampLastMs = f.timeMs
                record(ToolEvent(f.timeMs, fix.latitude, fix.longitude, fix.accuracyM?.toDouble(), ToolId.LAMPS.name, lr.type.name, lm.cct ?: Double.NaN,
                    lr.type.colour, listOfNotNull(lm.cct?.let { "CCT %.0f K".format(java.util.Locale.US, it) },
                        lh?.let { "blue %.0f %%; mel-DER %.2f".format(java.util.Locale.US, it.bluePercent, it.melanopicDer) }).joinToString("; "), info.model))
                points++
            }
            _lamps.value = ls.copy(result = lr, light = lm, health = lh, points = points)
        }

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
