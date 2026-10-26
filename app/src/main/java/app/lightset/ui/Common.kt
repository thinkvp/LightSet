package app.lightset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lightset.LightRig
import kotlin.math.abs
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
        Text(if (name.isBlank()) label else "$name · $label", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One light parameter: label and value (tap the value to type one), a slider, and
 * − / + for fine steps. [toSlider]/[fromSlider] map the value onto 0..1, e.g. so CCT
 * moves evenly in mired rather than kelvin.
 */
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
) {
    var editing by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                Modifier.clickable(enabled = enabled) { editing = true }.padding(vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
            }
            Slider(
                value = toSlider(value).coerceIn(0f, 1f),
                onValueChange = { onChange(fromSlider(it).coerceIn(range)) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label")
            }
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
