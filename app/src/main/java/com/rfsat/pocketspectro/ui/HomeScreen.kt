package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.shortRes
import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 28.sp)
            if (!StoreShots.hideVersion) Text(stringResource(R.string.home_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), color = muted)
            Text(stringResource(R.string.home_tagline), fontSize = 13.sp)
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
                        Connection.Connecting -> stringResource(R.string.conn_connecting)
                        Connection.AwaitingPermission -> stringResource(R.string.home_awaiting)
                        Connection.NoDevice -> stringResource(R.string.home_no_device)
                        is Connection.Failed -> c.message
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (ready != null) {
                val wl = ready.info.wavelengths()
                KeyValue(stringResource(R.string.home_range), pluralStringResource(R.plurals.home_range_value, ready.info.pixels,
                    "%.0f".format(Locale.US, wl.first()), "%.0f".format(Locale.US, wl.last()), ready.info.pixels))
                val sAcq = stringResource(R.string.state_acquiring)
                val sIdle = stringResource(R.string.state_idle)
                val sArmed = stringResource(R.string.state_alarm_armed)
                val sNoFire = stringResource(R.string.state_no_fire)
                val sLog = stringResource(R.string.state_logging, log.spectra)
                KeyValue(stringResource(R.string.home_state), buildList {
                    add(if (running) sAcq else sIdle)
                    if (armed) add(sArmed)
                    if (!ready.info.fireCapable) add(sNoFire)
                    if (log.active) add(sLog)
                }.joinToString(", "))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { engine.setRunning(true); onOpenTab(Tab.LIVE) }) { Text(stringResource(R.string.home_btn_live)) }
                    if (ready.info.fireCapable) OutlinedButton(onClick = { onOpenTab(Tab.FIRE) }) { Text(stringResource(R.string.home_btn_fire)) }
                }
            } else {
                Text(stringResource(R.string.home_plug_help),
                    style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = onReconnect) { Text(stringResource(R.string.home_btn_retry)) }
            }
        }

        Text(
            stringResource(R.string.home_about),
            textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        LinkRow(stringResource(R.string.home_developer), "RFSAT Limited", "www.rfsat.com") { openUrl("https://www.rfsat.com") }
        Text(
            stringResource(R.string.home_rfsat),
            textAlign = TextAlign.Justify, style = MaterialTheme.typography.bodySmall, color = muted,
        )
        if (ready != null) {
            LinkRow(stringResource(R.string.home_spectrometer), "${ready.info.model} · ${ready.info.serial}", "oceanoptics.com") { openUrl("https://www.oceanoptics.com") }
            KeyValue(stringResource(R.string.home_interface), stringResource(R.string.home_interface_value,
                if (ready.info.simulated) stringResource(R.string.iface_simulated) else ready.info.interfaceLabel))
        } else {
            LinkRow(stringResource(R.string.home_spectrometers), stringResource(R.string.home_spectrometers_value), "oceanoptics.com") { openUrl("https://www.oceanoptics.com") }
            KeyValue(stringResource(R.string.home_interface), stringResource(R.string.home_interface_usb))
        }
        KeyValue(stringResource(R.string.home_licence), "MIT © 2026 RFSAT Limited")
        KeyValue(stringResource(R.string.home_credits), stringResource(R.string.home_credits_value))

        HorizontalDivider()

        Text(stringResource(R.string.home_spectrometers), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_support_intro),
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
                Text(stringResource(support.shortRes()), Modifier.weight(0.38f), color = colour, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
                Text(list.joinToString(", ") { it.name }, Modifier.weight(0.62f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.home_support_legend),
            style = MaterialTheme.typography.bodySmall, color = muted)
        Text(stringResource(R.string.home_not_affiliated), style = MaterialTheme.typography.bodySmall,
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

/** Test hook: store-listing screenshots leave out the version so they stay valid across releases. */
object StoreShots { @Volatile var hideVersion = false }
