package com.rfsat.pocketspectro.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfsat.pocketspectro.BuildConfig
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.Connection
import com.rfsat.pocketspectro.core.DeviceCatalog
import java.util.Locale

/** Home / About: identity, connection status and quick actions, description, credits. */
@Composable
fun HomeScreen(engine: AcquisitionEngine, onOpenTab: (Tab) -> Unit, onReconnect: () -> Unit) {
    val conn by engine.connection.collectAsState()
    val running by engine.running.collectAsState()
    val armed by engine.fireArmed.collectAsState()
    val log by engine.log.collectAsState()
    val context = LocalContext.current
    fun openUrl(url: String) = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PocketSpectro", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 28.sp)
            Text("v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = muted)
            Text("Portable spectroscopy over USB", fontSize = 13.sp)
        }

        // Connection card with quick actions
        val ready = conn as? Connection.Ready
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (conn) {
                    is Connection.Ready -> SafeGreen
                    Connection.Connecting, Connection.AwaitingPermission -> WarnAmber
                    is Connection.Failed -> FireRed
                    Connection.NoDevice -> muted
                }
                Text("●  ", color = dot, fontSize = 18.sp)
                Text(
                    when (val c = conn) {
                        is Connection.Ready -> "${c.info.model}  ·  ${c.info.serial}"
                        Connection.Connecting -> "Connecting…"
                        Connection.AwaitingPermission -> "Waiting for USB permission"
                        Connection.NoDevice -> "No spectrometer connected"
                        is Connection.Failed -> c.message
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (ready != null) {
                val wl = ready.info.wavelengths()
                KeyValue("Range", "%.0f – %.0f nm, %d pixels".format(Locale.US, wl.first(), wl.last(), ready.info.pixels))
                KeyValue("State", buildList {
                    add(if (running) "acquiring" else "idle")
                    if (armed) add("fire alarm armed")
                    if (!ready.info.fireCapable) add("no fire detection (needs a NIR unit covering 766–770 nm)")
                    if (log.active) add("logging (${log.spectra})")
                }.joinToString(", "))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { engine.setRunning(true); onOpenTab(Tab.LIVE) }) { Text("Live spectrum") }
                    if (ready.info.fireCapable) OutlinedButton(onClick = { onOpenTab(Tab.FIRE) }) { Text("Fire detection") }
                }
            } else {
                Text("Plug a supported spectrometer into the phone's USB-C port with a data-capable OTG cable " +
                    "(micro-USB units need a micro-USB to USB-C OTG cable), or turn on the simulator in Setup.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = onReconnect) { Text("Retry connection") }
            }
        }

        Text(
            "PocketSpectro turns an Android phone into a portable console for Ocean Optics USB spectrometers, " +
                "connected directly to the phone's USB-C port. It controls the spectrometer, shows live spectra in " +
                "counts, absorbance or transmittance/reflectance with dark, reference, averaging, smoothing and " +
                "nonlinearity correction, and logs GPS-tagged spectra for later analysis. With a near-infrared " +
                "spectrometer that covers 766–770 nm, a fire-detection mode watches for the potassium emission " +
                "doublet that burning vegetation produces, with an audible alarm and a map of detections, for " +
                "hand-held or vehicle-based surveys.",
            textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        LinkRow("Developer", "RFSAT Limited", "www.rfsat.com") { openUrl("https://www.rfsat.com") }
        Text(
            "RFSAT Limited is a non-profit research and development SME based in Dublin, Ireland, with a research " +
                "office in Athens, Greece. It carries out applied research and technology development under national " +
                "and EU funding programmes, including Horizon Europe, spanning digital signal processing, " +
                "micro-embedded sensing, autonomous systems, GNSS positioning, and environmental and e-Health monitoring.",
            textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodySmall, color = muted,
        )
        if (ready != null) {
            LinkRow("Spectrometer", "${ready.info.model} · ${ready.info.serial}", "oceanoptics.com") { openUrl("https://www.oceanoptics.com") }
            KeyValue("Interface", "USB host (OTG), ${ready.info.interfaceLabel}")
        } else {
            LinkRow("Spectrometers", "Ocean Optics / Ocean Insight USB", "oceanoptics.com") { openUrl("https://www.oceanoptics.com") }
            KeyValue("Interface", "USB host (OTG)")
        }
        KeyValue("Licence", "MIT © 2026 RFSAT Limited")
        KeyValue("Credits", "USB protocols ported from Ocean Optics SeaBreeze (MIT)")

        HorizontalDivider()

        Text("Spectrometers", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text("Any Ocean Optics / Ocean Insight USB spectrometer is recognised when plugged in. Status in this version:",
            style = MaterialTheme.typography.bodySmall, color = muted)
        val groups = DeviceCatalog.models.groupBy { it.support }
        for (support in DeviceCatalog.Support.entries) {
            val list = groups[support] ?: continue
            val colour = when (support) {
                DeviceCatalog.Support.SUPPORTED -> SafeGreen
                DeviceCatalog.Support.EXPERIMENTAL -> WarnAmber
                else -> muted
            }
            Row {
                Text(support.short, Modifier.weight(0.38f), color = colour, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                Text(list.joinToString(", ") { it.name }, Modifier.weight(0.62f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Experimental: driver ported from Ocean's SeaBreeze sources, not yet tested on that hardware. " +
            "Needs external power: also needs a powered USB hub or the unit's own supply.",
            style = MaterialTheme.typography.bodySmall, color = muted)
        Text("Not affiliated with or endorsed by Ocean Optics / Ocean Insight.", style = MaterialTheme.typography.bodySmall,
            color = muted.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun LinkRow(label: String, value: String, url: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(0.58f)) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
