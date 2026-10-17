package com.rfsat.pocketspectro.ui

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rfsat.pocketspectro.SpectroApp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.Connection
import com.rfsat.pocketspectro.core.DeviceCatalog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val engine: AcquisitionEngine get() = (application as SpectroApp).engine
    private val usbManager: UsbManager get() = getSystemService(Context.USB_SERVICE) as UsbManager

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) engine.location.start()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        engine.connectUsb(usbManager, device)
                    } else {
                        engine.disconnect("USB permission refused")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && isSts(device) && !engine.store.value.useSimulator) engine.disconnect("Spectrometer unplugged")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && isSts(device)) connectSource()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Keep the screen on while acquiring (hand-held / vehicle use).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(engine.running, engine.store.settings) { r, s -> r && s.keepScreenOn }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            SpectroTheme {
                AppRoot(engine, onReconnect = { connectSource() }, onRequestLocation = { requestLocation() },
                    onExit = { engine.shutdown(); finishAndRemoveTask() })
            }
        }

        handleIntent(intent)
        if (engine.connection.value !is Connection.Ready) connectSource()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (engine.store.value.gpsTagging) {
            if (engine.location.hasPermission()) engine.location.start() else requestLocation()
        }
    }

    override fun onStop() {
        super.onStop()
        // Logging continues only while the app is in front, so stop GPS with it unless a log is open.
        if (!engine.log.value.active) engine.location.stop()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && isSts(device) && !engine.store.value.useSimulator) {
                // Launch via the device filter grants permission implicitly.
                connectDevice(device)
            }
        }
    }

    private fun requestLocation() {
        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Connects the simulator or the first Ocean spectrometer found, asking for USB permission if needed. */
    private fun connectSource() {
        if (engine.store.value.useSimulator) {
            engine.connectSimulator()
            return
        }
        val all = usbManager.deviceList.values.filter { isSts(it) }
        // Prefer a model this version can drive.
        val device = all.firstOrNull { DeviceCatalog.find(it.vendorId, it.productId)?.usable == true } ?: all.firstOrNull()
        if (device == null) engine.disconnect() else connectDevice(device)
    }

    private fun requestUsb(device: UsbDevice) {
        engine.awaitingPermission()
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    /** Any Ocean Optics / Ocean Insight device (both vendor IDs). */
    private fun isSts(d: UsbDevice) = DeviceCatalog.isOcean(d.vendorId)

    /** Connects a supported model, or reports a recognised-but-unsupported one without asking for USB access. */
    private fun connectDevice(device: UsbDevice) {
        val model = DeviceCatalog.find(device.vendorId, device.productId)
        when {
            model == null -> engine.disconnect("Unknown Ocean device %04X:%04X".format(device.vendorId, device.productId))
            !model.usable -> engine.disconnect("${model.name} recognised: ${model.support.label.lowercase()}")
            usbManager.hasPermission(device) -> engine.connectUsb(usbManager, device)
            else -> requestUsb(device)
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.rfsat.pocketspectro.USB_PERMISSION"
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LIVE("Live", Icons.Filled.ShowChart),
    FIRE("Fire", Icons.Filled.LocalFireDepartment),
    MAP("Map", Icons.Filled.Map),
    LOG("Log", Icons.Filled.ListAlt),
    SETUP("Setup", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRoot(
    engine: AcquisitionEngine,
    onReconnect: () -> Unit,
    onRequestLocation: () -> Unit,
    onExit: () -> Unit = {},
    initialTab: Int = 0,
    showExitDialog: Boolean = false,
    initialLogSessions: Boolean = false,
    initialMapSelection: Int? = null,
) {
    var tabName by rememberSaveable { mutableStateOf(Tab.entries[initialTab].name) }
    var confirmExit by remember { mutableStateOf(showExitDialog) }
    val conn by engine.connection.collectAsState()
    val frame by engine.frame.collectAsState()
    val armed by engine.fireArmed.collectAsState()
    // The Fire tab exists only while an STS-NIR (or the simulator) is connected.
    val fireCapable = (conn as? Connection.Ready)?.info?.fireCapable == true
    val tabs = Tab.entries.filter { it != Tab.FIRE || fireCapable }
    val tab = tabs.firstOrNull { it.name == tabName } ?: Tab.HOME
    val fireAlarm = armed && frame?.fireConfirmed == true
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { engine.messages.collect { snack.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PocketSpectro")
                        Text(
                            when (val c = conn) {
                                is Connection.Ready -> "${c.info.model} · ${c.info.serial}" + (if (fireAlarm) "  · FIRE" else "")
                                Connection.Connecting -> "Connecting…"
                                Connection.AwaitingPermission -> "Allow USB access to the spectrometer"
                                Connection.NoDevice -> "Plug in a spectrometer (USB-OTG)"
                                is Connection.Failed -> c.message
                            },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (fireAlarm) FireRed else MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tabName = t.name }, icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 10.sp, maxLines = 1, softWrap = false) })
                }
                NavigationBarItem(selected = false, onClick = { confirmExit = true },
                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Exit") },
                    label = { Text("Exit", fontSize = 10.sp, maxLines = 1) })
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (confirmExit) {
            val log by engine.log.collectAsState()
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("Exit PocketSpectro?") },
                text = {
                    Text(if (log.active) "Logging is in progress (${log.spectra} spectra). It will be stopped and the files closed; " +
                        "the spectrometer is released." else "Acquisition stops and the spectrometer is released.")
                },
                confirmButton = { TextButton(onClick = { confirmExit = false; onExit() }) { Text("Exit") } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Cancel") } },
            )
        }
        Box(Modifier.fillMaxSize().padding(pad)) {
            val ready = conn is Connection.Ready
            if (!ready && tab in setOf(Tab.LIVE, Tab.FIRE)) {
                NotConnected(conn, onReconnect)
            } else {
                when (tab) {
                    Tab.HOME -> HomeScreen(engine, onOpenTab = { tabName = it.name }, onReconnect = onReconnect)
                    Tab.LIVE -> LiveScreen(engine)
                    Tab.FIRE -> FireScreen(engine)
                    Tab.MAP -> MapScreen(engine, initialMapSelection)
                    Tab.LOG -> LogTab(engine, onRequestLocation, initialSessions = initialLogSessions)
                    Tab.SETUP -> SetupScreen(engine, onReconnect)
                }
            }
        }
    }
}

@Composable
private fun NotConnected(conn: Connection, onReconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(
            when (conn) {
                Connection.Connecting -> "Connecting to the spectrometer…"
                Connection.AwaitingPermission -> "Waiting for you to allow USB access."
                is Connection.Failed -> "Connection problem: ${conn.message}"
                else -> "No spectrometer connected."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Connect a supported spectrometer with a data-capable OTG cable (micro-USB to USB-C for micro-USB units). " +
                "The app opens automatically when it is " +
                "plugged in. To try the app without hardware, turn on the simulator in Setup.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
        )
        androidx.compose.material3.OutlinedButton(onClick = onReconnect, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
    }
}
