package app.lightset.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lightset.LightRig
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
    val shown = state.reading?.let { state.corrected(it.xy) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(state, vm)

        // Camera and reading side by side, controls directly beneath: always on screen.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            val aspect = info?.previewSize?.let { it.height.toFloat() / it.width } ?: 0.75f
            Box(
                Modifier
                    .weight(0.42f)
                    .aspectRatio(aspect)
                    .background(Color.Black, RoundedCornerShape(12.dp)),
            ) {
                CameraPreview(onAvailable = vm::startCamera, onGone = vm::stopCamera)
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
            Button(onClick = vm::measureOnce, enabled = state.cameraOpen && !state.busy && !state.live) {
                Text(if (state.measuring && state.task?.running != true) "Measuring…" else "Measure")
            }
            FilterChip(
                selected = state.live,
                onClick = { vm.setLive(!state.live) },
                enabled = state.cameraOpen && state.task?.running != true,
                label = { Text("Live") },
            )
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { saving = true }, enabled = state.reading != null && !state.live && !state.busy) {
                Text("Save ref")
            }
        }
        HorizontalDivider()

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReferencePicker(state.references, state.active, state::corrected, vm::selectReference, vm::deleteReference)

            state.calSession?.let { session ->
                CalibrationCard(
                    session, measuring = state.measuring, cameraOpen = state.cameraOpen,
                    onSelect = vm::selectCalStep, onMeasure = vm::measureCalStep,
                    onSave = vm::finishManualCalibration, onCancel = vm::cancelCalibration,
                )
            }

            LightCard(state, vm)
            CalibrationSection(state, vm)

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Fill the box with a white or grey card; drag up or down on the preview to resize it." +
                    (info?.let { " ${it.description}." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (saving) {
        SaveDialog(onSave = { vm.saveReference(it); saving = false }, onDismiss = { saving = false })
    }
}

@Composable
private fun Header(state: MeterUiState, vm: MeterViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Match", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        LightStatusChip(state.selectedNode?.name?.take(14) ?: "No light", state.lightStatus, onRetry = vm::reconnectLight)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Test G/M response") },
                    enabled = state.canRunLightTask,
                    onClick = { menu = false; vm.startGmTest() },
                )
            }
        }
    }
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
    val ref = state.active?.let { state.corrected(it.xy) }
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
                Text(shown?.let { formatTint(it.duv) } ?: "Tap Measure or Live", style = MaterialTheme.typography.bodyMedium,
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
            if (shown != null && ref != null) {
                val d = delta(shown, ref)
                if (d.withinTolerance(MATCH_MIRED, MATCH_DUV)) {
                    Pill("Matches", GoodGreen)
                } else {
                    Pill(describeTemp(d), when {
                        abs(d.mired) <= MATCH_MIRED -> GoodGreen
                        d.mired > 0 -> kelvinColor(2700)
                        else -> kelvinColor(9000)
                    })
                    Pill(describeTint(d), when {
                        abs(d.duv) <= MATCH_DUV -> GoodGreen
                        d.duv > 0 -> Color(0xFF5FBF5F)
                        else -> Color(0xFFD45FD4)
                    })
                }
            }
            when (reading?.warning) {
                ExposureWarning.CLIPPED -> Pill("Overexposed", WarnAmber)
                ExposureWarning.TOO_DARK -> Pill("Too dark", WarnAmber)
                else -> Unit
            }
            if (state.calibration == null) Pill("Uncalibrated", MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun ReferencePicker(
    refs: List<Reference>,
    active: Reference?,
    corrected: (Xy) -> CctDuv,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .clickable(enabled = refs.isNotEmpty()) { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.padding(end = 12.dp).size(26.dp).background(
                    active?.let { corrected(it.xy).let { c -> tintedColor(c.cct, c.duv) } } ?: Color.Transparent, CircleShape,
                )
            )
            Column(Modifier.weight(1f)) {
                Text("Reference", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        active != null -> corrected(active.xy).let { "${active.name} · ${formatKelvin(it.cct)} ${formatTint(it.duv)}" }
                        refs.isEmpty() -> "Measure the ambient, then Save ref"
                        else -> "None selected"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (refs.isNotEmpty()) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose reference")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            refs.forEach { ref ->
                val c = corrected(ref.xy)
                DropdownMenuItem(
                    leadingIcon = { Box(Modifier.size(18.dp).background(tintedColor(c.cct, c.duv), CircleShape)) },
                    text = {
                        Column {
                            Text(ref.name, fontWeight = if (ref.id == active?.id) FontWeight.SemiBold else null)
                            Text("${formatKelvin(c.cct)} ${formatTint(c.duv)} · ${fmt.format(Date(ref.createdAt))}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { onSelect(ref.id); open = false },
                    trailingIcon = {
                        IconButton(onClick = { onDelete(ref.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${ref.name}")
                        }
                    },
                )
            }
        }
    }
}

/** The light: status, brightness, and the Match action with its progress. */
@Composable
private fun LightCard(state: MeterUiState, vm: MeterViewModel) {
    val light = state.light
    val online = state.lightStatus == LightRig.Status.ONLINE
    val task = state.task
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(end = 12.dp).size(26.dp).background(
                        when {
                            light == null || !light.on -> Color(0xFF424242)
                            light.mode == LightRig.Mode.HSI -> Color.hsv(light.hue.toFloat(), light.sat / 100f, 1f)
                            else -> tintedColor(light.cct.toDouble(), 0.0)
                        },
                        CircleShape,
                    )
                )
                Column(Modifier.weight(1f)) {
                    LightPicker(state, vm)
                    Text(
                        light?.let {
                            (if (!it.on) "Off · " else "${it.brightness?.let { b -> "$b%" } ?: "brightness not set"} · ") +
                                if (it.mode == LightRig.Mode.HSI) "hue ${it.hue}°, sat ${it.sat}%"
                                else "${formatKelvin(it.cct.toDouble())} · G/M %+d".format(it.gm)
                        } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            BrightnessRow(light?.brightness, enabled = online && task?.running != true, onSet = vm::setLightBrightness)
            if (light?.brightness == null) {
                Text(
                    "Set brightness first. Matching only changes CCT and G/M.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task?.let { TaskView(it, onDismiss = vm::dismissTask) }
        }
    }
}

/** Which light Match and calibration use. */
@Composable
private fun LightPicker(state: MeterUiState, vm: MeterViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(enabled = state.lights.size > 1 && state.task?.running != true) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.selectedNode?.name ?: "No lights — add one on the Lights tab", style = MaterialTheme.typography.titleMedium)
            if (state.lights.size > 1) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose light")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.lights.forEach { n ->
                DropdownMenuItem(
                    text = { Text("${n.name} · ${n.model.label}") },
                    onClick = { vm.selectLight(n.unicast); open = false },
                )
            }
        }
    }
}

@Composable
private fun TaskView(task: TaskUi, onDismiss: () -> Unit) {
    var details by remember(task.title) { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(task.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide steps" else "Steps (${task.lines.size})") }
        if (!task.running) TextButton(onClick = onDismiss) { Text("Clear") }
    }
    if (task.running && !details) {
        task.lines.lastOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (details) {
        task.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
    }
    task.result?.let {
        Text(it, color = if (task.success) GoodGreen else WarnAmber, fontWeight = FontWeight.SemiBold)
    }
}

/** Brightness slider; sends when the finger lifts. */
@Composable
private fun BrightnessRow(brightness: Int?, enabled: Boolean, onSet: (Int) -> Unit) {
    var value by remember(brightness) { mutableStateOf((brightness ?: 20).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Brightness", Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onSet(value.roundToInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text("${value.roundToInt()}%", Modifier.width(48.dp).padding(start = 8.dp))
    }
}

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
    onSelect: (Int) -> Unit,
    onMeasure: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Calibrate manually", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use one trusted light at G/M 0 as the only light on the card. Keep the phone, card and " +
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
                    Text(r?.let { "read ${formatKelvin(it.cct)} ${formatTint(it.duv)}" } ?: "—",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What calibration is in use, how to make a new one, and a new one awaiting review. */
@Composable
private fun CalibrationSection(state: MeterUiState, vm: MeterViewModel) {
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    val cal = state.calibration
    val lightName = state.selectedNode?.name ?: "the light"
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Meter calibration", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cal?.let(::describeCalibration) ?: "Not calibrated", style = MaterialTheme.typography.bodyLarge)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = !state.busy && state.calSession == null) { Text("Calibrate") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Automatic, with $lightName") },
                    enabled = state.canRunLightTask,
                    onClick = { menu = false; guide = true },
                )
                DropdownMenuItem(text = { Text("Manually") }, onClick = { menu = false; vm.startCalibration() })
                state.previousCalibration?.let { prev ->
                    DropdownMenuItem(
                        text = { Text("Restore previous (${describeCalibration(prev)})") },
                        onClick = { menu = false; vm.restorePreviousCalibration() },
                    )
                }
                if (cal != null) {
                    DropdownMenuItem(text = { Text("Clear calibration") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
    }
    state.calReview?.let { CalibrationReview(it, cal, vm) }
    if (guide) {
        AlertDialog(
            onDismissRequest = { guide = false },
            title = { Text("Calibrate with $lightName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tip in listOf(
                        "Fill the box with a white or grey card.",
                        "Make $lightName the main light on the card: turn its brightness up, dim other lights.",
                        "Keep the phone and card still for about 20 s while it steps 2,700–6,500 K. The light blinks off once.",
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
                TextButton(onClick = { guide = false; vm.startAutoCalibration() }, enabled = state.canRunLightTask) {
                    Text("Start")
                }
            },
            dismissButton = { TextButton(onClick = { guide = false }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear calibration?") },
            text = { Text("Readings go back to the phone's own colour matrices. You can bring it back with Calibrate → Restore previous.") },
            confirmButton = { TextButton(onClick = { vm.clearCalibration(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
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
            }
            cal.points.sortedBy { it.targetKelvin }.forEach { p ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(formatKelvin(p.targetKelvin), Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium)
                    ReadingCell(p.measured, Modifier.weight(1f))
                    if (current != null) {
                        val old = current.points.firstOrNull { it.targetKelvin == p.targetKelvin }?.measured
                        if (old != null) ReadingCell(old, Modifier.weight(1f)) else Text("—", Modifier.weight(1f))
                    }
                }
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
private fun SaveDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val default = remember { "Ambient " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) }
    var name by rememberSaveable { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save reference") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun describeTemp(d: Delta): String {
    val k = abs(d.kelvin).toInt()
    return when {
        abs(d.mired) <= MATCH_MIRED -> "CCT ✓"
        d.mired > 0 -> "$k K warmer"
        else -> "$k K cooler"
    }
}

private fun describeTint(d: Delta): String {
    val t = abs(d.duv) * 1000
    return when {
        abs(d.duv) <= MATCH_DUV -> "Tint ✓"
        d.duv > 0 -> "%.1f greener".format(t)
        else -> "%.1f more magenta".format(t)
    }
}
