package com.rfsat.pocketspectro

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.proc.DisplayMode
import com.rfsat.pocketspectro.ui.AppRoot
import com.rfsat.pocketspectro.ui.SpectroTheme
import com.rfsat.pocketspectro.ui.Tab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens, fed by the simulated STS-NIR, at Galaxy S24 size.
 * Run with:  ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
 * Output:    app/build/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = ScreenshotTest.PHONE)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as SpectroApp
    private val engine: AcquisitionEngine get() = app.engine

    private fun prepare(simulator: Boolean = true, gps: Boolean = true, nir: Boolean = true,
                        model: com.rfsat.pocketspectro.sim.SimModel = if (nir) com.rfsat.pocketspectro.sim.SimModel.NIR else com.rfsat.pocketspectro.sim.SimModel.VIS) {
        engine.store.update {
            it.copy(useSimulator = simulator, gpsTagging = gps, integrationMs = 100.0, scansToAverage = 1, boxcarWidth = 0,
                displayMode = DisplayMode.COUNTS, simModel = model)
        }
        if (gps) {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            engine.location.start()
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 37.93021; longitude = 23.75543; altitude = 182.0; accuracy = 4f; speed = 0f
                time = System.currentTimeMillis()
            }
            shadowOf(lm).simulateLocation(loc)
        }
        if (simulator) {
            engine.connectSimulator(model)
            val t0 = System.currentTimeMillis()
            while (engine.connection.value !is com.rfsat.pocketspectro.core.Connection.Ready && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(20)
        }
    }

    private fun show(tab: Tab, exitDialog: Boolean = false, sessions: Boolean = false, mapSelect: Int? = null,
                     store: Boolean = false, tool: com.rfsat.pocketspectro.tools.ToolId? = null) {
        com.rfsat.pocketspectro.ui.StoreShots.hideVersion = store
        compose.setContent {
            SpectroTheme {
                AppRoot(engine, {}, {}, initialTab = tab.ordinal, showExitDialog = exitDialog,
                    initialLogSessions = sessions, initialMapSelection = mapSelect, initialTool = tool)
            }
        }
    }

    /** A short survey track with three journalled detections, east of Athens. */
    private fun seedMapData(withSimulated: Boolean = false) {
        val dir = java.io.File(engine.sessionsRoot, "session_20261017_101500").apply { mkdirs() }
        val sb = StringBuilder("time_utc,epoch_ms,latitude,longitude\n")
        for (i in 0..60) {
            val lat = 37.9520 + i * 0.00042 + 0.0006 * kotlin.math.sin(i / 6.0)
            val lon = 23.7905 + i * 0.00065
            sb.append("t,${1792233300000L + i * 5000},$lat,$lon\n")
        }
        java.io.File(dir, "fire.csv").writeText(sb.toString())
        val j = engine.journal.file.apply { parentFile?.mkdirs() }
        java.io.File(j.parentFile, "spectra").mkdirs()
        val wl = com.rfsat.pocketspectro.proc.Calibration.binnedWavelengths(doubleArrayOf(636.0, 0.473, -2.0e-5, 0.0), 1024, 0)
        val rnd = java.util.Random(7)
        fun spec(k: Double) = DoubleArray(wl.size) { i ->
            val l = wl[i]
            1480 + 6400 * kotlin.math.exp(-((l - 860) / 210).let { it * it }) * (0.7 / (1 + kotlin.math.exp(-(l - 715) / 12)) + 0.3) +
                k * (kotlin.math.exp(-((l - 766.5) / 0.75).let { it * it }) + 0.62 * kotlin.math.exp(-((l - 769.9) / 0.75).let { it * it })) +
                rnd.nextGaussian() * 25
        }
        val cfg = com.rfsat.pocketspectro.proc.FireDetector.Config()
        val rows = StringBuilder(com.rfsat.pocketspectro.data.FireEventJournal.HEADER + "\n")
        val pts = listOf(Triple(37.96220, 23.80720, 1500.0), Triple(37.96690, 23.81500, 2600.0), Triple(37.96810, 23.81810, 900.0))
        pts.forEachIndexed { n, (lat, lon, k) ->
            val t = 1792232225000L + n * 95_000L
            val y = spec(k)
            val r = com.rfsat.pocketspectro.proc.FireDetector.analyse(wl, y, cfg)
            java.io.File(j.parentFile, "spectra/$t.csv").writeText("wavelength_nm,counts\n" +
                wl.indices.joinToString("\n") { "%.4f,%.2f".format(java.util.Locale.US, wl[it], y[it]) })
            val c = r.confidence
            rows.append(listOf(com.rfsat.pocketspectro.data.SessionLogger.iso(t), t, lat, lon, 412 + 25 * n, 4 + n, "3.6", "48",
                "%.3f".format(java.util.Locale.US, r.window1.wavelengthNm), "%.1f".format(java.util.Locale.US, r.window1.intensity),
                "%.1f".format(java.util.Locale.US, r.window1.baseline), "%.2f".format(java.util.Locale.US, r.window1.percentAboveFloor),
                "%.3f".format(java.util.Locale.US, r.window2.wavelengthNm), "%.1f".format(java.util.Locale.US, r.window2.intensity),
                "%.1f".format(java.util.Locale.US, r.window2.baseline), "%.2f".format(java.util.Locale.US, r.window2.percentAboveFloor),
                "LOCAL_CONTINUUM", "%.1f".format(java.util.Locale.US, c.score), "%.2f".format(java.util.Locale.US, c.snr1),
                "%.2f".format(java.util.Locale.US, c.snr2), "%.3f".format(java.util.Locale.US, c.ratio),
                if (withSimulated && n == 2) "STS-NIR (simulated)" else "STS-NIR", "S08245", 200000, 1, "10.0", 2, if (n < 2) "session_20261017_101500" else "", "spectra/$t.csv",
            ).joinToString(",")).append("\n")
        }
        j.writeText(rows.toString())
    }

    private fun waitFor(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        compose.waitUntil(timeoutMs) { cond() }
        compose.waitForIdle()
    }

    /** Waits for frames taken after now, so state changes are visible. */
    private fun freshFrames(n: Int = 2) {
        val t0 = System.currentTimeMillis()
        repeat(n) {
            val after = engine.frame.value?.timeMs ?: 0L
            waitFor { (engine.frame.value?.timeMs ?: 0L) > maxOf(after, t0) }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun s00_home() {
        prepare()
        engine.setRunning(true)
        show(Tab.HOME)
        freshFrames(1)
        shot("00_home")
    }

    @Test fun s00b_exitDialog() {
        prepare()
        show(Tab.HOME, exitDialog = true)
        shot("00b_exit_confirm")
    }

    @Test @Config(qualifiers = TALL) fun t00_homeFull() {
        prepare()
        show(Tab.HOME)
        shot("00c_home_full_length")
    }

    @Test fun s00d_homeVisUnit() {
        prepare(nir = false)
        engine.setRunning(true)
        show(Tab.HOME)
        freshFrames(1)
        shot("00d_home_sts_vis_no_fire_tab")
    }

    @Test fun s00e_homeNoDevice() {
        prepare(simulator = false)
        show(Tab.HOME)
        shot("00e_home_no_device")
    }

    @Test fun s06b_map() {
        prepare()
        seedMapData()
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("06b_map_detections")
    }

    @Test fun s06c_mapEvent() {
        prepare()
        seedMapData()
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP, mapSelect = 1)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("06c_map_detection_details")
    }

    @Test fun s06e_mapSatellite() {
        prepare()
        seedMapData()
        engine.store.update { it.copy(mapLayer = com.rfsat.pocketspectro.ui.map.MapLayer.SATELLITE) }
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("06d_map_satellite")
    }

    @Test fun s06f_mapTerrain() {
        prepare()
        seedMapData()
        engine.store.update { it.copy(mapLayer = com.rfsat.pocketspectro.ui.map.MapLayer.TERRAIN) }
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("06e_map_terrain")
    }

    @Test fun s06g_mapWithSimulated() {
        prepare()
        seedMapData(withSimulated = true)
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("06f_map_with_simulated")
    }

    @Test fun s06d_sessionsWithJournal() {
        prepare()
        seedMapData()
        show(Tab.LOG, sessions = true)
        shot("07b_log_sessions_journal")
    }

    @Test fun s01_noDevice() {
        prepare(simulator = false)
        show(Tab.LIVE)
        shot("01_live_no_device")
    }

    @Test fun s02_liveCounts() {
        prepare()
        engine.setRunning(true)
        show(Tab.LIVE)
        freshFrames(3)
        shot("02_live_counts")
    }

    @Test fun s03_liveAbsorbance() {
        prepare()
        engine.store.update { it.copy(scansToAverage = 4, boxcarWidth = 2) }
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.captureDark(useShutter = true)
        waitFor { engine.dark.value != null }
        engine.captureReference()
        waitFor { engine.reference.value != null }
        engine.simulator!!.sampleOn = true
        engine.store.update { it.copy(displayMode = DisplayMode.ABSORBANCE) }
        engine.setRunning(true)
        show(Tab.LIVE)
        freshFrames(2)
        shot("03_live_absorbance")
    }

    @Test fun s04_fireClear() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        freshFrames(2)
        shot("04_fire_clear")
    }

    @Test fun s04b_firePiPresetOnContinuum() {
        prepare()
        engine.applyPiFirePreset()
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        freshFrames(2)
        shot("04b_fire_pi_preset_no_flame")
    }

    @Test fun s05_fireDetected() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.simulator!!.fireOn = true
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        freshFrames(1)
        shot("05_fire_detected")
    }

    @Test fun s06_logActive() {
        prepare()
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.store.update { it.copy(sequenceIntervalSec = 0.5, maxAcquisitions = 500) }
        engine.startLog()
        show(Tab.LOG)
        waitFor { engine.log.value.spectra >= 4 && engine.location.fix.value != null }
        shot("06_log_active")
    }

    @Test fun s07_results() {
        prepare()
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.store.update { it.copy(sequenceIntervalSec = 0.0, maxAcquisitions = 6) }
        engine.simulator!!.fireOn = true
        engine.startLog()
        waitFor { !engine.log.value.active && engine.log.value.spectra >= 6 }
        show(Tab.LOG, sessions = true)
        compose.waitForIdle()
        compose.onAllNodes(hasText("files", substring = true))[0].performClick()
        shot("07_results")
    }

    @Test fun s08_setup() {
        prepare()
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        show(Tab.SETUP)
        shot("08_setup")
    }

    @Test @Config(qualifiers = "+night") fun s09_liveDark() {
        prepare()
        engine.setRunning(true)
        show(Tab.LIVE)
        freshFrames(3)
        shot("09_live_counts_dark")
    }

    @Test @Config(qualifiers = "+night") fun s10_fireDetectedDark() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.simulator!!.fireOn = true
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        shot("10_fire_detected_dark")
    }

    // Full-length renders of the scrolling screens (whole content on one image).

    @Test @Config(qualifiers = TALL) fun t01_liveFull() {
        prepare()
        engine.setRunning(true)
        show(Tab.LIVE)
        freshFrames(2)
        shot("11_live_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t02_fireFull() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.simulator!!.fireOn = true
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        shot("12_fire_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t03_logFull() {
        prepare()
        show(Tab.LOG)
        shot("13_log_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t05_mapDetailFull() {
        prepare()
        seedMapData()
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP, mapSelect = 1)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("15_map_detection_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t04_setupFull() {
        prepare()
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        show(Tab.SETUP)
        shot("14_setup_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t06_fireSimManualFull() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false,
            simFire = com.rfsat.pocketspectro.sim.SimFireConfig(mode = com.rfsat.pocketspectro.sim.SimFireMode.MANUAL, durationSec = 20.0)) }
        engine.armFire(true)
        engine.setRunning(true)
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.injectSimFire()
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        freshFrames(1)
        shot("16_fire_sim_manual_full_length")
    }

    @Test @Config(qualifiers = TALL) fun t07_fireSimRandomFull() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false,
            simFire = com.rfsat.pocketspectro.sim.SimFireConfig(mode = com.rfsat.pocketspectro.sim.SimFireMode.RANDOM, meanIntervalSec = 120.0)) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        freshFrames(2)
        shot("17_fire_sim_random_full_length")
    }

    // ---- Tools ----------------------------------------------------------------------------------

    private fun tool(t: com.rfsat.pocketspectro.tools.ToolId) { engine.openTool(t) }

    private fun darkAndReference() {
        engine.reference.value?.let { engine.clearReference() }
        engine.captureDarkAndReference()
        waitFor(20_000) { engine.reference.value != null }
    }

    @Test fun u00_toolsListNir() {
        prepare()
        show(Tab.TOOLS)
        shot("20_tools_list_nir")
    }

    @Test @Config(qualifiers = TALL) fun u01_toolsListVis() {
        prepare(model = com.rfsat.pocketspectro.sim.SimModel.VIS)
        show(Tab.TOOLS)
        shot("21_tools_list_vis_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u02_emission() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.EMISSION)
        engine.tools.emissionArm(true)
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.setRunning(true)
        show(Tab.TOOLS)
        freshFrames(6)
        shot("22_tool_emission_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u03_vegetation() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.VEGETATION)
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Reflect(com.rfsat.pocketspectro.sim.SimTarget.LEAF_HEALTHY, com.rfsat.pocketspectro.sim.SimLight.DAYLIGHT))
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        darkAndReference()
        engine.tools.vegInterval(1.0)
        engine.tools.vegRecording(true)
        engine.setRunning(true)
        show(Tab.TOOLS)
        waitFor { engine.tools.vegetation.value.points >= 2 }
        shot("23_tool_vegetation_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u04_match() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.MATCH)
        val sim = engine.simulator!!
        val wl = sim.info.wavelengths()
        val root = engine.sessionsRoot.parentFile!!
        for (m in listOf(com.rfsat.pocketspectro.sim.SimTarget.PAPER, com.rfsat.pocketspectro.sim.SimTarget.COTTON,
                com.rfsat.pocketspectro.sim.SimTarget.POLYESTER, com.rfsat.pocketspectro.sim.SimTarget.POLYETHYLENE, com.rfsat.pocketspectro.sim.SimTarget.NYLON)) {
            com.rfsat.pocketspectro.tools.SpectralLibrary.save(root, m.name.lowercase().replaceFirstChar { it.uppercase() },
                com.rfsat.pocketspectro.tools.SpectralLibrary.Quantity.REFLECTANCE, sim.info.model, wl,
                DoubleArray(wl.size) { com.rfsat.pocketspectro.sim.SimOptics.reflectance(wl[it], m, 0L) })
        }
        engine.tools.libraryChanged()
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Reflect(com.rfsat.pocketspectro.sim.SimTarget.POLYESTER))
        darkAndReference()
        engine.setRunning(true)
        show(Tab.TOOLS)
        freshFrames(3)
        shot("24_tool_match_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u05_concentration() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.CONCENTRATION)
        darkAndReference()
        engine.setRunning(true)
        for (c in listOf(1.0, 2.0, 4.0, 8.0)) {
            engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Cuvette(c))
            freshFrames(2)
            val f = engine.frame.value!!
            val a = com.rfsat.pocketspectro.tools.BeerLambert.absorbance(f.wavelengths, f.counts, f.reference!!, 664.0, 2.0)
            engine.tools.concUpdate { it.copy(standards = it.standards + com.rfsat.pocketspectro.tools.BeerLambert.Standard(c, a)) }
        }
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Cuvette(5.3))
        show(Tab.TOOLS)
        freshFrames(3)
        shot("25_tool_concentration_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u06_colour() {
        prepare(model = com.rfsat.pocketspectro.sim.SimModel.VIS)
        tool(com.rfsat.pocketspectro.tools.ToolId.COLOUR)
        darkAndReference()
        engine.setRunning(true)
        freshFrames(2)
        val f = engine.frame.value!!
        val lab = com.rfsat.pocketspectro.tools.Colorimetry.lab(com.rfsat.pocketspectro.tools.Colorimetry.reflectanceXYZ(f.wavelengths, f.counts.indices.map { f.counts[it] / f.reference!![it] }.toDoubleArray()))
        engine.tools.colourUpdate { it.copy(standard = lab) }
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Reflect(com.rfsat.pocketspectro.sim.SimTarget.TILE_ORANGE))
        show(Tab.TOOLS)
        freshFrames(3)
        shot("26_tool_colour_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u07_light() {
        prepare(model = com.rfsat.pocketspectro.sim.SimModel.VIS)
        tool(com.rfsat.pocketspectro.tools.ToolId.LIGHT)
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.captureDark(useShutter = true)
        waitFor { engine.dark.value != null }
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Emit(com.rfsat.pocketspectro.sim.SimEmitter.LAMP_2856K))
        engine.setRunning(true)
        freshFrames(2)
        val f = engine.frame.value!!
        engine.tools.lightUpdate { it.copy(response = com.rfsat.pocketspectro.tools.LightMetrics.responseFromLamp(f.wavelengths, f.counts, 2856.0)) }
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Emit(com.rfsat.pocketspectro.sim.SimEmitter.LED_WHITE_COOL))
        show(Tab.TOOLS)
        freshFrames(3)
        shot("27_tool_light_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u08_kinetics() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.KINETICS)
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Cuvette(8.0))
        darkAndReference()
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Cuvette(8.0, com.rfsat.pocketspectro.sim.SimScene.Reaction(8.0, 0.08, System.currentTimeMillis())))
        engine.setRunning(true)
        engine.tools.kineticsRecording(true)
        show(Tab.TOOLS)
        waitFor(40_000) { engine.tools.kinetics.value.points.size >= 60 }
        shot("28_tool_kinetics_full_length")
    }

    @Test @Config(qualifiers = TALL) fun u10_colourPartialNir() {
        prepare()
        tool(com.rfsat.pocketspectro.tools.ToolId.COLOUR)
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Reflect(com.rfsat.pocketspectro.sim.SimTarget.TOMATO_UNRIPE))
        darkAndReference()
        engine.setRunning(true)
        freshFrames(2)
        val f = engine.frame.value!!
        val lab = com.rfsat.pocketspectro.tools.Colorimetry.lab(com.rfsat.pocketspectro.tools.Colorimetry.reflectanceXYZ(f.wavelengths, f.counts.indices.map { f.counts[it] / f.reference!![it] }.toDoubleArray()))
        engine.tools.colourUpdate { it.copy(standard = lab) }
        engine.setSimScene(com.rfsat.pocketspectro.sim.SimScene.Reflect(com.rfsat.pocketspectro.sim.SimTarget.TOMATO_RIPE))
        show(Tab.TOOLS)
        freshFrames(3)
        shot("26b_tool_colour_partial_nir_full_length")
    }

    @Test fun u09_mapWithToolResults() {
        prepare()
        seedMapData()
        val now = System.currentTimeMillis()
        for (i in 0..24) {
            val ndvi = 0.15 + 0.7 * (0.5 + 0.5 * kotlin.math.sin(i / 3.0))
            engine.tools.record(com.rfsat.pocketspectro.data.ToolEvent(now + i * 5000, 37.9545 + i * 0.00042, 23.7935 + i * 0.00068, 4.0,
                "VEGETATION", "NDVI %.2f".format(java.util.Locale.US, ndvi), ndvi, com.rfsat.pocketspectro.tools.Vegetation.colour(ndvi), "", "STS-NIR"))
        }
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        shot("29_map_with_tool_results")
    }

    // ---- Google Play listing set (9:16, no version shown) -> build/screenshots/play/ ----

    private fun storeShot(name: String) {
        compose.waitForIdle()
        val f = java.io.File("build/screenshots/play/$name.png")
        compose.onRoot().captureRoboImage(f.path)
        // Play wants 24-bit PNG without alpha; trim the 1 px rounding to exactly 1080 x 1920.
        val img = javax.imageio.ImageIO.read(f)
        val out = java.awt.image.BufferedImage(minOf(1080, img.width), minOf(1920, img.height), java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        javax.imageio.ImageIO.write(out, "png", f)
    }

    @Test @Config(qualifiers = PLAY) fun p1_live() {
        prepare()
        engine.store.update { it.copy(scansToAverage = 2) }
        engine.setRunning(true)
        show(Tab.LIVE, store = true)
        freshFrames(3)
        storeShot("1_live_spectrum")
    }

    @Test @Config(qualifiers = PLAY) fun p2_fire() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.simulator!!.fireOn = true
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, store = true, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        freshFrames(1)
        storeShot("2_fire_detection")
    }

    @Test @Config(qualifiers = PLAY) fun p3_map() {
        prepare()
        seedMapData()
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP, store = true)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        storeShot("3_map_detections")
    }

    @Test @Config(qualifiers = PLAY) fun p4_mapDetail() {
        prepare()
        seedMapData()
        com.rfsat.pocketspectro.ui.PreviewTiles.loaded = 0
        show(Tab.MAP, mapSelect = 1, store = true)
        waitFor(40_000) { com.rfsat.pocketspectro.ui.PreviewTiles.loaded > 0 }
        storeShot("4_detection_details")
    }

    @Test @Config(qualifiers = PLAY) fun p5_absorbance() {
        prepare()
        engine.store.update { it.copy(scansToAverage = 4, boxcarWidth = 2) }
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.captureDark(useShutter = true)
        waitFor { engine.dark.value != null }
        engine.captureReference()
        waitFor { engine.reference.value != null }
        engine.simulator!!.sampleOn = true
        engine.store.update { it.copy(displayMode = DisplayMode.ABSORBANCE) }
        engine.setRunning(true)
        show(Tab.LIVE, store = true)
        freshFrames(2)
        storeShot("5_absorbance")
    }

    @Test @Config(qualifiers = PLAY) fun p6_log() {
        prepare()
        waitFor { engine.connection.value is com.rfsat.pocketspectro.core.Connection.Ready }
        engine.store.update { it.copy(sequenceIntervalSec = 0.5, maxAcquisitions = 500) }
        engine.startLog()
        show(Tab.LOG, store = true)
        waitFor { engine.log.value.spectra >= 4 && engine.location.fix.value != null }
        storeShot("6_logging_gps")
    }

    @Test @Config(qualifiers = PLAY) fun p7_home() {
        prepare()
        engine.setRunning(true)
        show(Tab.HOME, store = true)
        freshFrames(1)
        storeShot("7_home")
    }

    @Test @Config(qualifiers = PLAY_NIGHT) fun p8_fireDark() {
        prepare()
        engine.applyRecommendedFirePreset()
        engine.simulator!!.fireOn = true
        engine.store.update { it.copy(alarmSound = false, alarmVibrate = false) }
        engine.armFire(true)
        engine.setRunning(true)
        show(Tab.TOOLS, store = true, tool = com.rfsat.pocketspectro.tools.ToolId.FIRE)
        waitFor { engine.frame.value?.fireConfirmed == true }
        freshFrames(1)
        storeShot("8_fire_dark_mode")
    }

    companion object {
        /** Galaxy S24: 1080 x 2340 px at 420 dpi. */
        const val PHONE = "w412dp-h892dp-420dpi"
        /** Google Play: 9:16 (1080 x 1920 after a 1 px trim), Galaxy S24 width and density. */
        const val PLAY = "w412dp-h732dp-420dpi"
        const val PLAY_NIGHT = "w412dp-h732dp-night-420dpi"
        const val TALL = "w412dp-h2200dp-420dpi"
    }
}
