package com.rfsat.pocketspectro.ui

import com.rfsat.pocketspectro.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.data.FireEvent
import com.rfsat.pocketspectro.data.SessionTrack
import com.rfsat.pocketspectro.data.SessionTracks
import com.rfsat.pocketspectro.ui.map.EventMap
import com.rfsat.pocketspectro.ui.map.MapLayer
import com.rfsat.pocketspectro.ui.map.isSimulated
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
    var selectedTool by remember { mutableStateOf<com.rfsat.pocketspectro.data.ToolEvent?>(null) }
    var toolEvents by remember { mutableStateOf(emptyList<com.rfsat.pocketspectro.data.ToolEvent>()) }
    val toolVersion by engine.tools.eventsVersion.collectAsState()
    LaunchedEffect(toolVersion) { toolEvents = withContext(Dispatchers.IO) { engine.toolEvents.load() } }
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
            MapPreview(s.mapLayer, tracks, events, selected, Modifier.fillMaxSize(), toolEvents)
        } else {
            EventMap(
                layer = s.mapLayer, tracks = tracks, events = events, selected = selected, own = fix,
                fitKey = fitKey, recenterKey = recenterKey, onEventTap = { selected = it; selectedTool = null },
                modifier = Modifier.fillMaxSize(),
                toolEvents = toolEvents, onToolTap = { selectedTool = it; selected = null },
            )
        }

        // Layer chips and summary
        Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in MapLayer.entries) {
                    ChoiceChip(selected = s.mapLayer == l, label = stringResource(l.label),
                        onClick = { engine.store.update { it.copy(mapLayer = l) } }, container = MaterialTheme.colorScheme.surface)
                }
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(pluralStringResource(R.plurals.map_detections, events.size, events.size) + " · " +
                        pluralStringResource(R.plurals.map_tracks, tracks.size, tracks.size) +
                        (if (toolEvents.isNotEmpty()) " · " + pluralStringResource(R.plurals.map_tool_results, toolEvents.size, toolEvents.size) else ""),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendIcon(R.drawable.ic_map_flame, stringResource(R.string.map_legend_detection))
                        if (events.any { it.isSimulated() }) LegendIcon(R.drawable.ic_map_flame_sim, stringResource(R.string.map_legend_simulated))
                        LegendDot(Color(0xFF1E88E5), stringResource(R.string.map_legend_track))
                        LegendDot(Color(0xFF1565C0), stringResource(R.string.map_legend_you))
                        if (toolEvents.isNotEmpty()) LegendDot(Color(0xFF8BC34A), stringResource(R.string.map_legend_tools))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.CenterEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { fitKey++ }, modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(12.dp))) { Icon(Icons.Filled.FitScreen, stringResource(R.string.map_show_all)) }
            SmallFloatingActionButton(onClick = { recenterKey++ }, modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(12.dp))) { Icon(Icons.Filled.MyLocation, stringResource(R.string.map_my_position)) }
        }

        if (events.isEmpty() && tracks.isEmpty() && toolEvents.isEmpty()) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Text(stringResource(R.string.map_empty),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        selected?.let { e ->
            var spectrum by remember(e) { mutableStateOf<Pair<DoubleArray, DoubleArray>?>(null) }
            LaunchedEffect(e) { spectrum = withContext(Dispatchers.IO) { engine.journal.loadSpectrum(e) } }
            EventCard(e, spectrum, onClose = { selected = null }, Modifier.align(Alignment.BottomCenter))
        }
        selectedTool?.let { e -> ToolEventCard(e, onClose = { selectedTool = null }, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun LegendIcon(@androidx.annotation.DrawableRes icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(15.dp))
        Text(" $label", style = MaterialTheme.typography.labelSmall)
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
                    Text(stringResource(if (e.isSimulated()) R.string.map_fire_detection_sim else R.string.map_fire_detection), color = FireRed, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", androidx.core.os.ConfigurationCompat.getLocales(androidx.compose.ui.platform.LocalConfiguration.current)[0] ?: Locale.US).format(Date(e.timeMs)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConfidenceBadge(e.confidence)
                androidx.compose.material3.OutlinedIconButton(onClick = onClose, border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant)) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
            }

            if (spectrum != null) {
                val (wl, y) = spectrum
                val lo = minOf(e.line1Nm, e.line2Nm) - 18.0; val hi = maxOf(e.line1Nm, e.line2Nm) + 18.0
                SpectrumChart(
                    wl, y, height = 190.dp, yUnit = stringResource(R.string.unit_counts),
                    xRange = if (fullRange || !lo.isFinite()) null else lo.coerceAtLeast(wl.first())..hi.coerceAtMost(wl.last()),
                    bands = listOf(
                        Band(com.rfsat.pocketspectro.proc.FireDetector.K_LINE_1_NM - 1.2, com.rfsat.pocketspectro.proc.FireDetector.K_LINE_1_NM + 1.2, FireRed.copy(alpha = 0.15f)),
                        Band(com.rfsat.pocketspectro.proc.FireDetector.K_LINE_2_NM - 1.2, com.rfsat.pocketspectro.proc.FireDetector.K_LINE_2_NM + 1.2, FireRed.copy(alpha = 0.15f)),
                    ),
                    markers = listOf(e.line1Nm, e.line2Nm),
                )
                ActionButton(onClick = { fullRange = !fullRange }, compact = true) {
                    Text(stringResource(if (fullRange) R.string.fire_zoom_k else R.string.map_show_full_spectrum))
                }
            } else {
                Text(stringResource(R.string.map_no_spectrum), style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.map_detector), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            KeyValue("K I 766.5 nm", stringResource(R.string.fire_window_value, "%.2f".format(Locale.US, e.line1Nm),
                "%.0f".format(Locale.US, e.line1Counts), "%+.1f".format(Locale.US, e.line1Pct)))
            KeyValue("K I 769.9 nm", stringResource(R.string.fire_window_value, "%.2f".format(Locale.US, e.line2Nm),
                "%.0f".format(Locale.US, e.line2Counts), "%+.1f".format(Locale.US, e.line2Pct)))
            KeyValue(stringResource(R.string.fire_compared_with), if (e.baseline == "GLOBAL_MEAN") stringResource(R.string.map_cmp_mean)
                else stringResource(R.string.map_cmp_local, "%.0f".format(Locale.US, e.line1Baseline), "%.0f".format(Locale.US, e.line2Baseline)))
            KeyValue(stringResource(R.string.map_snr), "%.1f / %.1f".format(Locale.US, e.snr1, e.snr2))
            KeyValue(stringResource(R.string.map_ratio), if (e.ratio.isFinite()) stringResource(R.string.map_ratio_value, "%.2f".format(Locale.US, e.ratio)) else "—")
            KeyValue(stringResource(R.string.set_threshold), pluralStringResource(R.plurals.map_threshold_value, e.confirmFrames,
                "%.1f".format(Locale.US, e.thresholdPct), e.confirmFrames))

            Text(stringResource(R.string.map_measurement), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            KeyValue(stringResource(R.string.home_spectrometer), "${e.model} · ${e.serial}")
            KeyValue(stringResource(R.string.field_integration), pluralStringResource(R.plurals.map_integration_value, e.scans,
                "%.1f".format(Locale.US, e.integrationUs / 1000.0), e.scans))
            KeyValue(stringResource(R.string.map_position), "%.6f, %.6f".format(Locale.US, e.latitude, e.longitude) +
                (e.accuracyM?.let { "  ±%.0f m".format(Locale.US, it) } ?: ""))
            e.altitudeM?.let { KeyValue(stringResource(R.string.map_altitude), "%.0f m".format(Locale.US, it)) }
            val heading = e.bearingDeg?.let { stringResource(R.string.map_heading, "%.0f".format(Locale.US, it)) }
            if (e.speedMs != null || e.bearingDeg != null) KeyValue(stringResource(R.string.map_movement),
                listOfNotNull(e.speedMs?.let { "%.1f km/h".format(Locale.US, it * 3.6) }, heading).joinToString(", "))
            KeyValue(stringResource(R.string.map_session), e.session?.removePrefix("session_") ?: stringResource(R.string.map_not_logging))
        }
    }
}

/** A result posted by one of the tools. */
@Composable
private fun ToolEventCard(e: com.rfsat.pocketspectro.data.ToolEvent, onClose: () -> Unit, modifier: Modifier) {
    val tool = com.rfsat.pocketspectro.tools.ToolId.entries.firstOrNull { it.name == e.tool }
    Surface(modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(android.graphics.Color.parseColor(e.colour)), RoundedCornerShape(50)))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(tool?.let { stringResource(it.titleRes) } ?: e.tool, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", androidx.core.os.ConfigurationCompat.getLocales(androidx.compose.ui.platform.LocalConfiguration.current)[0] ?: Locale.US).format(Date(e.timeMs)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.OutlinedIconButton(onClick = onClose, border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant)) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
            }
            Text(e.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (e.detail.isNotBlank()) Text(e.detail.replace("; ", "\n"), style = MaterialTheme.typography.bodySmall)
            KeyValue(stringResource(R.string.map_position), "%.6f, %.6f".format(Locale.US, e.latitude, e.longitude) +
                (e.accuracyM?.let { "  ±%.0f m".format(Locale.US, it) } ?: ""))
            if (e.model.isNotBlank()) KeyValue(stringResource(R.string.home_spectrometer), e.model)
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
        Text(stringResource(R.string.map_confidence_badge), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Stand-in for the MapLibre view in JVM screenshot tests, where its native renderer can't
 * run. It fetches the same raster tiles (OpenStreetMap / Esri / OpenTopoMap), places them in
 * Web Mercator at the phone's density and draws the same tracks and markers on top.
 */
@Composable
private fun MapPreview(layer: MapLayer, tracks: List<SessionTrack>, events: List<FireEvent>, selected: FireEvent?, modifier: Modifier,
                       toolEvents: List<com.rfsat.pocketspectro.data.ToolEvent> = emptyList()) {
    val pts = events.map { it.latitude to it.longitude } + toolEvents.map { it.latitude to it.longitude } + tracks.flatMap { t -> t.points.map { it.latitude to it.longitude } }
    var tiles by remember { mutableStateOf<Map<Triple<Int, Int, Int>, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val flame = androidx.compose.ui.res.painterResource(R.drawable.ic_map_flame)
    val flameSim = androidx.compose.ui.res.painterResource(R.drawable.ic_map_flame_sim)
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
        for (e in toolEvents) {
            val o = p(e.latitude, e.longitude)
            drawCircle(Color.White, 9f * density, o); drawCircle(Color(android.graphics.Color.parseColor(e.colour)), 7f * density, o)
        }
        selected?.let { drawCircle(Color(0xFFFFC107), 21f * density, p(it.latitude, it.longitude), style = Stroke(width = 4f * density)) }
        val sz = 30f * density
        for (e in events) {
            val o = p(e.latitude, e.longitude)
            translate(o.x - sz / 2, o.y - sz / 2) { with(if (e.isSimulated()) flameSim else flame) { draw(androidx.compose.ui.geometry.Size(sz, sz)) } }
        }
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
