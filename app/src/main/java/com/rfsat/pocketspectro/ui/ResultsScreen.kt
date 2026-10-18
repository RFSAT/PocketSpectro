package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rfsat.pocketspectro.core.AcquisitionEngine
import java.io.File
import java.util.Locale

@Composable
fun ResultsScreen(engine: AcquisitionEngine) {
    val context = LocalContext.current
    val log by engine.log.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf(emptyList<File>()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    val journalVersion by engine.journalVersion.collectAsState()
    var journalCount by remember { mutableIntStateOf(0) }
    var confirmClearJournal by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, log.active, log.spectra) {
        sessions = engine.sessionsRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    }
    LaunchedEffect(journalVersion, refresh) { journalCount = engine.journal.load().size }

    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "journal") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.res_journal), fontWeight = FontWeight.SemiBold)
                    Text(pluralStringResource(R.plurals.res_journal_count, journalCount, journalCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (journalCount > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(onClick = { share(context, listOf(engine.journal.file)) }, compact = true) { Text(stringResource(R.string.res_share_csv)) }
                        ActionButton(onClick = { confirmClearJournal = true }, compact = true) { Text(stringResource(R.string.action_clear)) }
                    }
                }
            }
        }
        if (sessions.isEmpty()) item(key = "empty") {
            Text(stringResource(R.string.res_no_sessions), Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(sessions, key = { it.name }) { dir ->
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            val isLive = log.active && log.folder == dir.name
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                Column(Modifier.clickable { expanded = if (expanded == dir.name) null else dir.name }.padding(12.dp)) {
                    Text(dir.name.removePrefix("session_") + if (isLive) "  " + stringResource(R.string.res_recording) else "", fontWeight = FontWeight.SemiBold)
                    Text(pluralStringResource(R.plurals.res_files, files.size, files.size, human(files.sumOf { it.length() })), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded == dir.name) {
                        val shown = files.take(40)
                        for (f in shown) {
                            Row(Modifier.fillMaxWidth().clickable { open(context, f) }.padding(vertical = 3.dp)) {
                                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(human(f.length()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (files.size > shown.size) Text(pluralStringResource(R.plurals.res_more_files, files.size - shown.size, files.size - shown.size),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(onClick = { share(context, files) }, compact = true) { Text(stringResource(R.string.res_share_all)) }
                            ActionButton(onClick = { confirmDelete = dir }, enabled = !isLive, compact = true) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearJournal) {
        AlertDialog(
            onDismissRequest = { confirmClearJournal = false },
            title = { Text(stringResource(R.string.res_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.res_clear_text, journalCount, journalCount)) },
            confirmButton = { ActionButton(onClick = { engine.clearJournal(); confirmClearJournal = false; refresh++ }, emphasis = true) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { ActionButton(onClick = { confirmClearJournal = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    confirmDelete?.let { dir ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.res_delete_title)) },
            text = { Text(stringResource(R.string.res_delete_text, dir.name)) },
            confirmButton = { ActionButton(onClick = { dir.deleteRecursively(); confirmDelete = null; refresh++ }, emphasis = true) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { ActionButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun uri(context: Context, f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

private fun mime(f: File) = when (f.extension.lowercase(Locale.US)) {
    "csv" -> "text/csv"
    "kml" -> "application/vnd.google-earth.kml+xml"
    else -> "text/plain"
}

private fun open(context: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(i, f.name)) } catch (_: Exception) {}
}

private fun share(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { uri(context, it) })
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { context.startActivity(Intent.createChooser(i, context.getString(R.string.res_share_session))) } catch (_: Exception) {}
}

private fun human(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f kB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
