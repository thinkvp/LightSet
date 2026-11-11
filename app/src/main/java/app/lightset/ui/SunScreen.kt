package app.lightset.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lightset.sun.HourRow
import app.lightset.sun.OpenMeteo
import app.lightset.sun.Phase
import app.lightset.sun.Sky
import app.lightset.sun.Sun
import app.lightset.sun.SunDay
import app.lightset.sun.Window
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

val SunAmber = Color(0xFFFFC94D)
val BlueHour = Color(0xFF7090E0)

@Composable
fun SunScreen(vm: SunViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Each time the tab is shown or the app comes back: re-check permission, place and forecast.
    LifecycleResumeEffect(Unit) {
        vm.onShown()
        onPauseOrDispose { }
    }
    // Opening the tab starts on the camera.
    LaunchedEffect(Unit) { vm.setMode(SunMode.CAMERA) }
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val fmt = remember(is24h) { TimeFormats(is24h) }
    Column(Modifier.fillMaxSize()) {
        TabHeader(Modifier.padding(start = 16.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.shownPlace?.label ?: "Sun",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusLine(state, fmt)
            }
            // A pin is showing: back to where the phone is.
            if (state.usingPin) {
                IconButton(onClick = vm::clearPin) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Back to my location")
                }
            }
            if (state.hasPermission || state.usingPin) {
                if (state.locating || state.shownFetching) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Find location and update the forecast")
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                state.mode == SunMode.MAP -> SunMap(state, vm, fmt)
                !state.hasPermission && state.place == null -> LocationPermission(vm)
                state.mode == SunMode.CAMERA -> SunCamera(state, vm, fmt)
                else -> DayView(state, vm, fmt)
            }
        }
        HorizontalDivider()
        ModeBar(state.mode, vm::setMode)
    }
}

@Composable
private fun DayView(state: SunUiState, vm: SunViewModel, fmt: TimeFormats) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasPermission) LocationPermission(vm, compact = true)
        DateRow(state, vm, fmt)
        val day = state.day
        if (day == null) {
            Text(
                if (state.locating) "Finding location…" else "No location yet",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        SunTimesCard(day, fmt)
        DayGraph(state)
        ForecastNote(state)
        HourList(state, fmt)
        Text(
            "Weather data by Open-Meteo.com (CC BY 4.0)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Camera (the sun's path over the live view) or Day (times and forecast), along the bottom
 * like the Lights tab's scene buttons. The one showing is filled.
 */
@Composable
private fun ModeBar(mode: SunMode, onMode: (SunMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeButton(Icons.Filled.CameraAlt, "Camera", mode == SunMode.CAMERA, { onMode(SunMode.CAMERA) }, Modifier.weight(1f))
        ModeButton(Icons.Filled.Schedule, "Day", mode == SunMode.DAY, { onMode(SunMode.DAY) }, Modifier.weight(1f))
        ModeButton(Icons.Filled.Map, "Map", mode == SunMode.MAP, { onMode(SunMode.MAP) }, Modifier.weight(1f))
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(" $label", maxLines = 1)
    }
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    if (selected) Button(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding, content = content)
}

/** Formats times in the phone's 12 or 24 hour style. */
internal class TimeFormats(is24h: Boolean) {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a")
    val hour: DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h a")
    val shortHour: DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24h) "HH" else "ha")
    val dayTime: DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24h) "EEE HH:mm" else "EEE h:mm a")
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

    fun time(t: ZonedDateTime?): String = t?.format(time) ?: "—"
    fun window(w: Window): String = if (w.isEmpty) "—" else "${time(w.start)}–${time(w.end)}"
}

@Composable
private fun StatusLine(state: SunUiState, fmt: TimeFormats) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when {
        state.locating -> "Finding location…" to muted
        state.shownFetching -> "Getting forecast…" to muted
        state.shownForecastError != null -> state.shownForecastError!! to WarnAmber
        state.locationError != null -> state.locationError to WarnAmber
        state.shownForecast != null -> {
            val at = state.shownForecast!!.fetchedAt.atZone(state.zone)
            "Forecast from " + (if (at.toLocalDate() == state.today) at.format(fmt.time) else at.format(fmt.dayTime)) to muted
        }
        else -> "" to muted
    }
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LocationPermission(vm: SunViewModel, compact: Boolean = false) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        vm.onPermissionResult(ok)
    }
    Column(
        if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            if (compact) "Showing the last place. Allow location to update it."
            else "The Sun tab uses your approximate location for sun times and the weather forecast.",
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        )
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Allow location") }
            // Once refused, Android may not ask again; Settings is the way back.
            if (denied) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Open settings") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRow(state: SunUiState, vm: SunViewModel, fmt: TimeFormats) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.shiftDate(-1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day") }
        Text(
            when (state.date) {
                state.today -> "Today, " + state.date.format(fmt.date)
                state.today.plusDays(1) -> "Tomorrow, " + state.date.format(fmt.date)
                else -> state.date.format(fmt.date)
            },
            Modifier.clickable { picking = true }.padding(horizontal = 4.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = { vm.shiftDate(1) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next day") }
        Spacer(Modifier.weight(1f))
        if (state.date != state.today) TextButton(onClick = { vm.setDate(state.today) }) { Text("Today") }
    }
    if (picking) {
        // The picker works in UTC midnights.
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.date.toEpochDay() * DAY_MS)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { vm.setDate(LocalDate.ofEpochDay(it / DAY_MS)) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

@Composable
private fun SunTimesCard(day: SunDay, fmt: TimeFormats) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            day.alwaysUp -> Text("The sun stays up all day", style = MaterialTheme.typography.titleMedium)
            day.alwaysDown -> Text("The sun stays down all day", style = MaterialTheme.typography.titleMedium)
            else -> Row {
                SunEvent("Sunrise", day.sunrise, day.sunriseAzimuth, fmt, Modifier.weight(1f))
                SunEvent("Sunset", day.sunset, day.sunsetAzimuth, fmt, Modifier.weight(1f))
            }
        }
        if (!day.alwaysDown && day.noon != null) {
            InfoRow("Solar noon", "${fmt.time(day.noon)} · ${day.noonAltitude.roundToInt()}° high")
        }
        day.dayLength?.let { InfoRow("Daylight", formatDuration(it)) }
        InfoRow("Golden hour", "${fmt.window(day.goldenMorning)} · ${fmt.window(day.goldenEvening)}", SunAmber)
        InfoRow("Blue hour", "${fmt.window(day.blueMorning)} · ${fmt.window(day.blueEvening)}", BlueHour)
    }
}

@Composable
private fun SunEvent(label: String, time: ZonedDateTime?, azimuth: Double?, fmt: TimeFormats, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmt.time(time), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        azimuth?.let {
            Text(
                "${Sun.compassPoint(it)} ${it.roundToInt()}°",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, dot: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp)) {
            if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        }
        Text(label, Modifier.width(112.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDuration(d: Duration): String {
    val mins = d.toMinutes()
    return "${mins / 60} h ${"%02d".format(mins % 60)} min"
}

/**
 * The sun's height through the day (amber above the horizon), with golden and blue hour
 * shaded, "now" marked, and under it a strip with each hour's sky from the forecast.
 */
@Composable
private fun DayGraph(state: SunUiState) {
    val curve = state.curve
    if (curve.size < 2) return
    val horizonColor = MaterialTheme.colorScheme.outline
    val belowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    val dayStart = state.date.atStartOfDay(state.zone)
    val nowFraction = if (state.date == state.today) {
        Duration.between(dayStart, state.now).toMinutes() / (24f * 60)
    } else null
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val top = max(curve.max(), 15.0) + 5
            val bottom = -20.0
            fun y(alt: Double) = ((top - alt) / (top - bottom) * size.height).toFloat()
            fun x(i: Int) = i.toFloat() / (curve.size - 1) * size.width
            val dx = size.width / (curve.size - 1)
            // Golden and blue hour as bands behind the curve.
            curve.forEachIndexed { i, alt ->
                val band = when (Sun.phase(alt)) {
                    Phase.GOLDEN -> SunAmber.copy(alpha = 0.16f)
                    Phase.BLUE -> BlueHour.copy(alpha = 0.2f)
                    else -> null
                }
                if (band != null) drawRect(band, Offset(x(i) - dx / 2, 0f), Size(dx, size.height))
            }
            val horizon = y(0.0)
            drawLine(horizonColor, Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = 1.dp.toPx())
            val path = Path().apply {
                curve.forEachIndexed { i, alt -> if (i == 0) moveTo(x(i), y(alt)) else lineTo(x(i), y(alt)) }
            }
            clipRect(bottom = horizon) {
                drawPath(path, SunAmber, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            clipRect(top = horizon) {
                drawPath(path, belowColor.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
            }
            nowFraction?.takeIf { it in 0f..1f }?.let { f ->
                val nx = f * size.width
                val pos = f * (curve.size - 1)
                val i = pos.toInt().coerceAtMost(curve.size - 2)
                val alt = curve[i] + (curve[i + 1] - curve[i]) * (pos - i)
                drawLine(nowColor.copy(alpha = 0.35f), Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(Color(0x99000000), 6.dp.toPx(), Offset(nx, y(alt)))
                drawCircle(if (alt > 0) SunAmber else nowColor, 4.dp.toPx(), Offset(nx, y(alt)))
            }
        }
        // Each hour's sky, lined up under the graph.
        if (state.hours.any { it.sky != null }) {
            Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(10.dp)) {
                val hourW = size.width / 24
                state.hours.forEach { h ->
                    val sky = h.sky ?: return@forEach
                    val start = Duration.between(dayStart, h.time).toMinutes() / 60f
                    drawRect(skyColor(sky), Offset(start * hourW, 0f), Size(hourW - 1.dp.toPx(), size.height))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach {
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ForecastNote(state: SunUiState) {
    if (state.shownForecast == null || state.dateInForecast) return
    Text(
        if (state.date.isBefore(state.today)) "No forecast for past days."
        else "No forecast this far ahead (it covers ${OpenMeteo.DAYS} days).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HourList(state: SunUiState, fmt: TimeFormats) {
    // From the start of blue hour to its end in the evening; the whole day if it never gets that light.
    val shown = state.hours.filter { it.sun.altitude >= Sun.BLUE_BOTTOM - 1 }.ifEmpty { state.hours }
    val nowHour = state.now.withMinute(0).withSecond(0).withNano(0)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        shown.forEach { HourItem(it, current = it.time.isEqual(nowHour), fmt) }
    }
}

@Composable
private fun HourItem(h: HourRow, current: Boolean, fmt: TimeFormats) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (current) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Golden or blue hour, as a stripe down the left edge.
        Box(
            Modifier.fillMaxHeight().width(3.dp).background(
                when (h.phase) {
                    Phase.GOLDEN -> SunAmber
                    Phase.BLUE -> BlueHour
                    else -> Color.Transparent
                },
            ),
        )
        Text(h.time.format(fmt.hour), Modifier.padding(start = 9.dp).width(54.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (current) FontWeight.SemiBold else null)
        val sky = h.sky
        Box(Modifier.width(30.dp)) {
            if (sky != null) Icon(skyIcon(sky), contentDescription = null, tint = skyTint(sky), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(sky?.label ?: if (h.sun.altitude > 0) "Sun up" else "Sun down", style = MaterialTheme.typography.bodyMedium,
                color = if (sky == null) muted else Color.Unspecified)
            WeatherIcons(h)
        }
        // Wind: which way it blows, its speed, and gusts (amber when they'd trouble a stand).
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
            val w = h.weather
            val speed = w?.windSpeed
            if (speed != null) {
                // The whole entry goes amber when gusts reach the limit, so it's not read as the speed's.
                val gusty = (w.windGusts ?: speed) >= GUSTY_KMH
                val main = if (gusty) WarnAmber else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically) {
                    w.windDirection?.let { WindArrow(it, main, 16.dp) }
                    Text(" ${speed.roundToInt()} km/h", style = MaterialTheme.typography.bodyMedium, color = main,
                        maxLines = 1, softWrap = false)
                }
                w.windGusts?.let { g ->
                    Text("gusts ${g.roundToInt()}", style = MaterialTheme.typography.bodySmall,
                        color = if (gusty) WarnAmber else muted, maxLines = 1)
                }
            }
        }
        Text(
            h.weather?.temperature?.let { "${it.roundToInt()}°" } ?: "",
            Modifier.width(38.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
        )
    }
}

/** Gusts from this (km/h) are enough to worry about stands, frames and wind noise. */
internal const val GUSTY_KMH = 40.0

/** An arrow pointing the way the wind blows, for wind coming from [from]° (north 0). */
@Composable
internal fun WindArrow(from: Double, tint: Color, size: Dp) {
    Icon(
        Icons.Filled.Navigation,
        contentDescription = "Wind from the ${Sun.compassPoint(from)}",
        tint = tint,
        modifier = Modifier.size(size).rotate((from + 180).toFloat()),
    )
}

/** The chance of rain (if it's worth saying) and cloud cover, each as an icon and a number. */
@Composable
private fun WeatherIcons(h: HourRow) {
    val w = h.weather ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        w.precipProbability?.takeIf { it >= 10 }?.let { IconValue(Icons.Filled.WaterDrop, "Chance of rain", "$it%", RainBlue, muted) }
        w.cloudCover?.let { IconValue(Icons.Filled.Cloud, "Cloud cover", "$it%", Color(0xFFB0B4C0), muted) }
    }
}

private val RainBlue = Color(0xFF7FB0DD)

@Composable
private fun IconValue(icon: ImageVector, description: String, value: String, tint: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
        Text(" $value", style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1)
    }
}

internal fun skyIcon(sky: Sky): ImageVector = when (sky) {
    Sky.SUN -> Icons.Filled.WbSunny
    Sky.HAZY -> Icons.Filled.WbCloudy
    Sky.OVERCAST, Sky.CLOUDY -> Icons.Filled.Cloud
    Sky.CLEAR -> Icons.Filled.NightsStay
    Sky.PARTLY -> Icons.Filled.CloudQueue
    Sky.FOG -> Icons.Filled.Dehaze
    Sky.DRIZZLE -> Icons.Filled.Grain
    Sky.RAIN -> Icons.Filled.Umbrella
    Sky.SNOW -> Icons.Filled.AcUnit
    Sky.STORM -> Icons.Filled.Thunderstorm
}

internal fun skyTint(sky: Sky): Color = when (sky) {
    Sky.SUN, Sky.HAZY -> SunAmber
    else -> skyColor(sky).let { if (it.red + it.green + it.blue < 0.6f) Color(0xFFB0B4C0) else it }
}

/** The strip colour for each sky: amber for sun, greys for cloud, blue for rain. */
internal fun skyColor(sky: Sky): Color = when (sky) {
    Sky.SUN -> SunAmber
    Sky.HAZY -> Color(0xFFB09858)
    Sky.OVERCAST -> Color(0xFF6B6B72)
    Sky.CLEAR -> Color(0xFF283250)
    Sky.PARTLY -> Color(0xFF383C4C)
    Sky.CLOUDY -> Color(0xFF48484F)
    Sky.FOG -> Color(0xFF9A9AA0)
    Sky.DRIZZLE -> Color(0xFF7FB0DD)
    Sky.RAIN -> Color(0xFF4A86D8)
    Sky.SNOW -> Color(0xFFE0E6F0)
    Sky.STORM -> Color(0xFF9575CD)
}
