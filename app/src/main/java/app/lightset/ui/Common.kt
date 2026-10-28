package app.lightset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lightset.LightRig
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

val GoodGreen = Color(0xFF66BB6A)
val WarnAmber = Color(0xFFFFB74D)

fun formatKelvin(k: Double): String = "%,d K".format((k / 10).roundToInt() * 10)

/** Duv ×1000, the scale people tend to quote tint in. */
fun formatTint(duv: Double): String {
    val t = duv * 1000
    return when {
        abs(t) < 0.5 -> "neutral"
        t > 0 -> "%.1f G".format(t)
        else -> "%.1f M".format(-t)
    }
}

/** Coloured dot and label for the light connection; tap to retry when offline. */
@Composable
fun LightStatusChip(
    name: String,
    status: LightRig.Status,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    offlineLabel: String = "Offline · tap to retry",
) {
    val (color, label) = when (status) {
        LightRig.Status.ONLINE -> GoodGreen to "Online"
        LightRig.Status.CONNECTING -> WarnAmber to "Connecting…"
        LightRig.Status.OFFLINE -> Color(0xFF9E9E9E) to offlineLabel
    }
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .clickable(enabled = status == LightRig.Status.OFFLINE, onClick = onRetry)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(if (name.isBlank()) label else "$name · $label", style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One light parameter on a single row: label over value (tap the value to type one),
 * then − / slider / +. [toSlider]/[fromSlider] map the value onto 0..1, e.g. so CCT
 * moves evenly in mired rather than kelvin. A [track] brush paints the slider's track
 * with what it sets (the hue spectrum, say), so it needs no separate legend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValueControl(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    toSlider: (Int) -> Float = { (it - range.first).toFloat() / (range.last - range.first) },
    fromSlider: (Float) -> Int = { (range.first + it * (range.last - range.first)).roundToInt() },
    track: Brush? = null,
) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(72.dp).clickable(enabled = enabled) { editing = true }) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(format(value), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp))
        }
        val position = toSlider(value).coerceIn(0f, 1f)
        val change: (Float) -> Unit = { onChange(fromSlider(it).coerceIn(range)) }
        if (track == null) {
            Slider(value = position, onValueChange = change, enabled = enabled, modifier = Modifier.weight(1f))
        } else {
            Slider(
                value = position,
                onValueChange = change,
                enabled = enabled,
                modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f),
                thumb = {
                    Box(
                        Modifier.size(22.dp)
                            .border(1.dp, Color(0x99000000), CircleShape)
                            .padding(1.dp)
                            .border(3.dp, Color.White, CircleShape)
                    )
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(12.dp).background(track, RoundedCornerShape(6.dp)))
                },
            )
        }
        IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label", modifier = Modifier.size(18.dp))
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '-' }.take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("${range.first} to ${range.last}") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
                    editing = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** A small preset button filled with the colour it sets. */
@Composable
fun SwatchButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(30.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(color, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1, color = if (color.luminance() > 0.4f) Color.Black else Color.White)
    }
}

/** Approximate display colour of a blackbody at [k] kelvin (Tanner Helland's fit). */
fun kelvinColor(k: Int): Color {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return Color((r / 255).toFloat().coerceIn(0f, 1f), (g / 255).toFloat().coerceIn(0f, 1f), (b / 255).toFloat().coerceIn(0f, 1f))
}

/** Warm-to-cool track for a CCT slider, matching its mired spacing. */
fun cctTrack(range: IntRange): Brush =
    Brush.horizontalGradient((0..8).map { kelvinColor(sliderToCct(it / 8f, range)) })

/** Magenta through neutral to green, with neutral at G/M 0. */
fun gmTrack(range: IntRange): Brush {
    val zero = (-range.first).toFloat() / (range.last - range.first)
    return Brush.horizontalGradient(
        0f to Color(0xFFD45FD4), zero.coerceIn(0.01f, 0.99f) to Color(0xFFEEEEEE), 1f to Color(0xFF5FBF5F),
    )
}

/** Dark to full white, for a brightness slider. */
val brightnessTrack: Brush = Brush.horizontalGradient(listOf(Color(0xFF202020), Color.White))

/** The hue spectrum at full saturation. */
val hueTrack: Brush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })

/** White to the fully saturated [hue]. */
fun satTrack(hue: Int): Brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue.toFloat(), 1f, 1f)))

/** CCT slider position, even in mired (perceptually even) from warm (left) to cool. */
fun cctToSlider(k: Int, range: IntRange): Float {
    val warm = 1e6 / range.first
    val cool = 1e6 / range.last
    return ((warm - 1e6 / k) / (warm - cool)).toFloat()
}

fun sliderToCct(v: Float, range: IntRange): Int {
    val warm = 1e6 / range.first
    val cool = 1e6 / range.last
    val k = 1e6 / (warm - v * (warm - cool))
    return (k / 10).roundToInt() * 10
}

/** [kelvinColor] pulled toward green or magenta by [duv], to show a reading's tint. */
fun tintedColor(cct: Double, duv: Double): Color {
    val base = kelvinColor(cct.roundToInt())
    val toward = if (duv > 0) Color(0xFF4CAF50) else Color(0xFFD040D0)
    return lerp(base, toward, (abs(duv) * 25).toFloat().coerceAtMost(0.6f))
}

/** Track for showing tint (Duv) from [TINT_SPAN] magenta to [TINT_SPAN] green. */
val tintTrack: Brush = Brush.horizontalGradient(listOf(Color(0xFFD45FD4), Color(0xFFEEEEEE), Color(0xFF5FBF5F)))
const val TINT_SPAN = 0.02

/** Where [duv] sits on [tintTrack], 0..1. */
fun tintPosition(duv: Double): Float = ((duv / TINT_SPAN + 1) / 2).toFloat().coerceIn(0f, 1f)

/**
 * A colour bar with a reading on it as a ringed dot, and optionally a reference as a
 * notch above the bar, so how far apart they are reads at a glance.
 */
@Composable
fun ColourGauge(track: Brush, value: Float?, reference: Float?, modifier: Modifier = Modifier) {
    val notch = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val barH = 8.dp.toPx()
        val cy = 14.dp.toPx()
        drawRoundRect(track, topLeft = Offset(0f, cy - barH / 2), size = Size(size.width, barH),
            cornerRadius = CornerRadius(barH / 2))
        reference?.let { r ->
            val x = r.coerceIn(0f, 1f) * size.width
            val half = 5.dp.toPx()
            drawPath(Path().apply {
                moveTo(x - half, 0f)
                lineTo(x + half, 0f)
                lineTo(x, cy - barH / 2)
                close()
            }, notch)
        }
        value?.let { v ->
            val c = Offset(v.coerceIn(0f, 1f) * size.width, cy)
            drawCircle(Color(0x99000000), radius = 7.dp.toPx(), center = c)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
        }
    }
}

/** A small rounded label filled with [color]. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 12.sp,
        maxLines = 1,
        color = if (color.luminance() > 0.4f) Color.Black else Color.White,
    )
}
