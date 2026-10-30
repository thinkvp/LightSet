package app.lightset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lightset.LightRig
import app.lightset.ble.Im150
import app.lightset.mesh.LightModel
import app.lightset.data.Scene
import app.lightset.mesh.MeshNode
import java.text.DateFormat
import java.util.Date

@Composable
fun LightsScreen(vm: LightsViewModel, exposureVm: ExposureViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exposure by exposureVm.state.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lights", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.add == null) {
                    FilledTonalButton(onClick = vm::startAdd) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(" Add light")
                    }
                }
                LightsMenu(onLog = { showLog = true })
            }
            // Only problems the user can fix (e.g. Bluetooth off, or not connecting yet);
            // per-light status is the dot.
            if (state.status == LightRig.Status.OFFLINE && state.lights.isNotEmpty()) {
                Text(
                    (state.statusError ?: "Not connected") + " · tap to connect",
                    Modifier.clickable(onClick = vm::reconnect),
                    style = MaterialTheme.typography.bodySmall, color = WarnAmber,
                )
            }
            state.add?.let { AddLightCard(it, vm) }
            // Drag a light by its handle to reorder; Match lists lights in this order.
            ReorderableColumn(state.lights, key = { it.unicast }, onReorder = vm::reorder) { node, handle ->
                LightControlCard(node, state, vm, handle)
            }
        }
        // Exposure while setting key and fill: it needs the IM150's light level.
        if (exposure.im150Status == Im150.Status.CONNECTED) {
            HorizontalDivider()
            ExposureBar(exposure, exposureVm)
        }
        HorizontalDivider()
        SceneBar(state, vm)
    }
    if (showLog) DiagnosticsDialog(state, onDismiss = { showLog = false })
}

@Composable
private fun LightsMenu(onLog: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Diagnostics log") }, onClick = { open = false; onLog() })
        }
    }
}

/** Save and load scenes: every light's settings at once. Always on screen. */
@Composable
private fun SceneBar(state: LightsUiState, vm: LightsViewModel) {
    var saving by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Scene?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { saving = true }, enabled = state.lights.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Text("Save scene")
        }
        Box(Modifier.weight(1f)) {
            FilledTonalButton(onClick = { loading = true }, enabled = state.scenes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Load scene")
            }
            DropdownMenu(expanded = loading, onDismissRequest = { loading = false }) {
                state.scenes.sortedByDescending { it.createdAt }.forEach { scene ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(scene.name)
                                Text("${scene.lights.size} light${if (scene.lights.size == 1) "" else "s"} · " +
                                    fmt.format(Date(scene.createdAt)), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { vm.loadScene(scene); loading = false },
                        trailingIcon = {
                            IconButton(onClick = { loading = false; deleting = scene }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${scene.name}")
                            }
                        },
                    )
                }
            }
        }
    }
    deleting?.let { scene ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${scene.name}?") },
            confirmButton = { TextButton(onClick = { vm.deleteScene(scene.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (saving) {
        val default = remember { "Scene " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) }
        var name by remember { mutableStateOf(default) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save scene") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.saveScene(name); saving = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
}

fun lightStatus(node: MeshNode, state: LightsUiState): LightRig.Status = when {
    state.status == LightRig.Status.ONLINE && node.unicast in state.reachable -> LightRig.Status.ONLINE
    state.status == LightRig.Status.CONNECTING -> LightRig.Status.CONNECTING
    else -> LightRig.Status.OFFLINE
}

/**
 * One light: a single header row (colour, name, summary, on/off) that expands to its
 * controls. Changes are sent as you move the controls.
 */
@Composable
private fun LightControlCard(node: MeshNode, state: LightsUiState, vm: LightsViewModel, handle: Modifier = Modifier) {
    val limits = node.model.limits
    val cctRange = limits.cctMin..limits.cctMax
    val status = lightStatus(node, state)
    val online = status == LightRig.Status.ONLINE
    val l = state.states[node.unicast] ?: LightRig.LightState(null, 5600, 0)
    val brightness = l.brightness ?: 20
    val hsi = l.mode == LightRig.Mode.HSI && node.model.rgb
    var expanded by rememberSaveable(node.unicast) { mutableStateOf(false) }
    fun send(s: LightRig.LightState) = vm.setLight(node.unicast, s.copy(brightness = s.brightness ?: brightness))
    fun set(cct: Int = l.cct, gm: Int = l.gm) =
        send(l.copy(cct = cct.coerceIn(cctRange), gm = gm, mode = LightRig.Mode.CCT))
    fun setHsi(hue: Int = l.hue, sat: Int = l.sat) = send(l.copy(hue = hue, sat = sat, mode = LightRig.Mode.HSI))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder ${node.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = handle.padding(horizontal = 4.dp, vertical = 12.dp),
                )
                Row(
                    Modifier.weight(1f).clickable { expanded = !expanded }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val swatch = when {
                        !l.on -> Color(0xFF424242)
                        hsi -> Color.hsv(l.hue.toFloat(), l.sat / 100f, 1f)
                        else -> kelvinColor(l.cct)
                    }
                    Box(Modifier.size(26.dp).background(swatch, CircleShape))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Box(Modifier.padding(start = 8.dp).size(8.dp).background(statusColor(status), CircleShape))
                        }
                        Text(
                            (if (l.on) "${l.brightness?.let { "$it%" } ?: "—"} · " else "Off · ") +
                                if (hsi) "hue ${l.hue}°, sat ${l.sat}%"
                                else formatKelvin(l.cct.toDouble()) + if (limits.hasGm) " · G/M %+d".format(l.gm) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Hide controls" else "Show controls")
                }
                Switch(
                    checked = l.on,
                    onCheckedChange = { send(l.copy(on = it)) },
                    enabled = online,
                    modifier = Modifier.padding(start = 4.dp),
                )
                NodeMenu(node, vm)
            }
            state.notice?.takeIf { it.first == node.unicast }?.let {
                Text(it.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!expanded) return@Column
            Column(Modifier.padding(start = 8.dp, end = 12.dp)) {
                if (node.model.rgb) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !hsi, onClick = { set() }, enabled = online, label = { Text("White") })
                        FilterChip(selected = hsi, onClick = { setHsi() }, enabled = online, label = { Text("Colour") })
                    }
                }
                ValueControl("Brightness", brightness, 0..100, step = 1, format = { "$it%" }, enabled = online,
                    onChange = { send(l.copy(brightness = it, on = true)) }, track = brightnessTrack)
                if (hsi) {
                    HsiControls(l, online, onHue = { setHsi(hue = it) }, onSat = { setHsi(sat = it) })
                } else {
                    ValueControl(
                        "CCT", l.cct.coerceIn(cctRange), cctRange, step = 50, format = { formatKelvin(it.toDouble()) },
                        enabled = online, onChange = { set(cct = it) },
                        toSlider = { cctToSlider(it, cctRange) },
                        fromSlider = { sliderToCct(it, cctRange) },
                        track = cctTrack(cctRange),
                    )
                    if (limits.hasGm) {
                        val gmRange = limits.gmMin..limits.gmMax
                        ValueControl("G/M", l.gm.coerceIn(gmRange), gmRange, step = 1,
                            format = { if (it == 0) "0" else "%+d %s".format(it, if (it > 0) "G" else "M") },
                            enabled = online, onChange = { set(gm = it) }, track = gmTrack(gmRange))
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in listOf(2700, 3200, 4300, 5600, 6500).filter { it in cctRange }) {
                            SwatchButton("${k}K", kelvinColor(k), online, { set(cct = k) }, Modifier.weight(1f))
                        }
                        if (limits.hasGm) {
                            SwatchButton("G/M 0", Color(0xFFEEEEEE), online, { set(gm = 0) }, Modifier.weight(1f))
                        }
                    }
                }
            }
            state.controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun statusColor(status: LightRig.Status) = when (status) {
    LightRig.Status.ONLINE -> GoodGreen
    LightRig.Status.CONNECTING -> WarnAmber
    LightRig.Status.OFFLINE -> Color(0xFF9E9E9E)
}

@Composable
private fun NodeMenu(node: MeshNode, vm: LightsViewModel) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${node.name}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; renaming = true })
            DropdownMenuItem(text = { Text("Model: ${node.model.label}") }, onClick = { open = false; picking = true })
            DropdownMenuItem(text = { Text("Set up again") }, onClick = { open = false; vm.configure(node) })
            DropdownMenuItem(text = { Text("Read settings (diagnostics)") }, onClick = { open = false; vm.readAll(node) })
            DropdownMenuItem(text = { Text("Remove from app") }, onClick = { open = false; removing = true })
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(node.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename light") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(node, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Light model") },
            text = {
                Column {
                    LightModel.entries.forEach { m ->
                        Text(
                            "${m.label} · ${m.limits.cctMin}–${m.limits.cctMax} K" +
                                (if (m.limits.hasGm) ", G/M ±${m.limits.gmMax}" else ", no G/M") + if (m.rgb) ", RGB" else "",
                            Modifier.fillMaxWidth().clickable { vm.setModel(node, m); picking = false }.padding(vertical = 10.dp),
                            color = if (m == node.model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${node.name}?") },
            text = { Text("LightSet forgets this light. To add it again, or use it with NANLINK, reset its Bluetooth first.") },
            confirmButton = { TextButton(onClick = { vm.remove(node); removing = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

/** Scan for lights after a Bluetooth reset and join one to the network. */
@Composable
private fun AddLightCard(add: AddLightUi, vm: LightsViewModel) {
    var naming by remember { mutableStateOf<JoinCandidate?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a light", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (add.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = vm::closeAdd, enabled = add.joining == null) { Text("Done") }
            }
            Text(
                "Reset the light's Bluetooth from its menu (usually under Wireless/Bluetooth → Reset). " +
                    "It then appears here; tap it to join it to LightSet. A light in NANLINK must be reset first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            add.candidates.forEach { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name ?: "Unnamed light")
                        Text("${c.address} · ${c.rssi} dBm", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (add.joining == c.address) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Button(onClick = { naming = c }, enabled = add.joining == null) { Text("Join") }
                    }
                }
            }
            if (add.candidates.isEmpty() && add.joining == null) {
                Text(if (add.scanning) "Looking for lights waiting to join…" else "None found.",
                    style = MaterialTheme.typography.bodyMedium)
                if (!add.scanning) TextButton(onClick = vm::startAdd) { Text("Scan again") }
            }
            add.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            add.added?.let { Text("Added $it", color = GoodGreen) }
            add.error?.let { Text(it, color = WarnAmber) }
        }
    }
    naming?.let { c ->
        var name by remember(c) { mutableStateOf(c.name ?: "Light") }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Name this light") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                    Text("Model: ${LightModel.fromName(c.name).label} (change later in its menu)",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.join(c, name); naming = null }) { Text("Join") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HsiControls(l: LightRig.LightState, enabled: Boolean, onHue: (Int) -> Unit, onSat: (Int) -> Unit) {
    ValueControl("Hue", l.hue, 0..359, step = 1, format = { "$it°" }, enabled = enabled, onChange = onHue, track = hueTrack)
    ValueControl("Saturation", l.sat, 0..100, step = 1, format = { "$it%" }, enabled = enabled, onChange = onSat,
        track = satTrack(l.hue))
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((name, hue) in listOf("Red" to 0, "Amber" to 35, "Green" to 120, "Cyan" to 180, "Blue" to 240, "Magenta" to 300)) {
            SwatchButton(name, Color.hsv(hue.toFloat(), 1f, 1f), enabled, { onHue(hue) }, Modifier.weight(1f))
        }
    }
}

/** Raw Bluetooth log, from the Lights menu. */
@Composable
private fun DiagnosticsDialog(state: LightsUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics log") },
        text = {
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                Text(state.log.takeLast(80).joinToString("\n").ifEmpty { "(empty)" },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
