package com.rfsat.pocketspectro.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rfsat.pocketspectro.data.FireEvent
import com.rfsat.pocketspectro.data.GeoFix
import com.rfsat.pocketspectro.data.SessionTrack
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Base layers, the same three raster sources as DBM's navigation map (no API key needed). */
enum class MapLayer(val label: String) { STREET("Street"), SATELLITE("Satellite"), TERRAIN("Terrain") }

object MapStyles {
    private fun raster(tiles: String, attribution: String, maxZoom: Int = 19) = """
{
  "version": 8,
  "sources": { "base": { "type": "raster", "tiles": ["$tiles"], "tileSize": 256,
    "attribution": "$attribution", "maxzoom": $maxZoom } },
  "layers": [ { "id": "base", "type": "raster", "source": "base" } ]
}
"""
    private val STREET = raster("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors")
    private val SATELLITE = raster(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "© Esri, Maxar, Earthstar Geographics")
    private val TERRAIN = raster("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "© OpenTopoMap (CC-BY-SA)", 17)

    fun styleFor(l: MapLayer) = when (l) {
        MapLayer.STREET -> STREET
        MapLayer.SATELLITE -> SATELLITE
        MapLayer.TERRAIN -> TERRAIN
    }
}

private const val TRACK_SRC = "ps-track"; private const val TRACK_LYR = "ps-track-l"
private const val EVENT_SRC = "ps-events"; private const val EVENT_LYR = "ps-events-l"
private const val SEL_SRC = "ps-sel"; private const val SEL_LYR = "ps-sel-l"
private const val OWN_SRC = "ps-own"; private const val OWN_LYR = "ps-own-l"

/**
 * MapLibre map of fire detections (red), survey tracks (blue) and the phone's position.
 * As in DBM, the user's pan and zoom are never overridden: the camera moves only on first
 * load and when [fitKey] or [recenterKey] changes.
 */
@Composable
fun EventMap(
    layer: MapLayer,
    tracks: List<SessionTrack>,
    events: List<FireEvent>,
    selected: FireEvent?,
    own: GeoFix?,
    fitKey: Int,
    recenterKey: Int,
    onEventTap: (FireEvent?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val mapHolder = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleHolder = remember { mutableStateOf<Style?>(null) }
    val loadedLayer = remember { mutableStateOf<MapLayer?>(null) }
    val didInitialCamera = remember { mutableStateOf(false) }
    val lastFit = remember { mutableStateOf(fitKey) }
    val lastRecenter = remember { mutableStateOf(recenterKey) }
    val currentEvents = rememberUpdatedState(events)
    val currentTap = rememberUpdatedState(onEventTap)

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onStop(); mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        val map = mapHolder.value
        if (map == null) {
            mv.getMapAsync { m ->
                mapHolder.value = m
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = true // OSM / Esri licence
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isTiltGesturesEnabled = false
                m.addOnMapClickListener { pt ->
                    val screen = m.projection.toScreenLocation(pt)
                    val hit = m.queryRenderedFeatures(screen, EVENT_LYR).firstOrNull()
                    val idx = hit?.getNumberProperty("i")?.toInt()
                    currentTap.value(idx?.let { currentEvents.value.getOrNull(it) })
                    true
                }
                m.setStyle(Style.Builder().fromJson(MapStyles.styleFor(layer))) { style ->
                    styleHolder.value = style; loadedLayer.value = layer
                    ensureLayers(style)
                    updateData(style, tracks, events, selected, own)
                    if (!didInitialCamera.value) {
                        fitCamera(m, tracks, events, own, animate = false)
                        didInitialCamera.value = true
                    }
                }
            }
        } else {
            if (loadedLayer.value != layer) {
                map.setStyle(Style.Builder().fromJson(MapStyles.styleFor(layer))) { style ->
                    styleHolder.value = style; loadedLayer.value = layer
                    ensureLayers(style)
                    updateData(style, tracks, events, selected, own)
                }
            } else {
                styleHolder.value?.let { updateData(it, tracks, events, selected, own) }
            }
            if (fitKey != lastFit.value) {
                fitCamera(map, tracks, events, own, animate = true)
                lastFit.value = fitKey
            }
            if (recenterKey != lastRecenter.value) {
                own?.let {
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(it.latitude, it.longitude)).zoom(15.0).build()))
                }
                lastRecenter.value = recenterKey
            }
        }
    }
}

private fun ensureLayers(style: Style) {
    if (style.getSource(TRACK_SRC) == null) style.addSource(GeoJsonSource(TRACK_SRC))
    if (style.getSource(EVENT_SRC) == null) style.addSource(GeoJsonSource(EVENT_SRC))
    if (style.getSource(SEL_SRC) == null) style.addSource(GeoJsonSource(SEL_SRC))
    if (style.getSource(OWN_SRC) == null) style.addSource(GeoJsonSource(OWN_SRC))
    if (style.getLayer(TRACK_LYR) == null) style.addLayer(LineLayer(TRACK_LYR, TRACK_SRC).withProperties(
        PropertyFactory.lineColor("#1E88E5"), PropertyFactory.lineWidth(3.5f), PropertyFactory.lineOpacity(0.85f),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
    if (style.getLayer(SEL_LYR) == null) style.addLayer(CircleLayer(SEL_LYR, SEL_SRC).withProperties(
        PropertyFactory.circleRadius(17f), PropertyFactory.circleColor("#00000000"),
        PropertyFactory.circleStrokeColor("#FFC107"), PropertyFactory.circleStrokeWidth(4f)))
    if (style.getLayer(EVENT_LYR) == null) style.addLayer(CircleLayer(EVENT_LYR, EVENT_SRC).withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#E53935"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
    if (style.getLayer(OWN_LYR) == null) style.addLayer(CircleLayer(OWN_LYR, OWN_SRC).withProperties(
        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#1565C0"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)))
}

private fun updateData(style: Style, tracks: List<SessionTrack>, events: List<FireEvent>, selected: FireEvent?, own: GeoFix?) {
    style.getSourceAs<GeoJsonSource>(TRACK_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
        tracks.filter { it.points.size >= 2 }.map { t ->
            Feature.fromGeometry(LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }))
    style.getSourceAs<GeoJsonSource>(EVENT_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
        events.mapIndexed { i, e ->
            Feature.fromGeometry(Point.fromLngLat(e.longitude, e.latitude)).also { it.addNumberProperty("i", i) }
        }))
    style.getSourceAs<GeoJsonSource>(SEL_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
        listOfNotNull(selected?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
    style.getSourceAs<GeoJsonSource>(OWN_SRC)?.setGeoJson(FeatureCollection.fromFeatures(
        listOfNotNull(own?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
}

/** Frames every detection and track point; falls back to the phone's position, then the world. */
private fun fitCamera(map: MapLibreMap, tracks: List<SessionTrack>, events: List<FireEvent>, own: GeoFix?, animate: Boolean) {
    val pts = events.map { LatLng(it.latitude, it.longitude) } +
        tracks.flatMap { t -> t.points.map { LatLng(it.latitude, it.longitude) } }
    val update = when {
        pts.size >= 2 && pts.distinct().size >= 2 -> {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
            CameraUpdateFactory.newLatLngBounds(b, 120)
        }
        pts.isNotEmpty() -> CameraUpdateFactory.newLatLngZoom(pts.first(), 15.0)
        own != null -> CameraUpdateFactory.newLatLngZoom(LatLng(own.latitude, own.longitude), 13.0)
        else -> CameraUpdateFactory.newLatLngZoom(LatLng(48.0, 10.0), 3.0)
    }
    if (animate) map.animateCamera(update) else map.moveCamera(update)
}
