package io.baato.trackme.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Baato vector style. The access token comes from local.properties (`baato.accessToken`), never from code. */
object Baato {
    const val STYLE = "breeze"
    val token: String get() = BuildConfig.BAATO_ACCESS_TOKEN
    val isConfigured: Boolean get() = token.isNotBlank()
    val styleUrl: String get() = "https://api.baato.io/api/v1/styles/$STYLE?key=$token"

    /** Map centre until we know where the user is. */
    val KATHMANDU = LatLng(27.7172, 85.3240)
}

/** One dot on the map. [color] is "#RRGGBB". */
data class MapMarker(val id: String, val label: String, val latitude: Double, val longitude: Double, val color: String)

/** "Fly to this marker". [seq] makes repeated taps on the same member count. */
data class MapFocus(val markerId: String, val seq: Int)

/** Stable colour per member, so a person keeps their colour across updates. */
private val palette = listOf("#E53935", "#8E24AA", "#3949AB", "#00897B", "#F4511E", "#6D4C41", "#039BE5", "#7CB342")
fun markerColor(key: Any): String = palette[(key.hashCode() and 0x7fffffff) % palette.size]

private const val TAG = "TrackMeMap"
private const val MEMBERS = "trackme-members"
private const val ME = "trackme-me"

/**
 * MapLibre MapView in Compose, with the Baato style, member dots + name labels, and a blue "you" dot.
 *
 * - [me]: your position (the SDK's last fix while sharing, otherwise the phone's location). Drawn by us, not by
 *   MapLibre's location component, so it shows as soon as there's any fix.
 * - [fitKey]: when it changes (e.g. a different trip), the camera fits all markers + you once.
 * - [recenterRequest]: bump it to fly back to you (or fit markers if your location is unknown).
 * - [focus]: fly to one marker (a tapped member chip). If that marker isn't drawn (it's you), fly to [me].
 * - [onMarkerTap]: a member dot was tapped on the map (its [MapMarker.id]).
 * - [topInsetPx] / [bottomInsetPx]: screen parts covered by the top buttons and the bottom sheet. Every camera
 *   move centres in the area between them, so "centre on X" is never hidden behind the sheet.
 * - [onError]: style / map load problems (e.g. a wrong Baato key), shown in the sample's event log.
 */
@Composable
fun BaatoMap(
    markers: List<MapMarker>,
    me: LatLng?,
    fitKey: Any?,
    recenterRequest: Int,
    modifier: Modifier = Modifier,
    focus: MapFocus? = null,
    onMarkerTap: (String) -> Unit = {},
    topInsetPx: Int = 0,
    bottomInsetPx: () -> Int = { 0 },
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reportError by rememberUpdatedState(onError)
    val markerTapped by rememberUpdatedState(onMarkerTap)
    val bottomInset by rememberUpdatedState(bottomInsetPx)
    val insets = { Insets(top = topInsetPx, bottom = bottomInset()) }
    val mapView = remember {
        MapLibre.getInstance(context)   // must run before the first MapView
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fittedFor by remember { mutableStateOf<Any?>(null) }
    var centeredOnMe by remember { mutableStateOf(false) }

    // Forward the screen's lifecycle to the MapView (required by MapLibre).
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener { message ->
            Log.e(TAG, "Map failed to load: $message")
            reportError("Map failed to load: $message")
        }
        mapView.getMapAsync { m ->
            m.cameraPosition = CameraPosition.Builder().target(Baato.KATHMANDU).zoom(12.0).build()
            m.setMaxZoomPreference(23.0)   // past this the style has no detail left and the map turns black
            m.uiSettings.isCompassEnabled = true
            // Tap a member dot (or its name) → fly to them.
            m.addOnMapClickListener { point ->
                val p = m.projection.toScreenLocation(point)
                val r = 24f * context.resources.displayMetrics.density
                val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), "trackme-member-dots", "trackme-member-labels")
                    .firstNotNullOfOrNull { runCatching { it.getStringProperty("id") }.getOrNull() }
                if (hit != null) markerTapped(hit)
                hit != null
            }
            m.setStyle(Style.Builder().fromUri(Baato.styleUrl)) { s ->
                runCatching { addOurLayers(s) }.onFailure {
                    Log.e(TAG, "Adding marker layers failed", it)
                    reportError("Marker layers: ${it.message}")
                }
                map = m
                style = s
            }
        }
    }

    // Member dots + names.
    LaunchedEffect(style, markers) {
        style?.getSourceAs<GeoJsonSource>(MEMBERS)?.setGeoJson(FeatureCollection.fromFeatures(markers.map { it.toFeature() }))
    }

    // You.
    LaunchedEffect(style, me) {
        val source = style?.getSourceAs<GeoJsonSource>(ME) ?: return@LaunchedEffect
        source.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
    }

    // Camera: fit once per trip / invite; otherwise go to you the first time we know where you are.
    LaunchedEffect(map, fitKey, markers.isNotEmpty(), me != null) {
        val m = map ?: return@LaunchedEffect
        if (fitKey != null && fittedFor != fitKey && (markers.isNotEmpty() || me != null)) {
            fittedFor = fitKey
            centeredOnMe = true
            fit(m, markers.map { LatLng(it.latitude, it.longitude) } + listOfNotNull(me), insets())
        } else if (!centeredOnMe && me != null && fittedFor == null) {
            centeredOnMe = true
            flyTo(m, me, 15.0, insets())
        }
    }

    // Recenter button.
    LaunchedEffect(recenterRequest) {
        val m = map ?: return@LaunchedEffect
        if (recenterRequest == 0) return@LaunchedEffect
        if (me != null) flyTo(m, me, 15.0, insets())
        else fit(m, markers.map { LatLng(it.latitude, it.longitude) }.ifEmpty { listOf(Baato.KATHMANDU) }, insets())
    }

    // A tapped member chip / dot.
    LaunchedEffect(focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        val target = markers.firstOrNull { it.id == f.markerId }?.let { LatLng(it.latitude, it.longitude) } ?: me ?: return@LaunchedEffect
        flyTo(m, target, maxOf(m.cameraPosition.zoom, 16.0), insets())
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Our sources and layers, on top of the Baato style. */
private fun addOurLayers(style: Style) {
    style.addSource(GeoJsonSource(MEMBERS))
    style.addSource(GeoJsonSource(ME))

    // You: a soft halo + a blue dot with a white ring.
    style.addLayer(
        CircleLayer("trackme-me-halo", ME).withProperties(
            PropertyFactory.circleRadius(22f),
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleOpacity(0.18f),
        ),
    )
    style.addLayer(
        CircleLayer("trackme-me-dot", ME).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )

    // Members.
    style.addLayer(
        CircleLayer("trackme-member-dots", MEMBERS).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    // Names. A symbol layer needs a font the style's glyph server has: MapLibre's default ("Open Sans Regular",
    // "Arial Unicode MS Regular") may not exist there, and then the labels silently don't draw. Use the font the
    // Baato style already uses for its own labels.
    val labels = SymbolLayer("trackme-member-labels", MEMBERS).withProperties(
        PropertyFactory.textField(Expression.get("label")),
        PropertyFactory.textSize(12f),
        PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
        PropertyFactory.textAnchor("top"),
        PropertyFactory.textColor("#1B1B1F"),
        PropertyFactory.textHaloColor("#FFFFFF"),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true),
    )
    styleFont(style)?.let { labels.setProperties(PropertyFactory.textFont(it)) }
    style.addLayer(labels)
}

/** The first plain `text-font` used by one of the style's own symbol layers. */
private fun styleFont(style: Style): Array<String>? =
    style.layers.asSequence()
        .filterIsInstance<SymbolLayer>()
        .mapNotNull { layer -> runCatching { layer.textFont.takeIf { it.isValue }?.value }.getOrNull() }
        .firstOrNull { it.isNotEmpty() }
        .also { Log.d(TAG, "Label font from style: ${it?.joinToString()}") }

private fun MapMarker.toFeature(): Feature =
    Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
        addStringProperty("id", id)
        addStringProperty("label", label)
        addStringProperty("color", color)
    }

/** Screen pixels covered at the top (buttons) and bottom (the sheet). */
private data class Insets(val top: Int, val bottom: Int)

/** Centre [target] in the visible part of the map (between the top buttons and the sheet). */
private fun flyTo(map: MapLibreMap, target: LatLng, zoom: Double, insets: Insets) {
    val camera = CameraPosition.Builder()
        .target(target)
        .zoom(zoom)
        .padding(0.0, insets.top.toDouble(), 0.0, insets.bottom.toDouble())
        .build()
    map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 600)
}

private fun fit(map: MapLibreMap, points: List<LatLng>, insets: Insets) {
    when (points.size) {
        0 -> Unit
        1 -> flyTo(map, points[0], 15.0, insets)
        else -> runCatching {
            val margin = 120
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(points).build(),
                    margin, insets.top + margin / 2, margin, insets.bottom + margin / 2,
                ),
                600,
            )
        }
    }
}

/**
 * The phone's location while the map is on screen (used for the blue dot when not sharing).
 * Plain LocationManager: no Google Play services needed, same as the SDK.
 */
@SuppressLint("MissingPermission")   // only runs when [granted]
@Composable
fun rememberDeviceLocation(granted: Boolean): LatLng? {
    val context = LocalContext.current
    var location by remember { mutableStateOf<LatLng?>(null) }
    DisposableEffect(granted) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!granted || lm == null) return@DisposableEffect onDispose { }

        // All four methods implemented: on Android < 10 the platform calls the "status" ones too.
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { location = LatLng(l.latitude, l.longitude) }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        providers.asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { location = LatLng(it.latitude, it.longitude) }
        providers.forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 3_000L, 0f, listener, Looper.getMainLooper()) }
                .onFailure { Log.w(TAG, "No updates from $p", it) }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return location
}
