package com.rfsat.pocketspectro.ui

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
                    Text("Fire detection journal", fontWeight = FontWeight.SemiBold)
                    Text("$journalCount detections with GPS position, recorded with or without a log (shown on the Map tab)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (journalCount > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { share(context, listOf(engine.journal.file)) }) { Text("Share CSV") }
                        TextButton(onClick = { confirmClearJournal = true }) { Text("Clear") }
                    }
                }
            }
        }
        if (sessions.isEmpty()) item(key = "empty") {
            Text("No logged sessions yet. Start one from Recording.", Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(sessions, key = { it.name }) { dir ->
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            val isLive = log.active && log.folder == dir.name
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                Column(Modifier.clickable { expanded = if (expanded == dir.name) null else dir.name }.padding(12.dp)) {
                    Text(dir.name.removePrefix("session_") + if (isLive) "  (recording)" else "", fontWeight = FontWeight.SemiBold)
                    Text("${files.size} file${if (files.size == 1) "" else "s"}, ${human(files.sumOf { it.length() })}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded == dir.name) {
                        val shown = files.take(40)
                        for (f in shown) {
                            Row(Modifier.fillMaxWidth().clickable { open(context, f) }.padding(vertical = 3.dp)) {
                                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(human(f.length()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (files.size > shown.size) Text("… and ${files.size - shown.size} more (all included when sharing)",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { share(context, files) }) { Text("Share all") }
                            TextButton(onClick = { confirmDelete = dir }, enabled = !isLive) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearJournal) {
        AlertDialog(
            onDismissRequest = { confirmClearJournal = false },
            title = { Text("Clear detection journal?") },
            text = { Text("All $journalCount recorded detections are removed from the phone and the map. Logged sessions are kept.") },
            confirmButton = { TextButton(onClick = { engine.clearJournal(); confirmClearJournal = false; refresh++ }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClearJournal = false }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { dir ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete session?") },
            text = { Text("${dir.name} and all its files will be removed from the phone.") },
            confirmButton = { TextButton(onClick = { dir.deleteRecursively(); confirmDelete = null; refresh++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
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
    try { context.startActivity(Intent.createChooser(i, "Share session")) } catch (_: Exception) {}
}

private fun human(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f kB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}
