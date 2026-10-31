package app.lightset.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lightset.LightRig
import app.lightset.ble.Im150
import app.lightset.camera.ExposureWarning
import app.lightset.camera.MeterSource
import app.lightset.camera.Reading
import app.lightset.color.Calibration
import app.lightset.color.CctDuv
import app.lightset.color.Delta
import app.lightset.color.Xy
import app.lightset.color.delta
import app.lightset.data.Reference
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Within this, a light reads as matched to the reference. */
private const val MATCH_MIRED = 5.0
private const val MATCH_DUV = 0.0015

@Composable
fun MeterScreen(vm: MeterViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var saving by rememberSaveable { mutableStateOf(false) }
    val info = remember { vm.cameraInfo() }
    val shown = state.reading?.let { state.corrected(it.xy, it.source) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(state, vm)

        if (state.meter == ColourMeter.IM150) {
            // No camera needed: the reading full width (the meter's status is on its chip).
            if (state.im150Status == Im150.Status.OFF) {
                Text(
                    (state.im150Error ?: "IM150 not connected") +
                        ". Turn it on and close the Illuminati app (it takes one connection at a time). Tap IM150 to retry.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = WarnAmber,
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Readout(state.reading, shown, state, Modifier.fillMaxWidth())
                if (state.live) LiveBadge(Modifier.align(Alignment.TopEnd))
            }
        } else Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            // Camera and reading side by side, controls directly beneath: always on screen.
            val aspect = info?.previewSize?.let { it.height.toFloat() / it.width } ?: 0.75f
            Box(
                Modifier
                    .weight(0.42f)
                    .aspectRatio(aspect)
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .then(if (state.live) Modifier.border(2.dp, LiveRed, RoundedCornerShape(12.dp)) else Modifier),
            ) {
                CameraPreview(onAvailable = vm::startCamera, onGone = vm::stopCamera)
                if (state.live) LiveBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                // Drag up to enlarge the sampled box, down to shrink it.
                Reticle(
                    state.patch,
                    Modifier.fillMaxSize().pointerInput(state.busy) {
                        if (state.busy) return@pointerInput
                        detectVerticalDragGestures(onDragEnd = vm::commitPatch, onDragCancel = vm::commitPatch) { change, dy ->
                            change.consume()
                            vm.dragPatch(-dy / size.height)
                        }
                    },
                )
                if (info?.source == MeterSource.PROCESSED) {
                    Text(
                        "APPROX",
                        Modifier.align(Alignment.TopStart).padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = Color.White, fontSize = 10.sp,
                    )
                }
            }
            Readout(state.reading, shown, state, Modifier.weight(0.58f).padding(start = 14.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Measures the room with the lights off and saves it as the reference.
            Button(onClick = vm::setAmbient, enabled = state.meterReady && !state.busy, modifier = Modifier.weight(1f)) {
                Text(if (state.measuring && state.task?.running != true && !state.live) "Reading ambient…" else "Set ambient")
            }
            // A reading without saving it, to check a light or the room.
            OutlinedButton(onClick = vm::measureOnce, enabled = state.meterReady && !state.busy && !state.live) {
                Text("Measure")
            }
            FilterChip(
                selected = state.live,
                onClick = { vm.setLive(!state.live) },
                enabled = state.meterReady && state.task?.running != true,
                label = { Text("Live") },
                leadingIcon = if (state.live) {
                    { Box(Modifier.size(8.dp).background(LiveRed, CircleShape)) }
                } else null,
            )
        }
        HorizontalDivider()

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferencePicker(state, { state.corrected(it.xy, it.source) }, vm,
                onSaveReading = { saving = true })

            state.calSession?.let { session ->
                CalibrationCard(
                    session, measuring = state.measuring, cameraOpen = state.cameraOpen, im150 = state.im150Connected,
                    onSelect = vm::selectCalStep, onMeasure = vm::measureCalStep,
                    onSave = vm::finishManualCalibration, onCancel = vm::cancelCalibration,
                )
            }

            LightCard(state, vm)
            state.calReview?.let { CalibrationReview(it, state.calibration, vm) }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (saving) {
        SaveDialog(vm.defaultReferenceName(), onSave = { vm.saveReference(it); saving = false }, onDismiss = { saving = false })
    }
}

@Composable
private fun Header(state: MeterUiState, vm: MeterViewModel) {
    var menu by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    TabHeader {
        // Which meter reads colour. The IM150's chip carries its connection: green
        // connected, amber finding it, red not connected (tap again to retry).
        for (m in ColourMeter.entries) {
            val im = m == ColourMeter.IM150
            FilterChip(
                selected = state.meter == m,
                onClick = { if (im && state.meter == m) vm.retryIm150() else vm.setMeter(m) },
                enabled = !state.busy,
                label = { Text(if (im) "IM150" else "Phone", maxLines = 1, softWrap = false) },
                leadingIcon = if (im && (state.meter == m || state.im150Status != Im150.Status.OFF)) {
                    {
                        Box(Modifier.size(8.dp).background(
                            when (state.im150Status) {
                                Im150.Status.CONNECTED -> GoodGreen
                                Im150.Status.SEARCHING -> WarnAmber
                                Im150.Status.OFF -> Color(0xFFE57373)
                            },
                            CircleShape,
                        ))
                    }
                } else null,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AllLightsSwitch(state, vm)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                CalibrationMenuItems(state, vm, onPicked = { menu = false },
                    onGuide = { guide = true }, onClear = { confirmClear = true })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Test G/M response") },
                    enabled = state.canRunLightTask,
                    onClick = { menu = false; vm.startGmTest() },
                )
                if (state.meter == ColourMeter.IM150 || state.im150Status != Im150.Status.OFF) {
                    DropdownMenuItem(
                        text = { Text("Stop using the IM150") },
                        enabled = !state.busy,
                        onClick = { menu = false; vm.forgetIm150() },
                    )
                }
            }
        }
    }
    CalibrationDialogs(state, vm, guide, confirmClear, closeGuide = { guide = false }, closeClear = { confirmClear = false })
}

/** CCT span the readout's gauge covers. */
private val GAUGE_CCT = 2000..10000

/**
 * The reading: its colour, CCT and tint in words, and both on colour gauges with the
 * reference marked, so how far off it is reads at a glance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readout(reading: Reading?, shown: CctDuv?, state: MeterUiState, modifier: Modifier) {
    val ref = state.active?.let { state.corrected(it.xy, it.source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(
                    shown?.let { tintedColor(it.cct, it.duv) } ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                )
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(shown?.let { formatKelvin(it.cct) } ?: "—", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 32.sp, maxLines = 1)
                Text(shown?.let { formatTint(it.duv) } ?: "Set ambient or Measure", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        ColourGauge(cctTrack(GAUGE_CCT), shown?.let { cctToSlider(it.cct.roundToInt(), GAUGE_CCT) },
            ref?.let { cctToSlider(it.cct.roundToInt(), GAUGE_CCT) })
        ColourGauge(tintTrack, shown?.let { tintPosition(it.duv) }, ref?.let { tintPosition(it.duv) })
        state.active?.let {
            Text("▼ ${it.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
        }
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val isReference = reading != null && state.active?.let {
                it.createdAt == reading.timestampMs && it.source == reading.source
            } == true
            if (state.settingAmbient) {
                Pill("Reading ambient…", MaterialTheme.colorScheme.surfaceVariant)
            } else if (isReference) {
                Pill("Saved as reference", MaterialTheme.colorScheme.surfaceVariant)
            } else if (shown != null && ref != null) {
                val d = delta(shown, ref)
                if (d.withinTolerance(MATCH_MIRED, MATCH_DUV)) {
                    Pill("Matches", GoodGreen)
                } else {
                    // What to do to the light to match, coloured by the direction to go.
                    Pill(describeTemp(d), when {
                        abs(d.mired) <= MATCH_MIRED -> GoodGreen
                        d.mired > 0 -> kelvinColor(9000)
                        else -> kelvinColor(2700)
                    })
                    Pill(describeTint(d), when {
                        abs(d.duv) <= MATCH_DUV -> GoodGreen
                        d.duv > 0 -> Color(0xFFD45FD4)
                        else -> Color(0xFF5FBF5F)
                    })
                }
            }
            when (reading?.warning) {
                ExposureWarning.CLIPPED -> Pill("Overexposed", WarnAmber)
                ExposureWarning.TOO_DARK -> Pill("Too dark", WarnAmber)
                else -> Unit
            }
            if (state.meter == ColourMeter.PHONE && state.calibration == null) {
                Pill("Uncalibrated", MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * The reference lights are matched to, with how old it is (amber once it's a few hours
 * old, as the room light may have moved). An old one from last time is offered, not
 * used. The menu picks, renames or deletes references, or saves the current reading.
 */
@Composable
private fun ReferencePicker(state: MeterUiState, corrected: (Reference) -> CctDuv, vm: MeterViewModel, onSaveReading: () -> Unit) {
    val refs = state.references
    val active = state.active
    val last = state.lastReference
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Reference?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { width = it.width }
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // Label and age, then the values (bigger) and the name.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reference", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = muted)
                    active?.let {
                        val age = now - it.createdAt
                        Text(formatAge(age, it.createdAt, now), style = MaterialTheme.typography.labelMedium,
                            color = if (age > STALE_REF_MS) WarnAmber else muted, maxLines = 1)
                    }
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (active != null) {
                        val c = corrected(active)
                        Box(Modifier.padding(end = 8.dp).size(16.dp).background(tintedColor(c.cct, c.duv), CircleShape))
                        Text("${formatKelvin(c.cct)}  ${formatTint(c.duv)}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("  ${active.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(
                            when {
                                last != null -> "None yet: Set ambient, or use the last one"
                                refs.isEmpty() -> "Set ambient to read the room"
                                else -> "None selected"
                            },
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (last != null && active == null) {
                // Last job's reference, one tap to carry on with it.
                TextButton(onClick = { vm.selectReference(last.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Use last", style = MaterialTheme.typography.labelLarge)
                        Text(formatAge(now - last.createdAt, last.createdAt, now), style = MaterialTheme.typography.labelSmall,
                            color = muted)
                    }
                }
            } else {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose reference")
            }
        }
        // As wide as the row, so names and values are easy to read.
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.width(with(LocalDensity.current) { width.toDp() }),
        ) {
            if (state.reading != null && !state.live && !state.busy) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Save current reading…") },
                    onClick = { open = false; onSaveReading() },
                )
                if (refs.isNotEmpty()) HorizontalDivider()
            }
            refs.forEach { ref ->
                val c = corrected(ref)
                DropdownMenuItem(
                    leadingIcon = { Box(Modifier.size(18.dp).background(tintedColor(c.cct, c.duv), CircleShape)) },
                    text = {
                        Column {
                            Text(ref.name, fontWeight = if (ref.id == active?.id) FontWeight.SemiBold else null)
                            Text("${formatKelvin(c.cct)} ${formatTint(c.duv)} · " +
                                (if (ref.source == MeterSource.IM150) "IM150 · " else "") +
                                formatAge(now - ref.createdAt, ref.createdAt, now),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { vm.selectReference(ref.id); open = false },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { open = false; renaming = ref }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename ${ref.name}")
                            }
                            IconButton(onClick = { vm.deleteReference(ref.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${ref.name}")
                            }
                        }
                    },
                )
            }
            if (refs.isEmpty() && state.reading == null) {
                DropdownMenuItem(text = { Text("No references yet") }, onClick = { open = false }, enabled = false)
            }
        }
    }
    renaming?.let { ref ->
        var name by remember(ref.id) { mutableStateOf(ref.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename reference") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameReference(ref.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

/** A reference older than this is shown in amber: the room light may have changed. */
private const val STALE_REF_MS = 3 * 60 * 60 * 1000L

/** "just now", "12 min ago", "3 h ago", "yesterday 12:37", or the date. */
private fun formatAge(ageMs: Long, at: Long, now: Long): String {
    val min = ageMs / 60_000
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    val days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
        startOfDay(now) - startOfDay(at))
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        days == 0L -> "${min / 60} h ago"
        days == 1L -> "yesterday $time"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(Date(at)) + " $time"
    }
}

private fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().run {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    timeInMillis
}

/** The light: status, brightness, and the Match action with its progress. */
@Composable
private fun LightCard(state: MeterUiState, vm: MeterViewModel) {
    val light = state.light
    val online = state.lightStatus == LightRig.Status.ONLINE
    val task = state.task
    StripeCard(lightColor(light)) {
        Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LightPicker(state, vm)
                    // The sliders show CCT settings; say so when the light is in colour mode instead.
                    if (light?.mode == LightRig.Mode.HSI) {
                        Text("Colour mode: hue ${light.hue}°, sat ${light.sat}%",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = light?.on == true,
                    onCheckedChange = vm::setLightOn,
                    enabled = online && task?.running != true && light != null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (task?.running == true) {
                    TextButton(onClick = vm::stopTask) { Text("Stop") }
                } else {
                    Button(onClick = vm::startDialIn, enabled = state.canRunLightTask && state.active != null) {
                        Text("Match")
                    }
                }
            }
            ValueControl("Brightness", light?.brightness ?: 20, 0..100, step = 1, format = { "$it%" },
                enabled = online && task?.running != true && light != null, onChange = vm::setLightBrightness,
                track = brightnessTrack)
            val node = state.selectedNode
            if (light != null && node != null) {
                val limits = node.model.limits
                val cctRange = limits.cctMin..limits.cctMax
                val manual = online && task?.running != true
                ValueControl(
                    "CCT", light.cct.coerceIn(cctRange), cctRange, step = 50, format = { formatKelvin(it.toDouble()) },
                    enabled = manual, onChange = { vm.setLightColour(it, light.gm) },
                    toSlider = { cctToSlider(it, cctRange) },
                    fromSlider = { sliderToCct(it, cctRange) },
                    track = cctTrack(cctRange),
                )
                if (limits.hasGm) {
                    val gmRange = limits.gmMin..limits.gmMax
                    ValueControl("G/M", light.gm.coerceIn(gmRange), gmRange, step = 1,
                        format = { if (it == 0) "0" else "%+d %s".format(it, if (it > 0) "G" else "M") },
                        enabled = manual, onChange = { vm.setLightColour(light.cct, it) }, track = gmTrack(gmRange))
                }
            }
            if (light?.brightness == null) {
                Text(
                    "Set brightness first. Matching only changes CCT and G/M.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task?.let { TaskView(it, onDismiss = vm::dismissTask) }
        }
    }
}

/**
 * Master on/off for every connected light (on while any of them is). With none
 * connected, the light link's state instead, tap to retry when it's offline.
 */
@Composable
private fun AllLightsSwitch(state: MeterUiState, vm: MeterViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (state.reachable.isEmpty()) {
        when (state.lightStatus) {
            LightRig.Status.CONNECTING -> Text("Connecting…", style = MaterialTheme.typography.labelMedium, color = muted)
            else -> Text("Lights offline · retry", Modifier.clickable(onClick = vm::reconnectLight).padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = WarnAmber)
        }
        return
    }
    Switch(
        checked = state.reachable.any { it in state.lightsOn },
        onCheckedChange = vm::setAllOn,
        enabled = state.task?.running != true,
    )
}

/** Which light Match and calibration use: any connected one, in the Lights tab's order. */
@Composable
private fun LightPicker(state: MeterUiState, vm: MeterViewModel) {
    var open by remember { mutableStateOf(false) }
    val choices = state.lights.filter { it.unicast in state.reachable }
    val canPick = choices.any { it.unicast != state.selected }
    Box {
        Row(
            Modifier.clickable(enabled = canPick && state.task?.running != true) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.selectedNode?.name ?: "No lights — add one on the Lights tab", style = MaterialTheme.typography.titleMedium)
            if (canPick) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose light")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { n ->
                DropdownMenuItem(
                    text = { Text("${n.name} · ${n.model.label}") },
                    onClick = { vm.selectLight(n.unicast); open = false },
                )
            }
        }
    }
}

/**
 * A light task: its title, a progress bar while it runs, and the latest step (all
 * steps on request), then the result. Kept short so Match fits without scrolling.
 */
@Composable
private fun TaskView(task: TaskUi, onDismiss: () -> Unit) {
    var details by remember(task.title) { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (details) "Hide steps" else "Steps (${task.lines.size})")
            }
            if (!task.running) TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
        }
        if (task.running) {
            val p = task.progress
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            if (!details) {
                task.lines.lastOrNull()?.let {
                    Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (details) {
            task.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
        }
        task.result?.let {
            Text(it, color = if (task.success) GoodGreen else WarnAmber, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A pulsing red dot and LIVE, so it's plain the reading is updating. */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Row(
        modifier.background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).alpha(pulse).background(LiveRed, CircleShape))
        Text("LIVE", Modifier.padding(start = 4.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val LiveRed = Color(0xFFFF4D4D)

@Composable
private fun CameraPreview(onAvailable: (SurfaceTexture) -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val view = remember {
        TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = onAvailable(st)
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onGone()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.surfaceTexture?.let { if (view.isAvailable) onAvailable(it) }
                Lifecycle.Event.ON_PAUSE -> onGone()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** Outlines the centre box ([patch] of each side) that the meter samples and meters exposure on. */
@Composable
private fun Reticle(patch: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width * patch
        val h = size.height * patch
        drawRect(
            Color.White,
            topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
            size = Size(w, h),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun CalibrationCard(
    session: CalSession,
    measuring: Boolean,
    cameraOpen: Boolean,
    im150: Boolean,
    onSelect: (Int) -> Unit,
    onMeasure: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Calibrate manually", style = MaterialTheme.typography.titleMedium)
            Text(
                if (im150) "The IM150 is connected, so its reading is the truth for each step and any light works: " +
                    "put it beside the card, facing the light. Keep everything still for each step. Tap a step to redo it."
                else "Use one trusted light at G/M 0 as the only light on the card. Keep the phone, card and " +
                    "distance the same for every step. Tap a step to redo it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CALIBRATION_TARGETS.forEachIndexed { i, k ->
                val r = session.readings[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (i == session.index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("Set ${formatKelvin(k)}, G/M 0", Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(r?.let { "read ${formatKelvin(it.cct)} ${formatTint(it.duv)}" } ?: "—",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        session.references[i]?.let {
                            Text("IM150 ${formatKelvin(it.cct)} ${formatTint(it.duv)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            session.error?.let { Text(it, color = WarnAmber) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onMeasure, enabled = cameraOpen && !measuring) {
                    Text(if (measuring) "Measuring…" else "Measure ${formatKelvin(CALIBRATION_TARGETS[session.index])}")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
                FilledTonalButton(onClick = onSave, enabled = session.complete && !measuring) { Text("Review") }
            }
        }
    }
}

/** Meter calibration in the overflow menu: what's in use, and making, restoring or clearing one. */
@Composable
private fun CalibrationMenuItems(
    state: MeterUiState, vm: MeterViewModel, onPicked: () -> Unit, onGuide: () -> Unit, onClear: () -> Unit,
) {
    val cal = state.calibration
    val lightName = state.selectedNode?.name ?: "the light"
    val canStart = !state.busy && state.calSession == null
    DropdownMenuItem(
        text = {
            Column {
                Text("Meter calibration", style = MaterialTheme.typography.labelMedium)
                Text(cal?.let(::describeCalibration) ?: "Not calibrated", style = MaterialTheme.typography.bodySmall)
            }
        },
        enabled = false,
        onClick = {},
    )
    DropdownMenuItem(
        text = { Text("Calibrate automatically, with $lightName" + if (state.im150Connected) " + IM150" else "") },
        enabled = canStart && state.lightReady,
        onClick = { onPicked(); vm.useCameraForCalibration(); onGuide() },
    )
    DropdownMenuItem(text = { Text("Calibrate manually") }, enabled = canStart,
        onClick = { onPicked(); vm.startCalibration() })
    state.previousCalibration?.let { prev ->
        DropdownMenuItem(
            text = { Text("Restore previous calibration (${describeCalibration(prev)})") },
            enabled = canStart,
            onClick = { onPicked(); vm.restorePreviousCalibration() },
        )
    }
    if (cal != null) {
        DropdownMenuItem(text = { Text("Clear calibration") }, enabled = canStart,
            onClick = { onPicked(); onClear() })
    }
}

/** The auto-calibration guide and clear confirmation opened from the menu. */
@Composable
private fun CalibrationDialogs(
    state: MeterUiState, vm: MeterViewModel, guide: Boolean, confirmClear: Boolean,
    closeGuide: () -> Unit, closeClear: () -> Unit,
) {
    val lightName = state.selectedNode?.name ?: "the light"
    if (guide) {
        AlertDialog(
            onDismissRequest = { closeGuide() },
            title = { Text("Calibrate with $lightName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tip in listOf(
                        "Fill the box with a white or grey card.",
                        "Make $lightName the main light on the card: turn its brightness up, dim other lights.",
                        "Keep the phone and card still for about 20 s while it steps 2,700–6,500 K. The light blinks off once.",
                        if (state.im150Connected) "The IM150's readings are the targets: put it beside the card, facing the light."
                        else "Without the IM150, the light's settings are taken as exact.",
                        "Nothing is saved until you review the result.",
                    )) {
                        Row {
                            Text("•  ")
                            Text(tip, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { closeGuide(); vm.startAutoCalibration() }, enabled = state.canAutoCalibrate) {
                    Text("Start")
                }
            },
            dismissButton = { TextButton(onClick = { closeGuide() }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { closeClear() },
            title = { Text("Clear calibration?") },
            text = { Text("Readings go back to the phone's own colour matrices. You can bring it back from the menu with Restore previous calibration.") },
            confirmButton = { TextButton(onClick = { vm.clearCalibration(); closeClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { closeClear() }) { Text("Cancel") } },
        )
    }
}

/** A new calibration's readings, any warnings, and how it compares with the current one. */
@Composable
private fun CalibrationReview(review: CalReview, current: Calibration?, vm: MeterViewModel) {
    val cal = review.calibration
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("New calibration", style = MaterialTheme.typography.titleMedium)
            // What the meter read at each setting, now and in the calibration in use.
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Row {
                Text("Light set", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, color = muted)
                Text("New", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
                if (current != null) {
                    Text("Current", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
                }
                if (review.settle.isNotEmpty()) {
                    Text("Spread", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            cal.points.sortedBy { it.setKelvin }.forEach { p ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(0.8f)) {
                        Text(formatKelvin(p.setKelvin), style = MaterialTheme.typography.bodyMedium)
                        // A reference meter's reading of the light, when that was the truth.
                        if (p.targetKelvin != p.setKelvin || p.targetDuv != 0.0) {
                            Text("IM150 ${formatKelvin(p.targetKelvin)}", style = MaterialTheme.typography.labelSmall, color = muted)
                            Text(formatTint(p.targetDuv), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                    }
                    ReadingCell(p.measured, Modifier.weight(1f))
                    if (current != null) {
                        val old = current.points.firstOrNull { it.setKelvin == p.setKelvin }?.measured
                        if (old != null) ReadingCell(old, Modifier.weight(1f)) else Text("—", Modifier.weight(1f))
                    }
                    if (review.settle.isNotEmpty()) {
                        val s = review.settle[p.setKelvin]
                        if (s != null) SettleCell(s, Modifier.weight(0.9f)) else Text("—", Modifier.weight(0.9f))
                    }
                }
            }
            if (review.settle.isNotEmpty()) {
                Text(
                    "Spread: how much the last two readings differed (mired over tint) and how long the light took to settle",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            current?.let { cur ->
                Text(
                    "Current: ${describeCalibration(cur)} · up to %.0f mired off on these".format(cur.worstError(cal.points)),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            review.warnings.forEach { Text(it, color = WarnAmber, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::discardReviewedCalibration) { Text("Discard") }
                FilledTonalButton(onClick = vm::saveReviewedCalibration) {
                    Text(if (current != null) "Replace current" else "Save")
                }
            }
        }
    }
}

/** A calibration reading in a table cell: CCT over tint. */
@Composable
private fun ReadingCell(c: CctDuv, modifier: Modifier) {
    Column(modifier) {
        Text(formatKelvin(c.cct), style = MaterialTheme.typography.bodyMedium)
        Text(formatTint(c.duv), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How a calibration reading settled: mired and tint spread, then the time taken, amber if it never settled. */
@Composable
private fun SettleCell(s: Settle, modifier: Modifier) {
    val color = if (s.settled) MaterialTheme.colorScheme.onSurface else WarnAmber
    Column(modifier) {
        Text("±%.1f · ±%.1f".format(s.spread.mired, s.spread.duv * 1000), style = MaterialTheme.typography.bodyMedium, color = color)
        Text(
            if (s.settled) "%.1f s".format(s.ms / 1000.0) else "still changing",
            style = MaterialTheme.typography.labelSmall,
            color = if (s.settled) MaterialTheme.colorScheme.onSurfaceVariant else WarnAmber,
        )
    }
}

private fun describeCalibration(c: Calibration): String {
    val date = if (c.createdAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.createdAt))
    else "Saved earlier"
    return date + when (c.source) {
        null -> ""
        "manual" -> " · manual"
        else -> " · ${c.source}"
    }
}

@Composable
private fun SaveDialog(default: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save reference") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What to do to the light's CCT to match the reference. */
private fun describeTemp(d: Delta): String {
    val k = abs(d.kelvin).toInt()
    return when {
        abs(d.mired) <= MATCH_MIRED -> "CCT ✓"
        d.mired > 0 -> "Cool it ~$k K"
        else -> "Warm it ~$k K"
    }
}

/** What to do to the light's tint to match the reference. */
private fun describeTint(d: Delta): String {
    val t = abs(d.duv) * 1000
    return when {
        abs(d.duv) <= MATCH_DUV -> "Tint ✓"
        d.duv > 0 -> "Add magenta %.1f".format(t)
        else -> "Add green %.1f".format(t)
    }
}
