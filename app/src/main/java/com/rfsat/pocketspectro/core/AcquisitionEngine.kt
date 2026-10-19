package com.rfsat.pocketspectro.core

import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.labelRes
import com.rfsat.pocketspectro.tools.defaultScene
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rfsat.pocketspectro.data.AppSettings
import com.rfsat.pocketspectro.data.FireEventJournal
import com.rfsat.pocketspectro.data.DetectionContext
import com.rfsat.pocketspectro.data.LocationTracker
import com.rfsat.pocketspectro.data.LoggedSpectrum
import com.rfsat.pocketspectro.data.SessionLogger
import com.rfsat.pocketspectro.data.SettingsStore
import com.rfsat.pocketspectro.proc.DisplayMode
import com.rfsat.pocketspectro.proc.FireConfirmer
import com.rfsat.pocketspectro.proc.FireDetector
import com.rfsat.pocketspectro.proc.Processing
import com.rfsat.pocketspectro.proc.ScanAverager
import com.rfsat.pocketspectro.sim.SimulatedSpectrometer
import com.rfsat.pocketspectro.usb.AndroidUsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

sealed interface Connection {
    data object NoDevice : Connection
    data object AwaitingPermission : Connection
    data object Connecting : Connection
    data class Ready(val info: DeviceInfo) : Connection
    data class Failed(val message: String) : Connection
}

/** A stored dark or reference, with the conditions it was taken under. */
data class StoredSpectrum(val data: DoubleArray, val integrationUs: Long, val scans: Int, val timeMs: Long)

data class Frame(
    val timeMs: Long,
    val wavelengths: DoubleArray,
    val meanRaw: DoubleArray,
    /** Dark-corrected (when a dark exists), nonlinearity-corrected and boxcar-smoothed counts. */
    val counts: DoubleArray,
    /** What the chart shows for [mode]. */
    val display: DoubleArray,
    val mode: DisplayMode,
    /** True when mode needs a reference that is missing (display falls back to counts). */
    val needsReference: Boolean,
    val darkCorrected: Boolean,
    val nlCorrected: Boolean,
    val maxRaw: Int,
    val saturated: Boolean,
    val scans: Int,
    val integrationUs: Long,
    val fire: FireDetector.Result?,
    val fireConfirmed: Boolean,
    /** Stored reference after the same boxcar as [counts] (null when none or a different pixel count). */
    val reference: DoubleArray? = null,
)

data class LogState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val spectra: Int = 0,
    val detections: Int = 0,
    val max: Int = 0,
    val folder: String? = null,
)

/**
 * Owns the spectrometer. All device I/O happens on one dedicated thread, mirroring the
 * Pi daemon's active object: settings changes, dark/reference captures and logging are
 * applied between scans so a long integration never blocks the UI.
 */
class AcquisitionEngine(private val context: Context, val store: SettingsStore, val location: LocationTracker) {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "spectrometer").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loopJob: Job? = null
    private var device: Spectrometer? = null
    private var appliedIntegrationUs = -1L
    private var appliedBinning = -1

    private val _connection = MutableStateFlow<Connection>(Connection.NoDevice)
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _dark = MutableStateFlow<StoredSpectrum?>(null)
    val dark: StateFlow<StoredSpectrum?> = _dark.asStateFlow()
    private val _reference = MutableStateFlow<StoredSpectrum?>(null)
    val reference: StateFlow<StoredSpectrum?> = _reference.asStateFlow()
    private val _log = MutableStateFlow(LogState())
    val log: StateFlow<LogState> = _log.asStateFlow()
    private val _fireArmed = MutableStateFlow(false)
    val fireArmed: StateFlow<Boolean> = _fireArmed.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    private val _simFire = MutableStateFlow(com.rfsat.pocketspectro.sim.SimFireStatus())
    /** Simulator only: the injected-fire state, refreshed with every frame. */
    val simFire: StateFlow<com.rfsat.pocketspectro.sim.SimFireStatus> = _simFire.asStateFlow()
    private val _shutterAvailable = MutableStateFlow(true)
    val shutterAvailable: StateFlow<Boolean> = _shutterAvailable.asStateFlow()

    @Volatile private var singleShot = false
    @Volatile private var darkRequest: Boolean? = null // value = use shutter
    @Volatile private var referenceRequest = false
    @Volatile private var logStopRequest = false
    private var logger: SessionLogger? = null
    private var lastLogMs = 0L
    private val confirmer = FireConfirmer()
    private var lastFireConfirmed = false
    private val alarm = AlarmPlayer(context)

    private val filesRoot: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    val sessionsRoot: File get() = File(filesRoot, "sessions").apply { mkdirs() }
    /** Confirmed fire detections with position, recorded whether or not a log is open. */
    val journal by lazy { FireEventJournal(filesRoot) }
    private val _journalVersion = MutableStateFlow(0)
    /** Increments whenever a detection is added to the journal (the map reloads on it). */
    val journalVersion: StateFlow<Int> = _journalVersion.asStateFlow()

    fun clearJournal() { journal.clear(); _journalVersion.value++ }

    /** Results the tools post with a position (vegetation survey, matches, concentrations...). */
    val toolEvents by lazy { com.rfsat.pocketspectro.data.ToolEventJournal(filesRoot) }
    /** Background work of the tools (survey recording, line alarm, kinetics). */
    val tools by lazy {
        com.rfsat.pocketspectro.tools.ToolHub(context, toolEvents) { on ->
            if (on) alarm.trigger(store.value.alarmSound, store.value.alarmVibrate) else if (!_fireArmed.value) alarm.stop()
        }
    }

    /** Opens a tool (null = the list); with the simulator, shows that tool's demonstration scene. */
    fun openTool(t: com.rfsat.pocketspectro.tools.ToolId?) {
        tools.setActive(t)
        val sim = simulator ?: return
        if (t != null) sim.scene = t.defaultScene(sim.model, System.currentTimeMillis())
    }

    /** Simulator only: what the probe looks at. */
    fun setSimScene(scene: com.rfsat.pocketspectro.sim.SimScene) { simulator?.scene = scene }

    /** Stores a dark with the shutter and then the reference (white tile / blank) in one go. */
    fun captureDarkAndReference() { darkRequest = true; referenceRequest = true }

    // ---- connection ----------------------------------------------------------------------

    fun awaitingPermission() { _connection.value = Connection.AwaitingPermission }

    fun noDevice() {
        if (device == null) _connection.value = Connection.NoDevice
    }

    fun connectUsb(manager: UsbManager, usb: UsbDevice) {
        scope.launch {
            closeDevice()
            _connection.value = Connection.Connecting
            try {
                val model = DeviceCatalog.find(usb.vendorId, usb.productId)
                    ?: throw IOException(context.getString(R.string.conn_unknown_device, "%04X:%04X".format(usb.vendorId, usb.productId)))
                if (!model.usable) throw IOException(context.getString(R.string.conn_recognised_unusable, model.name, context.getString(model.support.labelRes())))
                val io = AndroidUsbTransport.open(manager, usb)
                val dev = try { SpectrometerFactory.open(io, model) } catch (e: IOException) { io.close(); throw e }
                attach(dev)
            } catch (e: Exception) {
                _connection.value = Connection.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun connectSimulator(model: com.rfsat.pocketspectro.sim.SimModel = store.value.simModel) {
        scope.launch {
            closeDevice()
            val sim = SimulatedSpectrometer(model = model)
            tools.active.value?.let { sim.scene = it.defaultScene(model, System.currentTimeMillis()) }
            attach(sim)
        }
    }

    /** Old call sites: NIR or VIS. */
    fun connectSimulator(nir: Boolean) = connectSimulator(if (nir) com.rfsat.pocketspectro.sim.SimModel.NIR else com.rfsat.pocketspectro.sim.SimModel.VIS)

    /** Called on USB detach or when the user switches source. */
    /** Stops logging and releases the spectrometer before the app exits. */
    fun shutdown() {
        scope.launch { closeDevice(); _connection.value = Connection.NoDevice }
    }

    fun disconnect(reason: String? = null) {
        scope.launch {
            closeDevice()
            _connection.value = if (reason != null) Connection.Failed(reason) else Connection.NoDevice
        }
    }

    private fun attach(dev: Spectrometer) {
        device = dev
        (dev as? SimulatedSpectrometer)?.fire?.config = store.value.simFire
        refreshSimFire()
        if (!dev.info.fireCapable) { _fireArmed.value = false; alarm.stop() }
        appliedIntegrationUs = -1
        appliedBinning = -1
        _dark.value = null
        _reference.value = null
        _shutterAvailable.value = true
        confirmer.reset()
        _connection.value = Connection.Ready(dev.info)
        loopJob = scope.launch { loop() }
    }

    private fun closeDevice() {
        loopJob?.cancel()
        loopJob = null
        stopLogInternal()
        _running.value = false
        alarm.stop()
        device?.close()
        device = null
        _frame.value = null
    }

    val simulator: SimulatedSpectrometer? get() = device as? SimulatedSpectrometer

    // ---- user actions --------------------------------------------------------------------

    fun setRunning(on: Boolean) {
        _running.value = on
        if (!on && _log.value.active && !_log.value.paused) pauseLog(true)
    }

    fun singleShot() { singleShot = true }
    fun captureDark(useShutter: Boolean) { darkRequest = useShutter }
    fun captureReference() { referenceRequest = true }
    fun clearDark() { _dark.value = null }
    fun clearReference() { _reference.value = null }

    /** Simulator: starts a fire burst now with the configured duration and strength. */
    fun injectSimFire() {
        val sim = simulator ?: return
        sim.fire.config = store.value.simFire
        sim.fire.inject(System.currentTimeMillis())
        refreshSimFire()
    }

    /** Simulator: holds a flame on (manual) or switches it off. */
    fun holdSimFire(on: Boolean) { simulator?.fire?.hold = on; refreshSimFire() }

    /** Simulator: ends the current burst and any held flame. */
    fun stopSimFire() { simulator?.fire?.stop(); refreshSimFire() }

    private fun refreshSimFire() {
        _simFire.value = simulator?.fire?.status(System.currentTimeMillis()) ?: com.rfsat.pocketspectro.sim.SimFireStatus()
    }

    fun armFire(on: Boolean) {
        _fireArmed.value = on
        confirmer.reset()
        lastFireConfirmed = false
        if (!on) alarm.stop()
    }

    fun startLog() {
        val dev = device ?: return
        scope.launch {
            stopLogInternal()
            val s = store.value
            logger = SessionLogger(sessionsRoot, dev.info, s)
            lastLogMs = 0L
            _log.value = LogState(active = true, max = s.maxAcquisitions, folder = logger?.dir?.name)
            _running.value = true
        }
    }

    fun pauseLog(pause: Boolean) { _log.value = _log.value.copy(paused = pause) }

    fun stopLog() { logStopRequest = true; scope.launch { stopLogInternal() } }

    private fun stopLogInternal() {
        logStopRequest = false
        logger?.close()
        logger = null
        if (_log.value.active) {
            val r = context.resources
            _messages.tryEmit(context.getString(R.string.msg_log_saved,
                r.getQuantityString(R.plurals.count_spectra, _log.value.spectra, _log.value.spectra),
                r.getQuantityString(R.plurals.count_detections, _log.value.detections, _log.value.detections)))
        }
        _log.value = _log.value.copy(active = false, paused = false)
    }

    // ---- acquisition loop ---------------------------------------------------------------

    private suspend fun loop() {
        val ctx = kotlin.coroutines.coroutineContext
        while (ctx.isActive) {
            val dev = device ?: return
            try {
                val s = store.value
                syncDevice(dev, s)
                val darkReq = darkRequest
                when {
                    darkReq != null -> { darkRequest = null; doCaptureDark(dev, s, darkReq) }
                    referenceRequest -> { referenceRequest = false; doCaptureReference(dev, s) }
                    _running.value || singleShot -> { singleShot = false; acquireFrame(dev, s) }
                    else -> delay(40)
                }
            } catch (e: IOException) {
                _messages.tryEmit(context.getString(R.string.msg_spectrometer_error, e.message ?: e.javaClass.simpleName))
                closeDevice()
                _connection.value = Connection.Failed(e.message ?: context.getString(R.string.conn_io_error))
                return
            }
        }
    }

    private fun syncDevice(dev: Spectrometer, s: AppSettings) {
        (dev as? SimulatedSpectrometer)?.fire?.config = s.simFire
        if (s.binningFactor != appliedBinning) {
            dev.setBinningFactor(s.binningFactor)
            appliedBinning = s.binningFactor
            _dark.value = null
            _reference.value = null
            appliedIntegrationUs = -1 // re-send after a binning change
            _connection.value = Connection.Ready(dev.info)
        }
        if (s.integrationUs != appliedIntegrationUs) {
            dev.setIntegrationTimeMicros(s.integrationUs)
            appliedIntegrationUs = s.integrationUs
        }
    }

    private fun averaged(dev: Spectrometer, scans: Int): ScanAverager {
        val avg = ScanAverager(dev.info.pixels)
        repeat(scans.coerceAtLeast(1)) {
            val scan = dev.acquire()
            if (scan.size != dev.info.pixels) throw IOException("Got ${scan.size} pixels, expected ${dev.info.pixels}")
            avg.add(scan)
        }
        return avg
    }

    private fun doCaptureDark(dev: Spectrometer, s: AppSettings, useShutter: Boolean) {
        var shuttered = false
        if (useShutter) {
            shuttered = try { dev.setShutter(false) } catch (_: IOException) { false }
            _shutterAvailable.value = shuttered
            if (shuttered) dev.acquire() // flush a scan integrated with the shutter open
            else _messages.tryEmit(context.getString(R.string.msg_no_shutter))
        }
        try {
            val avg = averaged(dev, s.scansToAverage)
            _dark.value = StoredSpectrum(avg.mean(), s.integrationUs, avg.count, System.currentTimeMillis())
            _messages.tryEmit(context.resources.getQuantityString(if (shuttered) R.plurals.msg_dark_stored_shutter else R.plurals.msg_dark_stored, avg.count, avg.count))
        } finally {
            if (shuttered) { dev.setShutter(true); dev.acquire() }
        }
    }

    private fun doCaptureReference(dev: Spectrometer, s: AppSettings) {
        val dark = _dark.value
        if (dark == null) {
            _messages.tryEmit(context.getString(R.string.msg_dark_first))
            return
        }
        // Simulator: the probe sees the white tile / blank while the reference is taken.
        val sim = dev as? SimulatedSpectrometer
        val shown = sim?.scene
        val blank = shown?.let { com.rfsat.pocketspectro.tools.blankFor(it) }
        if (sim != null && blank != null) sim.scene = blank
        val avg = try { averaged(dev, s.scansToAverage) } finally { if (sim != null && shown != null) sim.scene = shown }
        val nl = dev.info.nonlinearityCoefficients
        val ref = Processing.correctedCounts(avg.mean(), dark.data, nl, s.applyNonlinearity)
        _reference.value = StoredSpectrum(ref, s.integrationUs, avg.count, System.currentTimeMillis())
        _messages.tryEmit(context.getString(if (avg.maxRaw >= dev.info.maxCounts) R.string.msg_ref_saturated else R.string.msg_ref_stored))
    }

    private fun acquireFrame(dev: Spectrometer, s: AppSettings) {
        val info = dev.info
        val avg = averaged(dev, s.scansToAverage)
        val meanRaw = avg.mean()
        val wl = info.wavelengths()
        val dark = _dark.value?.data?.takeIf { it.size == meanRaw.size }
        val nl = info.nonlinearityCoefficients
        val nlApplied = s.applyNonlinearity && nl != null && dark != null
        val corrected = Processing.correctedCounts(meanRaw, dark, nl, s.applyNonlinearity)
        val counts = Processing.boxcar(corrected, s.boxcarWidth)
        val ref = _reference.value?.data?.takeIf { it.size == meanRaw.size }?.let { Processing.boxcar(it, s.boxcarWidth) }
        val needsRef = s.displayMode != DisplayMode.COUNTS && ref == null
        val display = when {
            s.displayMode == DisplayMode.ABSORBANCE && ref != null -> Processing.absorbance(counts, ref)
            s.displayMode == DisplayMode.TRANSMITTANCE && ref != null -> Processing.transmittance(counts, ref)
            else -> counts
        }

        // Fire detection (Pi: raw single scan, so default input is the raw mean). NIR units only.
        val fireCounts = if (info.fireCapable) {
            Processing.boxcar(if (s.fireUseDarkCorrected && dark != null) corrected else meanRaw, s.boxcarWidth)
        } else null
        val fire = fireCounts?.let { FireDetector.analyse(wl, it, s.fire) }
        confirmer.required = s.fire.confirmFrames
        val confirmed = fire != null && confirmer.update(fire.fire)
        if (_fireArmed.value && fire != null) {
            if (confirmed) alarm.trigger(s.alarmSound, s.alarmVibrate) else if (lastFireConfirmed) alarm.stop()
        }
        // Detections are journalled while monitoring is on (Monitoring ON on the Fire tool).
        if (confirmed && fire != null && _fireArmed.value) {
            val fix = if (store.value.gpsTagging) location.current() else null
            try {
                val ctx = DetectionContext(info.model, info.serial, s.integrationUs, avg.count, s.fire.thresholdPercent,
                    s.fire.confirmFrames, wl, fireCounts ?: meanRaw)
                if (journal.record(System.currentTimeMillis(), fix, fire, logger?.dir?.name, risingEdge = !lastFireConfirmed, ctx = ctx) != null) {
                    _journalVersion.value++
                }
            } catch (e: IOException) {
                _messages.tryEmit(context.getString(R.string.msg_record_failed, e.message ?: e.javaClass.simpleName))
            }
        }
        lastFireConfirmed = confirmed

        val now = System.currentTimeMillis()
        if (dev is SimulatedSpectrometer) _simFire.value = dev.fire.status(now)
        _frame.value = Frame(
            timeMs = now, wavelengths = wl, meanRaw = meanRaw, counts = counts, display = display,
            mode = s.displayMode, needsReference = needsRef, darkCorrected = dark != null, nlCorrected = nlApplied,
            maxRaw = avg.maxRaw, saturated = avg.maxRaw >= info.maxCounts, scans = avg.count,
            integrationUs = s.integrationUs, fire = fire, fireConfirmed = confirmed, reference = ref,
        )
        _frame.value?.let { fr -> tools.onFrame(fr, if (s.gpsTagging) location.current() else null, info) }

        logIfDue(now, s, wl, counts, avg.count, dark != null, nlApplied, fire, confirmed)
    }

    private fun logIfDue(
        now: Long, s: AppSettings, wl: DoubleArray, counts: DoubleArray, scans: Int,
        darkCorrected: Boolean, nlApplied: Boolean, fire: FireDetector.Result?, confirmed: Boolean,
    ) {
        val lg = logger ?: return
        val st = _log.value
        if (!st.active || st.paused || logStopRequest) return
        val intervalMs = (s.sequenceIntervalSec * 1000.0).toLong()
        if (lastLogMs != 0L && now - lastLogMs < intervalMs) return
        lastLogMs = now
        val fix = if (s.gpsTagging) location.current() else null
        try {
            lg.writeSpectrum(LoggedSpectrum(now, s.integrationUs, scans, s.boxcarWidth, darkCorrected, nlApplied, wl, counts, fix))
            if (fire != null) lg.writeFire(now, fire, confirmed, fix)
        } catch (e: IOException) {
            _messages.tryEmit(context.getString(R.string.msg_log_failed, e.message ?: e.javaClass.simpleName))
            stopLogInternal()
            return
        }
        _log.value = st.copy(spectra = lg.spectraWritten, detections = lg.detectionsWritten)
        if (st.max > 0 && lg.spectraWritten >= st.max) stopLogInternal()
    }

    /** Applies the settings the Pi's Detect page used, as the daemon actually ran them. */
    fun applyRecommendedFirePreset() {
        store.update {
            it.copy(
                integrationMs = 200.0, scansToAverage = 1, boxcarWidth = 0, fireUseDarkCorrected = false,
                fire = FireDetector.Config(),
            )
        }
    }

    fun applyPiFirePreset() {
        store.update {
            it.copy(
                integrationMs = 200.0,
                scansToAverage = 1,
                boxcarWidth = 0,
                fireUseDarkCorrected = false,
                fire = it.fire.copy(
                    thresholdPercent = 10.0, confirmFrames = 1,
                    windowMode = FireDetector.WindowMode.PIXEL,
                    pixelWindow1 = 278..282, pixelWindow2 = 287..291,
                    algorithm = FireDetector.Algorithm.PI_IDENTICAL,
                    baseline = FireDetector.Baseline.GLOBAL_MEAN,
                ),
            )
        }
    }
}
