package app.lightset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * Exposure calculator pinned to the bottom of the Lights tab: the light (IM150 lux,
 * held, or set by hand) and the camera settings. Locked settings are kept and the rest
 * are worked out (shown in blue); a meter shows how far over or under that leaves it.
 * Kept short when open so the lights above stay in view to adjust.
 */
@Composable
fun ExposureBar(state: ExposureUiState, vm: ExposureViewModel) {
    val r = state.result
    val s = state.shown
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locked = state.locked
    val solved = r != null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(PanelGrey, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.setExpanded(!state.expanded) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.expanded) {
                Text(state.lux?.let { "%,d".format(it.roundToInt()) } ?: "—",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text(" lux · " + lightSource(state), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    "${fmtAperture(s.fNumber)} · ISO ${fmtIso(s.iso)} · ${fmtNum(s.angle)}° · ${fmtNd(s.ndStops)}",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                modifier = Modifier.padding(start = 4.dp), tint = muted,
            )
        }

        if (!state.expanded) {
            ExposureMeter(r, Modifier.padding(vertical = 6.dp))
            Row {
                Text(
                    (state.lux?.let { "%,d lux · ".format(it.roundToInt()) } ?: "") + lightSourceShort(state),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1,
                )
                Text("${fmtNum(nearestFps(s.fps))} fps · ${fmtBias(s.bias)}", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            return@Column
        }

        // The light: set by hand with − and +, and held with the lock.
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(Icons.Filled.Remove, "Lower light") { vm.step(ExposureParam.LIGHT, -1) }
            ExposureMeter(r, Modifier.weight(1f).padding(horizontal = 4.dp))
            StepButton(Icons.Filled.Add, "Raise light") { vm.step(ExposureParam.LIGHT, 1) }
            LockButton("light", ExposureParam.LIGHT in locked) { vm.toggleLock(ExposureParam.LIGHT) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExposureTile("Aperture", fmtAperture(s.fNumber), null, ExposureParam.APERTURE in locked, solved,
                { vm.step(ExposureParam.APERTURE, it) }, { vm.toggleLock(ExposureParam.APERTURE) }, Modifier.weight(1f))
            ExposureTile("ISO", fmtIso(s.iso), null, ExposureParam.ISO in locked, solved,
                { vm.step(ExposureParam.ISO, it) }, { vm.toggleLock(ExposureParam.ISO) }, Modifier.weight(1f))
            ExposureTile("Shutter", "${fmtNum(s.angle)}°", fmtShutter(s), ExposureParam.SHUTTER in locked, solved,
                { vm.step(ExposureParam.SHUTTER, it) }, { vm.toggleLock(ExposureParam.SHUTTER) }, Modifier.weight(1f))
            ExposureTile("ND", if (s.ndStops <= 0.01) "None" else "%.1f".format(s.ndStops * 0.3),
                if (s.ndStops > 0.01) fmtFx6Nd(s.ndStops) else null, ExposureParam.ND in locked, solved,
                { vm.step(ExposureParam.ND, it) }, { vm.toggleLock(ExposureParam.ND) }, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExposureStepper("fps", fmtNum(nearestFps(s.fps)), vm::stepFps, Modifier.weight(1f))
            ExposureStepper("Expose", fmtBias(s.bias), vm::stepBias, Modifier.weight(1f))
        }
    }
}

/**
 * Over/under on a −3…+3 stop scale like a camera's meter: a tick at correct exposure
 * and a needle, green when it's within tolerance. No needle without a light value.
 */
@Composable
private fun ExposureMeter(r: Exposure.Result?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val cy = size.height / 2
        val bar = 3.dp.toPx()
        drawRoundRect(track, Offset(0f, cy - bar / 2), Size(size.width, bar), CornerRadius(bar / 2))
        drawRect(tick, Offset(size.width / 2 - 0.5.dp.toPx(), cy - 5.dp.toPx()), Size(1.dp.toPx(), 10.dp.toPx()))
        r?.let {
            val x = ((it.stopsOff.coerceIn(-3.0, 3.0) + 3) / 6).toFloat() * size.width
            val w = 3.dp.toPx()
            drawRoundRect(if (it.ok) GoodGreen else WarnAmber, Offset(x - w / 2, 0f), Size(w, size.height), CornerRadius(w / 2))
        }
    }
}

/**
 * One camera setting as a tile: name and lock (tap to lock or unlock), the value (blue
 * when worked out), an optional detail line, and − / + to step it.
 */
@Composable
private fun ExposureTile(
    label: String,
    value: String,
    sub: String?,
    locked: Boolean,
    solved: Boolean,
    onStep: (Int) -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val worked = solved && !locked
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .then(if (worked) Modifier.border(1.dp, SolvedBlue, shape) else Modifier)
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.clickable(onClick = onLock).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (locked) "Unlock $label" else "Lock $label",
                modifier = Modifier.padding(start = 2.dp).size(12.dp),
                tint = if (locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (worked) SolvedBlue else Color.Unspecified)
        Text(sub ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StepButton(Icons.Filled.Remove, "Lower $label") { onStep(-1) }
            StepButton(Icons.Filled.Add, "Raise $label") { onStep(1) }
        }
    }
}

/** An input that's never worked out (frame rate, exposure bias): name, − value +. */
@Composable
private fun ExposureStepper(label: String, value: String, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StepButton(Icons.Filled.Remove, "Lower $label") { onStep(-1) }
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 1)
        StepButton(Icons.Filled.Add, "Raise $label") { onStep(1) }
    }
}

@Composable
private fun LockButton(label: String, locked: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(36.dp)) {
        Icon(
            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (locked) "Unlock $label" else "Lock $label",
            modifier = Modifier.size(18.dp),
            tint = if (locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmtBias(bias: Double): String = if (abs(bias) < 0.01) "0" else (if (bias > 0) "+" else "−") + fmtThirds(abs(bias))

/** Where the light value comes from, for the collapsed line. */
private fun lightSourceShort(state: ExposureUiState): String = when {
    ExposureParam.LIGHT in state.locked -> "held"
    state.im150Status == Im150.Status.CONNECTED -> "IM150"
    state.im150Status == Im150.Status.SEARCHING -> "finding IM150…"
    else -> "no meter"
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

/**
 * The Sony FX6's variable ND, which is labelled as the fraction of light let through
 * (1/4 = 2 stops … 1/128 = 7 stops). Between clear and 1/4 it has nothing.
 */
private fun fmtFx6Nd(stops: Double): String = when {
    stops < 2 - 0.01 -> "FX6: none"
    stops > 7 + 0.01 -> "FX6: past 1/128"
    else -> "FX6 1/%d".format(2.0.pow(stops).roundToInt())
}

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
