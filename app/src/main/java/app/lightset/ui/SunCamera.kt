package app.lightset.ui

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lightset.sun.HourRow
import app.lightset.sun.Orientation
import app.lightset.sun.OrientationReading
import app.lightset.sun.Place
import app.lightset.sun.Phase
import app.lightset.sun.SkyProjection
import app.lightset.sun.Sky
import app.lightset.sun.Sun
import app.lightset.sun.SunPos
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the overlay needs to know about the camera to line the sky up with the picture. */
private data class LensInfo(
    val focalMm: Float,
    val sensorLongMm: Float,
    val sensorShortMm: Float,
    val streamLong: Int,
    val streamShort: Int,
)

internal val GoldenOrange = Color(0xFFFF9A3C)
internal val Scrim = Color(0xAA000000)

/** Icons drawn into the overlay, as painters (a Canvas can't hold composables). */
internal class OverlayIcons(val sky: Map<Sky, Painter>, val sun: Painter, val twilight: Painter)

@Composable
internal fun rememberOverlayIcons(): OverlayIcons {
    val sky = Sky.entries.associateWith { rememberVectorPainter(skyIcon(it)) }
    val sun = rememberVectorPainter(Icons.Filled.WbSunny)
    val twilight = rememberVectorPainter(Icons.Filled.WbTwilight)
    return remember(sky, sun, twilight) { OverlayIcons(sky, sun, twilight) }
}

/**
 * The live camera with the sun's path for the chosen day drawn over it: each hour as its
 * forecast sky's icon, the sun now, and a ring at the time on the slider.
 */
@Composable
internal fun SunCamera(state: SunUiState, vm: SunViewModel, fmt: TimeFormats) {
    val context = LocalContext.current
    val place = state.place ?: return
    val lens = rememberCameraPreview()
    val orientation = remember { Orientation(context) }
    val readings = remember { orientation.readings() }
    val reading by readings.collectAsStateWithLifecycle(initialValue = null)
    val declination = remember(place) { Orientation.declination(place.lat, place.lon) }
    val icons = rememberOverlayIcons()
    val sel = rememberSelection(state, place)
    val selected = sel.pos
    val selectedTime = sel.time
    val sunNow = remember(place, state.now) { Sun.position(place.lat, place.lon, state.now) }
    var aligning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Clipped: the preview is scaled to fill, and would otherwise spill over the header.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(factory = { lens.view }, modifier = Modifier.fillMaxSize())
            val r = reading
            val info = lens.info
            if (r != null && info != null) {
                SkyOverlay(
                    state, r, info, declination + state.headingCorrection, fmt, icons,
                    selected = selected, selectedTime = selectedTime, sunNow = sunNow.takeIf { state.date == state.today },
                    aligning = aligning,
                    onAligned = { tappedAz ->
                        vm.alignOnSun(tappedAz, sunNow.azimuth)
                        aligning = false
                    },
                )
            }
            lens.error?.let {
                Text("Camera: $it", Modifier.align(Alignment.Center).padding(24.dp), color = WarnAmber)
            }
            CompassControls(
                orientation.available, r, state.aligned, aligning,
                canAlign = sunNow.altitude > 1,
                onAlign = { aligning = !aligning },
                onReset = vm::clearAlignment,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            if (aligning) {
                Row(
                    Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.WbSunny, contentDescription = "Tap the middle of the sun", tint = SunAmber,
                        modifier = Modifier.size(22.dp))
                }
            }
        }
        TimePanel(state, vm, fmt, sel)
    }
}

/** The time picked on the slider, where the sun is then, and that hour's row. */
internal class Selection(
    val minute: Int,
    val setMinute: (Int) -> Unit,
    val defaultMinute: Int,
    val time: ZonedDateTime,
    val pos: SunPos,
    val hour: HourRow?,
)

/** The slider's time for [place]: now for today, solar noon on other days, until it's moved. */
@Composable
internal fun rememberSelection(state: SunUiState, place: Place): Selection {
    val dayStart = state.date.atStartOfDay(state.zone)
    val defaultMinute = if (state.date == state.today) Duration.between(dayStart, state.now).toMinutes().toInt()
    else state.day?.noon?.let { Duration.between(dayStart, it).toMinutes().toInt() } ?: 720
    // Back to the default for a new day, place or time zone.
    var minute by rememberSaveable(state.date, state.zone.id, place.lat, place.lon) { mutableIntStateOf(defaultMinute) }
    val time = dayStart.plusMinutes(minute.toLong())
    val pos = remember(place.lat, place.lon, time) { Sun.position(place.lat, place.lon, time) }
    return Selection(minute, { minute = it }, defaultMinute, time, pos, state.hours.lastOrNull { !it.time.isAfter(time) })
}

private class CameraPreviewState(val view: PreviewView) {
    var info by mutableStateOf<LensInfo?>(null)
    var error by mutableStateOf<String?>(null)
}

/** Binds the back camera's preview to a PreviewView while on screen, and reads its lens. */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun rememberCameraPreview(): CameraPreviewState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember {
        CameraPreviewState(
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            },
        )
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
            )
            .build()
        preview.setSurfaceProvider(state.view.surfaceProvider)
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get().also { provider = it }
                val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                val c = Camera2CameraInfo.from(camera.cameraInfo)
                val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val physical: SizeF? = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels: Size? = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active: Rect? = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val stream = preview.resolutionInfo?.resolution ?: Size(1440, 1080)
                state.info = if (focal != null && physical != null) {
                    // The physical size covers the whole pixel array; the picture comes from the active part.
                    val w = physical.width * ((active?.width() ?: 1).toFloat() / (pixels?.width ?: active?.width() ?: 1))
                    val h = physical.height * ((active?.height() ?: 1).toFloat() / (pixels?.height ?: active?.height() ?: 1))
                    LensInfo(focal, maxOf(w, h), minOf(w, h), maxOf(stream.width, stream.height), minOf(stream.width, stream.height))
                } else {
                    // No lens data: assume a typical main camera (about 66° across the long side).
                    LensInfo(4.0f, 5.2f, 3.9f, maxOf(stream.width, stream.height), minOf(stream.width, stream.height))
                }
                state.error = null
            } catch (e: Exception) {
                state.error = e.message ?: e.javaClass.simpleName
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbind(preview)
        }
    }
    return state
}

@Composable
private fun SkyOverlay(
    state: SunUiState,
    reading: OrientationReading,
    lens: LensInfo,
    headingShift: Double,
    fmt: TimeFormats,
    icons: OverlayIcons,
    selected: SunPos,
    selectedTime: ZonedDateTime,
    sunNow: SunPos?,
    aligning: Boolean,
    onAligned: (Double) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // The last frame's projection, for turning a tap back into a direction. Not state:
    // it's written while drawing, and state written there would redraw forever.
    val last = remember { arrayOfNulls<SkyProjection>(1) }
    val aligned by rememberUpdatedState(onAligned)
    val tapModifier = if (aligning) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { tap -> last[0]?.let { aligned(it.unproject(tap.x, tap.y).first) } }
        }
    } else Modifier
    Canvas(Modifier.fillMaxSize().then(tapModifier)) {
        val focal = SkyProjection.focalPx(
            lens.focalMm, lens.sensorLongMm, lens.sensorShortMm, lens.streamLong, lens.streamShort,
            size.width.roundToInt(), size.height.roundToInt(),
        )
        val proj = SkyProjection(reading.rotation, headingShift, focal, size.width / 2, size.height / 2)
        last[0] = proj
        drawHorizon(proj, measurer)
        drawTrack(proj, state.track)
        state.day?.let { day ->
            day.sunriseAzimuth?.let { drawHorizonEvent(proj, measurer, icons, it, fmt.time(day.sunrise)) }
            day.sunsetAzimuth?.let { drawHorizonEvent(proj, measurer, icons, it, fmt.time(day.sunset)) }
        }
        drawHours(proj, measurer, icons, state.hours, fmt)
        sunNow?.let { drawSunNow(proj, icons, it) }
        // Within a few minutes of now, the sun marker already shows it.
        val nearNow = sunNow != null && abs(Duration.between(state.now, selectedTime).toMinutes()) < 10
        if (!nearNow) drawSelected(proj, measurer, selected, selectedTime.format(fmt.time))
    }
}

internal val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
internal val smallLabel = labelStyle.copy(fontSize = 10.sp)
internal val shadowColor = Color(0xCC000000)

/** Text with a dark shadow so it reads over sky and buildings alike. */
internal fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle = labelStyle) {
    // Measured without limits and drawn as layouts: drawing by text constrains it to the
    // space left on the canvas, which throws for labels partly past the edge.
    val layout = measurer.measure(text, style)
    val w = layout.size.width
    val h = layout.size.height
    if (at.x > size.width || at.y > size.height || at.x + w < 0 || at.y + h < 0) return
    val shadow = measurer.measure(text, style.copy(color = shadowColor))
    drawText(shadow, topLeft = at + Offset(1.dp.toPx(), 1.dp.toPx()))
    drawText(layout, topLeft = at)
}

/** Text centred on [center]. */
internal fun DrawScope.centredLabel(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle = labelStyle) {
    val s = measurer.measure(text, style).size
    label(measurer, text, Offset(center.x - s.width / 2f, center.y - s.height / 2f), style)
}

/** An icon [sizePx] across, centred on [center], tinted [tint]. */
internal fun DrawScope.icon(p: Painter, center: Offset, sizePx: Float, tint: Color) {
    translate(center.x - sizePx / 2, center.y - sizePx / 2) {
        with(p) { draw(GeoSize(sizePx, sizePx), colorFilter = ColorFilter.tint(tint)) }
    }
}

private fun DrawScope.onScreen(x: Float, y: Float, margin: Float = 0f) =
    x >= -margin && x <= size.width + margin && y >= -margin && y <= size.height + margin

/** The horizon with compass points along it (true bearings): N, E, S, W large, the rest small. */
private fun DrawScope.drawHorizon(proj: SkyProjection, measurer: TextMeasurer) {
    val line = Color(0x99FFFFFF)
    var prev: Offset? = null
    for (az in 0..360 step 3) {
        val p = proj.project(az.toDouble(), 0.0)
        val here = if (p.inFront) Offset(p.x, p.y) else null
        if (prev != null && here != null) drawLine(line, prev, here, strokeWidth = 1.dp.toPx())
        prev = here
    }
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    for (az in 0 until 360 step 15) {
        val p = proj.project(az.toDouble(), 0.0)
        if (!p.inFront || !onScreen(p.x, p.y)) continue
        val cardinal = az % 90 == 0
        val major = az % 45 == 0
        val tick = (if (major) 8 else 4).dp.toPx()
        drawLine(line, Offset(p.x, p.y - tick), Offset(p.x, p.y + tick), strokeWidth = 1.dp.toPx())
        if (major) {
            val style = if (cardinal) labelStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold) else smallLabel
            centredLabel(measurer, names[az / 45], Offset(p.x, p.y + 20.dp.toPx()), style)
        }
    }
}

/** The day's path, coloured by light: daylight, golden hour, blue hour; dashed below the horizon. */
private fun DrawScope.drawTrack(proj: SkyProjection, track: List<SunPos>) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
    for (i in 1 until track.size) {
        val a = track[i - 1]
        val b = track[i]
        val mid = (a.altitude + b.altitude) / 2
        if (mid < Sun.BLUE_BOTTOM - 4) continue
        val pa = proj.project(a.azimuth, a.altitude)
        val pb = proj.project(b.azimuth, b.altitude)
        if (!pa.inFront || !pb.inFront) continue
        val above = mid >= 0
        drawLine(
            phaseColor(Sun.phase(mid)), Offset(pa.x, pa.y), Offset(pb.x, pb.y),
            strokeWidth = (if (above) 3 else 2).dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (above) null else dash,
        )
    }
}

internal fun phaseColor(p: Phase): Color = when (p) {
    Phase.DAY -> SunAmber
    Phase.GOLDEN -> GoldenOrange
    Phase.BLUE -> BlueHour
    Phase.NIGHT -> Color(0x99FFFFFF)
}

/** Sunrise or sunset: the twilight icon on the horizon with its time under it. */
private fun DrawScope.drawHorizonEvent(proj: SkyProjection, measurer: TextMeasurer, icons: OverlayIcons, azimuth: Double, time: String) {
    val p = proj.project(azimuth, 0.0)
    if (!p.inFront || !onScreen(p.x, p.y, 100f)) return
    val c = Offset(p.x, p.y - 16.dp.toPx())
    drawCircle(shadowColor, 15.dp.toPx(), c)
    icon(icons.twilight, c, 22.dp.toPx(), GoldenOrange)
    centredLabel(measurer, time, Offset(p.x, p.y - 40.dp.toPx()), smallLabel)
}

/** Each hour the sun is up, as its forecast sky's icon (a plain dot past the forecast). */
private fun DrawScope.drawHours(proj: SkyProjection, measurer: TextMeasurer, icons: OverlayIcons, hours: List<HourRow>, fmt: TimeFormats) {
    for (h in hours) {
        if (h.sun.altitude < -2) continue
        val p = proj.project(h.sun.azimuth, h.sun.altitude)
        if (!p.inFront || !onScreen(p.x, p.y, 40f)) continue
        val c = Offset(p.x, p.y)
        val sky = h.sky
        if (sky != null) {
            drawCircle(shadowColor, 14.dp.toPx(), c)
            icon(icons.sky.getValue(sky), c, 20.dp.toPx(), skyTint(sky))
        } else {
            drawCircle(shadowColor, 7.dp.toPx(), c)
            drawCircle(Color(0xFFDDDDDD), 5.dp.toPx(), c)
        }
        label(measurer, h.time.format(fmt.shortHour), Offset(p.x + 15.dp.toPx(), p.y - 7.dp.toPx()), smallLabel)
    }
}

/** The sun now: a glowing disc, or an arrow at the screen edge pointing to it. */
private fun DrawScope.drawSunNow(proj: SkyProjection, icons: OverlayIcons, sun: SunPos) {
    val p = proj.project(sun.azimuth, sun.altitude)
    if (p.inFront && onScreen(p.x, p.y)) {
        val c = Offset(p.x, p.y)
        drawCircle(SunAmber.copy(alpha = 0.25f), 22.dp.toPx(), c)
        drawCircle(SunAmber, 11.dp.toPx(), c)
        drawCircle(Color(0xFFFFF3D0), 6.dp.toPx(), c)
    } else {
        drawEdgeArrow(proj, sun, SunAmber) { at -> icon(icons.sun, at, 20.dp.toPx(), SunAmber) }
    }
}

/** The time on the slider: a white ring, or an edge arrow with the time. */
private fun DrawScope.drawSelected(proj: SkyProjection, measurer: TextMeasurer, pos: SunPos, time: String) {
    val p = proj.project(pos.azimuth, pos.altitude)
    if (p.inFront && onScreen(p.x, p.y)) {
        drawCircle(shadowColor, 15.dp.toPx(), Offset(p.x, p.y), style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 15.dp.toPx(), Offset(p.x, p.y), style = Stroke(2.5.dp.toPx()))
    } else {
        drawEdgeArrow(proj, pos, Color.White) { at -> centredLabel(measurer, time, at) }
    }
}

/** An arrow at the screen edge pointing towards [pos], with [badge] drawn just inside it. */
private fun DrawScope.drawEdgeArrow(proj: SkyProjection, pos: SunPos, color: Color, badge: DrawScope.(Offset) -> Unit) {
    val (dx, dy) = proj.screenDirection(pos.azimuth, pos.altitude)
    val inset = 30.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    // Where a ray from the centre meets the inset edge.
    val tx = if (abs(dx) > 1e-4f) (cx - inset) / abs(dx) else Float.MAX_VALUE
    val ty = if (abs(dy) > 1e-4f) (cy - inset) / abs(dy) else Float.MAX_VALUE
    val t = min(tx, ty)
    val tip = Offset(cx + dx * t, cy + dy * t)
    val len = 16.dp.toPx()
    val back = Offset(tip.x - dx * len, tip.y - dy * len)
    val side = Offset(-dy, dx) * (len * 0.6f)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + side.x, back.y + side.y)
        lineTo(back.x - side.x, back.y - side.y)
        close()
    }
    drawPath(path, shadowColor, style = Stroke(3.dp.toPx()))
    drawPath(path, color)
    badge(Offset(back.x - dx * 18.dp.toPx(), back.y - dy * 18.dp.toPx()))
}

/**
 * Top left: a crosshair button to line up on the sun (green once lined up, with undo
 * beside it), and the compass's state as an icon, with words only when it needs a figure 8.
 */
@Composable
private fun CompassControls(
    available: Boolean,
    reading: OrientationReading?,
    aligned: Boolean,
    aligning: Boolean,
    canAlign: Boolean,
    onAlign: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (available && canAlign) {
            RoundButton(
                if (aligning) Icons.Filled.Close else Icons.Filled.GpsFixed,
                if (aligning) "Cancel" else "Line up on the sun",
                tint = when {
                    aligning -> Color.White
                    aligned -> GoodGreen
                    else -> Color.White
                },
                onClick = onAlign,
            )
        }
        if (aligned && !aligning) RoundButton(Icons.Filled.Restore, "Undo lining up", Color.White, onReset)
        when {
            !available -> CompassChip(WarnAmber, "No compass")
            aligned || aligning -> Unit
            reading?.unreliable == true -> CompassChip(WarnAmber, "Figure 8")
            reading?.headingErrorDeg != null && reading.headingErrorDeg >= 1f ->
                CompassChip(Color.White, "±${reading.headingErrorDeg.roundToInt()}°")
        }
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Scrim, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun CompassChip(color: Color, text: String) {
    Row(
        Modifier.background(Scrim, RoundedCornerShape(22.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Explore, contentDescription = "Compass", tint = color, modifier = Modifier.size(20.dp))
        Text(" $text", color = color, fontSize = 13.sp)
    }
}

/**
 * The chosen time with the sky then (as an icon, with rain, cloud and temperature), where
 * the sun is (a compass dial and a height gauge), a slider painted with each hour's sky,
 * and the date.
 */
@Composable
internal fun TimePanel(state: SunUiState, vm: SunViewModel, fmt: TimeFormats, sel: Selection) {
    val minute = sel.minute
    val onMinute = sel.setMinute
    val defaultMinute = sel.defaultMinute
    val selected = sel.pos
    val hour = sel.hour
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sel.time.format(fmt.time), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (minute != defaultMinute) {
                    IconButton(onClick = { onMinute(defaultMinute) }) {
                        Icon(Icons.Filled.Restore, contentDescription = if (state.date == state.today) "Back to now" else "Back to noon")
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            SkyBadge(hour)
            Spacer(Modifier.width(12.dp))
            Dial(Sun.compassPoint(selected.azimuth)) { drawCompassDial(selected.azimuth) }
            Spacer(Modifier.width(8.dp))
            Dial("${selected.altitude.roundToInt()}°") { drawHeightGauge(selected.altitude) }
        }
        DaySlider(state, minute, onMinute)
        DateRow(state, vm, fmt)
    }
}

/** The hour's sky as a big icon, with rain chance, cloud and temperature stacked beside it. */
@Composable
private fun SkyBadge(hour: HourRow?) {
    val sky = hour?.sky ?: return
    val w = hour.weather
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(skyIcon(sky), contentDescription = sky.label, tint = skyTint(sky), modifier = Modifier.size(34.dp))
        Column(Modifier.padding(start = 6.dp)) {
            w?.precipProbability?.takeIf { it >= 10 }?.let { Stat(Icons.Filled.WaterDrop, "$it%", Color(0xFF7FB0DD)) }
            w?.cloudCover?.let { Stat(Icons.Filled.Cloud, "$it%", Color(0xFFB0B4C0)) }
            w?.temperature?.let { Stat(Icons.Filled.Thermostat, "${it.roundToInt()}°", Color(0xFFB0B4C0)) }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(" $text", fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small round gauge with a caption under it. */
@Composable
private fun Dial(caption: String, draw: DrawScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(36.dp), onDraw = draw)
        Text(caption, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A compass rose with north up and an arrow towards the sun's bearing. */
private fun DrawScope.drawCompassDial(azimuth: Double) {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Color(0xFF3A3A3E), r, c, style = Stroke(1.5.dp.toPx()))
    // North tick.
    drawLine(Color(0xFFECECEC), Offset(c.x, c.y - r), Offset(c.x, c.y - r + 5.dp.toPx()), strokeWidth = 2.dp.toPx())
    val a = Math.toRadians(azimuth)
    val tip = Offset(c.x + (r - 3.dp.toPx()) * sin(a).toFloat(), c.y - (r - 3.dp.toPx()) * cos(a).toFloat())
    drawLine(SunAmber, c, tip, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(SunAmber, 3.5.dp.toPx(), tip)
}

/** A quarter circle from the horizon to straight up, with the sun at its height. */
private fun DrawScope.drawHeightGauge(altitude: Double) {
    val r = size.minDimension - 4.dp.toPx()
    val o = Offset(2.dp.toPx(), size.height - 2.dp.toPx())
    drawLine(Color(0xFF3A3A3E), o, Offset(o.x + r, o.y), strokeWidth = 1.5.dp.toPx())
    drawArc(
        Color(0xFF3A3A3E), startAngle = -90f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(o.x - r, o.y - r), size = GeoSize(2 * r, 2 * r), style = Stroke(1.5.dp.toPx()),
    )
    val a = Math.toRadians(altitude.coerceIn(-10.0, 90.0))
    val tip = Offset(o.x + r * cos(a).toFloat(), o.y - r * sin(a).toFloat())
    val color = if (altitude > 0) SunAmber else Color(0xFF8D8D92)
    drawLine(color, o, tip, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(color, 3.5.dp.toPx(), tip)
}

/**
 * A slider across the day whose track is painted with each hour's sky (light phases where
 * there's no forecast), with sunrise and sunset ticked, so the day's weather reads at a glance.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySlider(state: SunUiState, minute: Int, onMinute: (Int) -> Unit) {
    val dayStart = state.date.atStartOfDay(state.zone)
    val day = state.day
    Slider(
        value = minute.toFloat(),
        onValueChange = { onMinute(it.roundToInt()) },
        valueRange = 0f..(24 * 60 - 1).toFloat(),
        modifier = Modifier.padding(top = 4.dp),
        thumb = {
            Box(
                Modifier.size(width = 8.dp, height = 34.dp)
                    .border(1.dp, Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(1.dp)
                    .background(Color.White, RoundedCornerShape(3.dp)),
            )
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val corner = CornerRadius(6.dp.toPx())
                drawRoundRect(Color(0xFF1E2230), size = size, cornerRadius = corner)
                val perMin = size.width / (24 * 60)
                state.hours.forEach { h ->
                    val start = Duration.between(dayStart, h.time).toMinutes()
                    val color = h.sky?.let { skyColor(it) } ?: when (h.phase) {
                        Phase.DAY -> Color(0xFF55555C)
                        Phase.GOLDEN -> GoldenOrange.copy(alpha = 0.6f)
                        Phase.BLUE -> BlueHour.copy(alpha = 0.6f)
                        Phase.NIGHT -> Color(0xFF1E2230)
                    }
                    drawRect(color, Offset(start * perMin + 0.5f, 0f), GeoSize(60 * perMin - 1f, size.height))
                }
                // Golden and blue hour as a thin band along the top.
                state.track.forEachIndexed { i, p ->
                    val ph = Sun.phase(p.altitude)
                    if (ph == Phase.GOLDEN || ph == Phase.BLUE) {
                        val x = i * SunUiState.CURVE_STEP_MIN * perMin
                        drawRect(phaseColor(ph), Offset(x, 0f), GeoSize(SunUiState.CURVE_STEP_MIN * perMin, 3.dp.toPx()))
                    }
                }
                listOfNotNull(day?.sunrise, day?.sunset).forEach { t ->
                    val x = Duration.between(dayStart, t).toMinutes() * perMin
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
        },
    )
}
