package app.lightset.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lightset.ble.Im150
import app.lightset.exposure.Exposure
import app.lightset.exposure.ExposureParam
import app.lightset.exposure.ExposureSettings
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Exposure calculator pinned to the bottom of Match: the light (IM150 lux, held, or
 * set by hand) and the camera settings. Locked settings are kept and the rest are
 * worked out; whatever can't be reached is shown as light to add or cut.
 */
@Composable
fun ExposureBar(state: ExposureUiState, vm: ExposureViewModel) {
    val r = state.result
    val s = state.shown
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.setExpanded(!state.expanded) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Exposure", style = MaterialTheme.typography.labelMedium, color = muted)
                Text(
                    "${fmtAperture(s.fNumber)} · ISO ${fmtIso(s.iso)} · ${fmtNum(s.angle)}° @ ${fmtNum(nearestFps(s.fps))} · ${fmtNd(s.ndStops)}",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                r == null -> Pill("No light reading", MaterialTheme.colorScheme.surfaceVariant)
                r.ok -> Pill("Exposed ✓", GoodGreen)
                r.stopsOff < 0 -> Pill("Under %.1f".format(-r.stopsOff), WarnAmber)
                else -> Pill("Over %.1f".format(r.stopsOff), WarnAmber)
            }
            Icon(
                if (state.expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (state.expanded) "Hide exposure" else "Show exposure",
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (!state.expanded) return@Column

        val locked = state.locked
        ExposureRow(
            "Light",
            state.lux?.let { "%,d lux".format(it.roundToInt()) } ?: "—",
            lightSource(state),
            locked = ExposureParam.LIGHT in locked, solved = false,
            onStep = { vm.step(ExposureParam.LIGHT, it) }, onLock = { vm.toggleLock(ExposureParam.LIGHT) },
        )
        ExposureRow("Aperture", fmtAperture(s.fNumber), null, ExposureParam.APERTURE in locked, r != null,
            { vm.step(ExposureParam.APERTURE, it) }, { vm.toggleLock(ExposureParam.APERTURE) })
        ExposureRow("ISO", fmtIso(s.iso), null, ExposureParam.ISO in locked, r != null,
            { vm.step(ExposureParam.ISO, it) }, { vm.toggleLock(ExposureParam.ISO) })
        ExposureRow("Shutter", "${fmtNum(s.angle)}°", fmtShutter(s), ExposureParam.SHUTTER in locked, r != null,
            { vm.step(ExposureParam.SHUTTER, it) }, { vm.toggleLock(ExposureParam.SHUTTER) })
        ExposureRow("Frame rate", "${fmtNum(nearestFps(s.fps))} fps", null, locked = null, solved = false,
            onStep = vm::stepFps, onLock = {})
        ExposureRow("ND", fmtNd(s.ndStops), if (s.ndStops > 0) "${fmtThirds(s.ndStops)} stops" else null,
            ExposureParam.ND in locked, r != null, { vm.step(ExposureParam.ND, it) }, { vm.toggleLock(ExposureParam.ND) })
        ExposureRow("Expose", "${if (s.bias >= 0) "+" else "−"}${fmtThirds(abs(s.bias))}", "stops over mid-grey",
            locked = null, solved = false, onStep = vm::stepBias, onLock = {})

        val lux = state.lux
        Text(
            when {
                r == null || lux == null -> "Connect the IM150 for a reading, or set the light with − and +. " +
                    "Lock what you want to keep; the rest are worked out."
                r.ok -> "Exposed for EV %.1f (ISO 100). Unlocked settings are worked out.".format(Exposure.ev100(lux))
                r.stopsOff < 0 -> "Needs %.1f stops more light: about %,d lux at the subject, or unlock a setting."
                    .format(-r.stopsOff, (lux * 2.0.pow(-r.stopsOff)).roundToInt())
                else -> "%.1f stops too much light: cut it to about %,d lux, add ND, or unlock a setting."
                    .format(r.stopsOff, (lux * 2.0.pow(-r.stopsOff)).roundToInt())
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (r != null && !r.ok) WarnAmber else muted,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * One setting: name, value (in the accent colour when worked out), − and +, and a lock.
 * [locked] null means the setting is an input that's never worked out, so has no lock.
 */
@Composable
private fun ExposureRow(
    label: String,
    value: String,
    sub: String?,
    locked: Boolean?,
    solved: Boolean,
    onStep: (Int) -> Unit,
    onLock: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1.3f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (locked == false && solved) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = { onStep(-1) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Remove, contentDescription = "Lower $label") }
        IconButton(onClick = { onStep(1) }, Modifier.size(36.dp)) { Icon(Icons.Filled.Add, contentDescription = "Raise $label") }
        if (locked != null) {
            IconButton(onClick = onLock, Modifier.size(36.dp)) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (locked) "Unlock $label" else "Lock $label",
                    tint = if (locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
    }
}

private fun lightSource(state: ExposureUiState): String {
    val ev = state.lux?.let { "EV %.1f · ".format(Exposure.ev100(it)) } ?: ""
    return ev + when {
        ExposureParam.LIGHT in state.locked -> "held"
        state.im150Status == Im150.Status.CONNECTED -> "IM150 live"
        state.im150Status == Im150.Status.SEARCHING -> "looking for the IM150…"
        else -> "no meter"
    }
}

/** Up to three decimals, trailing zeros dropped: 172.8, 23.976, 180. */
private fun fmtNum(v: Double): String = "%.3f".format(v).trimEnd('0').trimEnd('.')

private fun fmtAperture(n: Double): String = "f/" + fmtNum(n)

private fun fmtIso(iso: Double): String = "%,d".format(iso.roundToInt())

private fun fmtShutter(s: ExposureSettings): String {
    val t = s.shutter
    return if (t >= 1) "%.1f s".format(t) else "1/%d s".format((1 / t).roundToInt())
}

private fun fmtNd(stops: Double): String = if (stops <= 0.01) "No ND" else "ND %.1f".format(stops * 0.3)

private fun nearestFps(fps: Double): Double = Exposure.FRAME_RATES.minBy { abs(it - fps) }

/** Stops in thirds, e.g. 1⅔. */
private fun fmtThirds(stops: Double): String {
    val thirds = (stops * 3).roundToInt()
    val whole = thirds / 3
    return when (thirds % 3) {
        0 -> "$whole"
        1 -> if (whole == 0) "⅓" else "$whole⅓"
        else -> if (whole == 0) "⅔" else "$whole⅔"
    }
}
