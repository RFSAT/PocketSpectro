package com.rfsat.pocketspectro.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.FireEvent
import com.rfsat.pocketspectro.data.SessionTrack
import com.rfsat.pocketspectro.data.SessionTracks
import com.rfsat.pocketspectro.ui.map.EventMap
import com.rfsat.pocketspectro.ui.map.MapLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Map of fire detections (journal) and survey tracks (logged sessions). */
@Composable
fun MapScreen(engine: AcquisitionEngine, initialSelected: Int? = null) {
    val s by engine.store.settings.collectAsState()
    val fix by engine.location.fix.collectAsState()
    val journalVersion by engine.journalVersion.collectAsState()
    val log by engine.log.collectAsState()
    var events by remember { mutableStateOf(emptyList<FireEvent>()) }
    var tracks by remember { mutableStateOf(emptyList<SessionTrack>()) }
    var selected by remember { mutableStateOf<FireEvent?>(null) }
    var fitKey by remember { mutableIntStateOf(0) }
    var recenterKey by remember { mutableIntStateOf(0) }

    // Reload when a detection is journalled or the open log grows.
    LaunchedEffect(journalVersion, log.spectra / 10, log.active) {
        val (e, t) = withContext(Dispatchers.IO) { engine.journal.load() to SessionTracks.load(engine.sessionsRoot) }
        events = e; tracks = t
        if (selected == null && initialSelected != null) selected = e.getOrNull(initialSelected)
    }

    Box(Modifier.fillMaxSize()) {
        if (isRobolectric) {
            MapPreview(s.mapLayer, tracks, events, selected, Modifier.fillMaxSize())
        } else {
            EventMap(
                layer = s.mapLayer, tracks = tracks, events = events, selected = selected, own = fix,
                fitKey = fitKey, recenterKey = recenterKey, onEventTap = { selected = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Layer chips and summary
        Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in MapLayer.entries) {
                    FilterChip(
                        selected = s.mapLayer == l,
                        onClick = { engine.store.update { it.copy(mapLayer = l) } },
                        label = { Text(l.label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("${events.size} fire detection${if (events.size == 1) "" else "s"} · ${tracks.size} survey track${if (tracks.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendDot(FireRed, "detection"); LegendDot(Color(0xFF1E88E5), "track"); LegendDot(Color(0xFF1565C0), "you")
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.CenterEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { fitKey++ }) { Icon(Icons.Filled.FitScreen, "Show all detections") }
            SmallFloatingActionButton(onClick = { recenterKey++ }) { Icon(Icons.Filled.MyLocation, "Go to my position") }
        }

        if (events.isEmpty() && tracks.isEmpty()) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Text("No detections yet. Confirmed fire detections with a GPS fix appear here as red markers, " +
                    "and logged sessions draw their survey track in blue.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        selected?.let { e ->
            var spectrum by remember(e) { mutableStateOf<Pair<DoubleArray, DoubleArray>?>(null) }
            LaunchedEffect(e) { spectrum = withContext(Dispatchers.IO) { engine.journal.loadSpectrum(e) } }
            EventCard(e, spectrum, onClose = { selected = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun LegendDot(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 4.dp).background(c, RoundedCornerShape(50)).padding(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Full detail of one detection: spectrum at that place, confidence and measurement conditions. */
@Composable
private fun EventCard(e: FireEvent, spectrum: Pair<DoubleArray, DoubleArray>?, onClose: () -> Unit, modifier: Modifier) {
    var fullRange by remember(e) { mutableStateOf(false) }
    val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.58f).dp
    Surface(modifier.fillMaxWidth().heightIn(max = maxH).padding(10.dp), shape = RoundedCornerShape(14.dp),
        tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fire detection", color = FireRed, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(e.timeMs)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConfidenceBadge(e.confidence)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }

            if (spectrum != null) {
                val (wl, y) = spectrum
                val lo = minOf(e.line1Nm, e.line2Nm) - 18.0; val hi = maxOf(e.line1Nm, e.line2Nm) + 18.0
                SpectrumChart(
                    wl, y, height = 190.dp, yUnit = "counts",
                    xRange = if (fullRange || !lo.isFinite()) null else lo.coerceAtLeast(wl.first())..hi.coerceAtMost(wl.last()),
                    bands = listOf(
                        Band(com.rfsat.pocketspectro.proc.FireDetector.K_LINE_1_NM - 1.2, com.rfsat.pocketspectro.proc.FireDetector.K_LINE_1_NM + 1.2, FireRed.copy(alpha = 0.15f)),
                        Band(com.rfsat.pocketspectro.proc.FireDetector.K_LINE_2_NM - 1.2, com.rfsat.pocketspectro.proc.FireDetector.K_LINE_2_NM + 1.2, FireRed.copy(alpha = 0.15f)),
                    ),
                    markers = listOf(e.line1Nm, e.line2Nm),
                )
                androidx.compose.material3.TextButton(onClick = { fullRange = !fullRange }) {
                    Text(if (fullRange) "Zoom to K lines" else "Show full spectrum")
                }
            } else {
                Text("No spectrum was saved with this detection.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Detector", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            KeyValue("K I 766.5 nm", "%.2f nm  %.0f counts  %+.1f %%".format(Locale.US, e.line1Nm, e.line1Counts, e.line1Pct))
            KeyValue("K I 769.9 nm", "%.2f nm  %.0f counts  %+.1f %%".format(Locale.US, e.line2Nm, e.line2Counts, e.line2Pct))
            KeyValue("Compared with", if (e.baseline == "GLOBAL_MEAN") "spectrum mean (Pi rule)"
                else "local continuum %.0f / %.0f counts".format(Locale.US, e.line1Baseline, e.line2Baseline))
            KeyValue("Signal-to-noise", "%.1f / %.1f".format(Locale.US, e.snr1, e.snr2))
            KeyValue("Line ratio 766/770", if (e.ratio.isFinite()) "%.2f (expected 1–2.5)".format(Locale.US, e.ratio) else "—")
            KeyValue("Threshold", "%.1f %%, confirmed over %d scan%s".format(Locale.US, e.thresholdPct, e.confirmFrames, if (e.confirmFrames == 1) "" else "s"))

            Text("Measurement", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            KeyValue("Spectrometer", "${e.model} · ${e.serial}")
            KeyValue("Integration", "%.1f ms × %d scan%s".format(Locale.US, e.integrationUs / 1000.0, e.scans, if (e.scans == 1) "" else "s"))
            KeyValue("Position", "%.6f, %.6f".format(Locale.US, e.latitude, e.longitude) +
                (e.accuracyM?.let { "  ±%.0f m".format(Locale.US, it) } ?: ""))
            e.altitudeM?.let { KeyValue("Altitude", "%.0f m".format(Locale.US, it)) }
            if (e.speedMs != null || e.bearingDeg != null) KeyValue("Movement",
                listOfNotNull(e.speedMs?.let { "%.1f km/h".format(Locale.US, it * 3.6) }, e.bearingDeg?.let { "heading %.0f°".format(Locale.US, it) }).joinToString(", "))
            KeyValue("Session", e.session?.removePrefix("session_") ?: "not logging")
        }
    }
}

@Composable
private fun ConfidenceBadge(score: Double) {
    if (!score.isFinite()) return
    val c = when {
        score >= 70 -> SafeGreen
        score >= 40 -> WarnAmber
        else -> FireRed
    }
    Column(Modifier.background(c.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%.0f %%".format(Locale.US, score), fontWeight = FontWeight.Bold, color = c)
        Text("confidence", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Stand-in for the MapLibre view in JVM screenshot tests, where its native renderer can't
 * run. It fetches the same raster tiles (OpenStreetMap / Esri / OpenTopoMap), places them in
 * Web Mercator at the phone's density and draws the same tracks and markers on top.
 */
@Composable
private fun MapPreview(layer: MapLayer, tracks: List<SessionTrack>, events: List<FireEvent>, selected: FireEvent?, modifier: Modifier) {
    val pts = events.map { it.latitude to it.longitude } + tracks.flatMap { t -> t.points.map { it.latitude to it.longitude } }
    var tiles by remember { mutableStateOf<Map<Triple<Int, Int, Int>, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val view = remember(pts, viewSize, density, selected) {
        PreviewView.fit(pts, viewSize.width, viewSize.height, density)?.let { v ->
            selected?.let { v.centredOn(it.latitude, it.longitude, 0.15) } ?: v
        }
    }

    LaunchedEffect(layer, view) {
        val v = view ?: return@LaunchedEffect
        tiles = withContext(Dispatchers.IO) { PreviewView.fetchTiles(layer, v) }
        PreviewTiles.loaded = tiles.size
    }

    Canvas(modifier.background(Color(0xFFE8EBE4)).onSizeChanged { viewSize = it }) {
        val v = view ?: return@Canvas
        val t = v.tilePx
        for ((key, img) in tiles) {
            val (_, tx, ty) = key
            val left = (tx * t - v.originX).toFloat(); val top = (ty * t - v.originY).toFloat()
            drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(kotlin.math.ceil(t).toInt() + 1, kotlin.math.ceil(t).toInt() + 1))
        }
        fun p(lat: Double, lon: Double) = Offset(v.x(lon).toFloat(), v.y(lat).toFloat())
        for (tr in tracks) {
            if (tr.points.size < 2) continue
            val path = Path()
            tr.points.forEachIndexed { i, pt -> val o = p(pt.latitude, pt.longitude); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, Color(0xD91E88E5), style = Stroke(width = 3.5f * density))
        }
        for (e in events) {
            val o = p(e.latitude, e.longitude)
            drawCircle(Color.White, 11.5f * density, o); drawCircle(Color(0xFFE53935), 9f * density, o)
        }
        selected?.let { drawCircle(Color(0xFFFFC107), 17f * density, p(it.latitude, it.longitude), style = Stroke(width = 4f * density)) }
    }
    Box(Modifier.fillMaxSize()) {
        Text(when (layer) {
            MapLayer.STREET -> "© OpenStreetMap contributors"
            MapLayer.SATELLITE -> "© Esri, Maxar, Earthstar Geographics"
            MapLayer.TERRAIN -> "© OpenTopoMap (CC-BY-SA)"
        }, Modifier.align(Alignment.BottomEnd).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333))
    }
}

/** Lets screenshot tests wait until the preview's tiles have arrived. */
object PreviewTiles { @Volatile var loaded = 0 }

/** Web Mercator framing and tile fetching for [MapPreview]. */
private class PreviewView(val zoom: Int, val tilePx: Double, val originX: Double, val originY: Double) {
    fun x(lon: Double) = (lon + 180.0) / 360.0 * tilePx * (1 shl zoom) - originX
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / Math.PI) / 2 * tilePx * (1 shl zoom) - originY
    }
    var widthPx = 0; var heightPx = 0

    /** Same zoom, panned so the point sits at [yFraction] of the height (above the detail panel). */
    fun centredOn(lat: Double, lon: Double, yFraction: Double) =
        PreviewView(zoom, tilePx, originX + x(lon) - widthPx / 2.0, originY + y(lat) - heightPx * yFraction)
            .also { it.widthPx = widthPx; it.heightPx = heightPx }

    companion object {
        private fun wx(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world
        private fun wy(lat: Double, world: Double): Double {
            val r = Math.toRadians(lat)
            return (1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / Math.PI) / 2 * world
        }

        fun fit(pts: List<Pair<Double, Double>>, w: Int, h: Int, density: Float): PreviewView? {
            if (pts.isEmpty() || w <= 0 || h <= 0) return null
            val tile = 256.0 * density
            val la0 = pts.minOf { it.first }; val la1 = pts.maxOf { it.first }
            val lo0 = pts.minOf { it.second }; val lo1 = pts.maxOf { it.second }
            var z = 18
            while (z > 2) {
                val world = tile * (1 shl z)
                val dx = wx(lo1, world) - wx(lo0, world); val dy = wy(la0, world) - wy(la1, world)
                if (dx <= w * 0.62 && dy <= h * 0.55) break
                z--
            }
            val world = tile * (1 shl z)
            val cx = (wx(lo0, world) + wx(lo1, world)) / 2; val cy = (wy(la0, world) + wy(la1, world)) / 2
            return PreviewView(z, tile, cx - w / 2.0, cy - h / 2.0).also { it.widthPx = w; it.heightPx = h }
        }

        fun fetchTiles(layer: MapLayer, v: PreviewView): Map<Triple<Int, Int, Int>, androidx.compose.ui.graphics.ImageBitmap> {
            val out = HashMap<Triple<Int, Int, Int>, androidx.compose.ui.graphics.ImageBitmap>()
            val x0 = kotlin.math.floor(v.originX / v.tilePx).toInt(); val x1 = kotlin.math.floor((v.originX + v.widthPx) / v.tilePx).toInt()
            val y0 = kotlin.math.floor(v.originY / v.tilePx).toInt(); val y1 = kotlin.math.floor((v.originY + v.heightPx) / v.tilePx).toInt()
            for (tx in x0..x1) for (ty in y0..y1) {
                val url = when (layer) {
                    MapLayer.STREET -> "https://tile.openstreetmap.org/${v.zoom}/$tx/$ty.png"
                    MapLayer.SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${v.zoom}/$ty/$tx"
                    MapLayer.TERRAIN -> "https://a.tile.opentopomap.org/${v.zoom}/$tx/$ty.png"
                }
                try {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "PocketSpectro-screenshots/1.0 (RFSAT Limited)")
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    val bytes = c.inputStream.use { it.readBytes() }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        out[Triple(v.zoom, tx, ty)] = it.asImageBitmap()
                    }
                } catch (e: Exception) {
                    System.err.println("preview tile $url: $e")
                }
            }
            return out
        }
    }
}

private val isRobolectric: Boolean get() = Build.FINGERPRINT == "robolectric"
