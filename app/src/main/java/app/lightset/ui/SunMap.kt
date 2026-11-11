package app.lightset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lightset.sun.Place
import app.lightset.sun.Sun
import app.lightset.sun.SunPos
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** OpenFreeMap's light style: OpenStreetMap data, free and keyless. Credited by the map's (i) button. */
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** Overlay lines and text that read on the light map. */
private val Ink = Color(0xFF2A2A2D)

/**
 * A map with the sun drawn around a place (the pin, or the phone): the day's path as a
 * ring, where higher is nearer the middle; sunrise and sunset lines; the sun at the
 * slider's time and the shadow it casts. Long-press to move the pin, or search for a place.
 */
@Composable
internal fun SunMap(state: SunUiState, vm: SunViewModel, fmt: TimeFormats) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val icons = rememberOverlayIcons()
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped as the map moves, so the overlay (which reads it) redraws with it.
    var cameraTick by remember { mutableIntStateOf(0) }
    val latestVm by rememberUpdatedState(vm)

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val start = state.shownPlace
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setStyle(Style.Builder().fromUri(MAP_STYLE))
            // North stays up, so bearings on the map are plain screen angles.
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(if (start != null) LatLng(start.lat, start.lon) else LatLng(0.0, 0.0))
                .zoom(if (start != null) 14.0 else 1.0)
                .build()
            m.addOnCameraMoveListener { cameraTick++ }
            m.addOnCameraIdleListener { cameraTick++ }
            m.addOnMapLongClickListener { p ->
                latestVm.setPin(p.latitude, p.longitude)
                m.animateCamera(CameraUpdateFactory.newLatLng(p))
                true
            }
            map = m
        }
    }

    // A pin placed by search (or the phone, after "my location") that's off screen: go there.
    val shown = state.shownPlace
    LaunchedEffect(map, shown?.lat, shown?.lon) {
        val m = map ?: return@LaunchedEffect
        val p = shown ?: return@LaunchedEffect
        val target = LatLng(p.lat, p.lon)
        if (!m.projection.visibleRegion.latLngBounds.contains(target)) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(target, maxOf(m.cameraPosition.zoom, 13.0)))
        }
    }

    val place = state.shownPlace
    // One selection for both the overlay and the panel.
    val sel = place?.let { rememberSelection(state, it) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (place != null && sel != null) {
                Canvas(Modifier.fillMaxSize()) {
                    cameraTick.let { } // read, to redraw as the map moves
                    val m = map ?: return@Canvas
                    val pt = m.projection.toScreenLocation(LatLng(place.lat, place.lon))
                    drawSunDiagram(Offset(pt.x, pt.y), state, sel.pos, icons, measurer, fmt)
                }
                SearchBar(state, vm, Modifier.align(Alignment.TopStart).padding(10.dp))
                if (state.pin == null) {
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                            .background(Scrim, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Hold to drop a pin", fontSize = 13.sp)
                    }
                }
            }
        }
        if (sel != null) TimePanel(state, vm, fmt, sel)
    }
}

/** Search for a town, address or landmark; the pin goes to the first match. */
@Composable
private fun SearchBar(state: SunUiState, vm: SunViewModel, modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search for a place") },
            leadingIcon = {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Search, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                vm.search(text)
                focus.clearFocus()
            }),
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xE6161618),
                unfocusedContainerColor = Color(0xCC161618),
            ),
        )
        state.searchError?.let {
            Text(it, Modifier.background(Scrim, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                color = WarnAmber, fontSize = 13.sp)
        }
    }
}

/** Small text on a dark pill centred on [center], kept on screen. */
private fun DrawScope.pillLabel(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle = smallLabel) {
    val layout = measurer.measure(text, style)
    val pad = 5.dp.toPx()
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + pad
    val x = (center.x - w / 2).coerceIn(2f, size.width - w - 2f)
    val y = (center.y - h / 2).coerceIn(2f, size.height - h - 2f)
    drawRoundRect(Scrim, Offset(x, y), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(x + pad, y + pad / 2))
}

/** Dark text with a white outline, which reads on the light map without a pill. */
private fun DrawScope.haloLabel(measurer: TextMeasurer, text: String, at: Offset) {
    val halo = measurer.measure(text, smallLabel.copy(color = Color.White))
    val ink = measurer.measure(text, smallLabel.copy(color = Ink, fontWeight = FontWeight.Bold))
    val d = 1.dp.toPx()
    for ((dx, dy) in listOf(-d to 0f, d to 0f, 0f to -d, 0f to d, -d to -d, d to d, -d to d, d to -d)) {
        drawText(halo, topLeft = at + Offset(dx, dy))
    }
    drawText(ink, topLeft = at)
}

/**
 * The sun around [c]: a ring for the horizon (north up), the day's path inside it with
 * the sun's height as nearness to the middle, sunrise and sunset as lines out past the
 * ring, each hour as its sky's icon, and the sun at the slider's time with its shadow.
 */
private fun DrawScope.drawSunDiagram(
    c: Offset,
    state: SunUiState,
    selected: SunPos,
    icons: OverlayIcons,
    measurer: TextMeasurer,
    fmt: TimeFormats,
) {
    val r = size.minDimension * 0.36f
    fun dir(az: Double) = Offset(sin(Math.toRadians(az)).toFloat(), -cos(Math.toRadians(az)).toFloat())
    fun at(az: Double, alt: Double) = c + dir(az) * (r * (1 - alt.coerceIn(0.0, 90.0) / 90.0).toFloat())

    // Horizon ring, with 30° and 60° rings faint inside it, and N E S W outside.
    drawCircle(Color(0x0D000000), r, c)
    drawCircle(Ink.copy(alpha = 0.8f), r, c, style = Stroke(2.dp.toPx()))
    val faint = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    for (alt in listOf(30.0, 60.0)) {
        drawCircle(Ink.copy(alpha = 0.45f), r * (1 - alt / 90.0).toFloat(), c, style = Stroke(1.dp.toPx(), pathEffect = faint))
    }
    listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W").forEach { (az, name) ->
        pillLabel(measurer, name, c + dir(az) * (r + 16.dp.toPx()), labelStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }

    // Sunrise and sunset: lines from the place out past the ring.
    state.day?.let { day ->
        listOf(day.sunriseAzimuth to day.sunrise, day.sunsetAzimuth to day.sunset).forEach { (az, time) ->
            if (az == null) return@forEach
            val end = c + dir(az) * (r * 1.3f)
            drawLine(GoldenOrange, c, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(shadowColor, 13.dp.toPx(), end)
            icon(icons.twilight, end, 18.dp.toPx(), GoldenOrange)
            pillLabel(measurer, fmt.time(time), end + Offset(0f, 24.dp.toPx()))
        }
    }

    // The day's path above the horizon, coloured by light.
    val track = state.track
    for (i in 1 until track.size) {
        val a = track[i - 1]
        val b = track[i]
        if (a.altitude < 0 && b.altitude < 0) continue
        drawLine(
            phaseColor(Sun.phase((a.altitude + b.altitude) / 2)), at(a.azimuth, a.altitude), at(b.azimuth, b.altitude),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
        )
    }

    // Each hour the sun's up, as its sky's icon.
    for (h in state.hours) {
        if (h.sun.altitude < 0) continue
        val p = at(h.sun.azimuth, h.sun.altitude)
        val sky = h.sky
        if (sky != null) {
            drawCircle(shadowColor, 12.dp.toPx(), p)
            icon(icons.sky.getValue(sky), p, 17.dp.toPx(), skyTint(sky))
        } else {
            drawCircle(shadowColor, 6.dp.toPx(), p)
            drawCircle(Color(0xFFDDDDDD), 4.dp.toPx(), p)
        }
        haloLabel(measurer, h.time.format(fmt.shortHour), p + Offset(13.dp.toPx(), -7.dp.toPx()))
    }

    // The sun at the slider's time: a line out to it, and its shadow the other way, with
    // how long a shadow is against the height of what casts it.
    if (selected.altitude > 0) {
        val sunAt = at(selected.azimuth, selected.altitude)
        val ratio = 1 / tan(Math.toRadians(selected.altitude))
        val shadowEnd = c + dir(selected.azimuth + 180) * (r * (0.25f + 0.15f * ratio.toFloat()).coerceAtMost(1.1f))
        drawLine(Color(0xCC000000), c, shadowEnd, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0xFF8D8D92), c, shadowEnd, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        pillLabel(measurer, "×%.1f".format(ratio), shadowEnd + dir(selected.azimuth + 180) * 18.dp.toPx())
        drawLine(SunAmber, c, sunAt, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(SunAmber.copy(alpha = 0.3f), 16.dp.toPx(), sunAt)
        drawCircle(SunAmber, 9.dp.toPx(), sunAt)
    }

    // The place itself.
    drawCircle(shadowColor, 8.dp.toPx(), c)
    drawCircle(Color.White, 6.dp.toPx(), c)
    drawCircle(Color(0xFFE53935), 3.5.dp.toPx(), c)
}
