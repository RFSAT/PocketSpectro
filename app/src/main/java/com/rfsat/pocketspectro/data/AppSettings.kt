package com.rfsat.pocketspectro.data

import android.content.Context
import android.content.SharedPreferences
import com.rfsat.pocketspectro.proc.DisplayMode
import com.rfsat.pocketspectro.proc.FireDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SaveMode(val label: String) {
    SINGLE("One CSV per session (one row per spectrum)"),
    MULTI("One file per spectrum (Pi format)"),
}

data class AppSettings(
    // Acquisition
    val integrationMs: Double = 100.0,
    val scansToAverage: Int = 1,
    val boxcarWidth: Int = 0,
    val binningFactor: Int = 0,
    val applyNonlinearity: Boolean = true,
    val displayMode: DisplayMode = DisplayMode.COUNTS,
    val useSimulator: Boolean = false,
    // Logging / sequence (Pi "Acquisition Control")
    val sequenceIntervalSec: Double = 5.0, // 0 = every spectrum
    val maxAcquisitions: Int = 0, // 0 = unlimited
    val saveMode: SaveMode = SaveMode.SINGLE,
    val filePrefix: String = "nir",
    val gpsTagging: Boolean = true,
    // Fire detection
    val fire: FireDetector.Config = FireDetector.Config(),
    val fireUseDarkCorrected: Boolean = false,
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,
    val keepScreenOn: Boolean = true,
    val mapLayer: com.rfsat.pocketspectro.ui.map.MapLayer = com.rfsat.pocketspectro.ui.map.MapLayer.STREET,
) {
    val integrationUs: Long get() = (integrationMs * 1000.0).toLong().coerceIn(10L, 85_000_000L)
}

/** SharedPreferences-backed settings exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pocketspectro", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val f = d.fire
        return AppSettings(
            integrationMs = prefs.getFloat("integrationMs", d.integrationMs.toFloat()).toDouble(),
            scansToAverage = prefs.getInt("average", d.scansToAverage),
            boxcarWidth = prefs.getInt("boxcar", d.boxcarWidth),
            binningFactor = prefs.getInt("binning", d.binningFactor),
            applyNonlinearity = prefs.getBoolean("nl", d.applyNonlinearity),
            displayMode = enumOr(prefs.getString("displayMode", null), d.displayMode),
            useSimulator = prefs.getBoolean("sim", d.useSimulator),
            sequenceIntervalSec = prefs.getFloat("seqInterval", d.sequenceIntervalSec.toFloat()).toDouble(),
            maxAcquisitions = prefs.getInt("maxAcq", d.maxAcquisitions),
            saveMode = enumOr(prefs.getString("saveMode", null), d.saveMode),
            filePrefix = prefs.getString("prefix", d.filePrefix) ?: d.filePrefix,
            gpsTagging = prefs.getBoolean("gps", d.gpsTagging),
            fire = FireDetector.Config(
                windowMode = enumOr(prefs.getString("fireWinMode", null), f.windowMode),
                centre1Nm = prefs.getFloat("fireC1", f.centre1Nm.toFloat()).toDouble(),
                centre2Nm = prefs.getFloat("fireC2", f.centre2Nm.toFloat()).toDouble(),
                halfWidthNm = prefs.getFloat("fireHw", f.halfWidthNm.toFloat()).toDouble(),
                pixelWindow1 = prefs.getInt("fireP1a", f.pixelWindow1.first)..prefs.getInt("fireP1b", f.pixelWindow1.last),
                pixelWindow2 = prefs.getInt("fireP2a", f.pixelWindow2.first)..prefs.getInt("fireP2b", f.pixelWindow2.last),
                thresholdPercent = prefs.getFloat("fireThr", f.thresholdPercent.toFloat()).toDouble(),
                algorithm = enumOr(prefs.getString("fireAlg", null), f.algorithm),
                baseline = enumOr(prefs.getString("fireBase", null), f.baseline),
                confirmFrames = prefs.getInt("fireConfirm", f.confirmFrames),
            ),
            fireUseDarkCorrected = prefs.getBoolean("fireDark", d.fireUseDarkCorrected),
            alarmSound = prefs.getBoolean("alarmSound", d.alarmSound),
            alarmVibrate = prefs.getBoolean("alarmVibe", d.alarmVibrate),
            keepScreenOn = prefs.getBoolean("screenOn", d.keepScreenOn),
            mapLayer = enumOr(prefs.getString("mapLayer", null), d.mapLayer),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putFloat("integrationMs", s.integrationMs.toFloat())
            .putInt("average", s.scansToAverage)
            .putInt("boxcar", s.boxcarWidth)
            .putInt("binning", s.binningFactor)
            .putBoolean("nl", s.applyNonlinearity)
            .putString("displayMode", s.displayMode.name)
            .putBoolean("sim", s.useSimulator)
            .putFloat("seqInterval", s.sequenceIntervalSec.toFloat())
            .putInt("maxAcq", s.maxAcquisitions)
            .putString("saveMode", s.saveMode.name)
            .putString("prefix", s.filePrefix)
            .putBoolean("gps", s.gpsTagging)
            .putString("fireWinMode", s.fire.windowMode.name)
            .putFloat("fireC1", s.fire.centre1Nm.toFloat())
            .putFloat("fireC2", s.fire.centre2Nm.toFloat())
            .putFloat("fireHw", s.fire.halfWidthNm.toFloat())
            .putInt("fireP1a", s.fire.pixelWindow1.first).putInt("fireP1b", s.fire.pixelWindow1.last)
            .putInt("fireP2a", s.fire.pixelWindow2.first).putInt("fireP2b", s.fire.pixelWindow2.last)
            .putFloat("fireThr", s.fire.thresholdPercent.toFloat())
            .putString("fireAlg", s.fire.algorithm.name)
            .putString("fireBase", s.fire.baseline.name)
            .putInt("fireConfirm", s.fire.confirmFrames)
            .putBoolean("fireDark", s.fireUseDarkCorrected)
            .putBoolean("alarmSound", s.alarmSound)
            .putBoolean("alarmVibe", s.alarmVibrate)
            .putBoolean("screenOn", s.keepScreenOn)
            .putString("mapLayer", s.mapLayer.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
