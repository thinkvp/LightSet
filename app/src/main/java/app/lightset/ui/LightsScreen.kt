package app.lightset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lightset.LightRig
import app.lightset.mesh.LightModel
import app.lightset.mesh.MeshNode

@Composable
fun LightsScreen(vm: LightsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
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
        }
        if (state.status == LightRig.Status.OFFLINE && state.lights.isNotEmpty()) {
            Text(
                (state.statusError ?: "Not connected") + " · tap a light's status to retry",
                style = MaterialTheme.typography.bodySmall, color = WarnAmber,
            )
        }
        state.add?.let { AddLightCard(it, vm) }
        state.lights.forEach { node ->
            LightControlCard(node, state, vm)
        }
        Text(
            "Close NANLINK while using LightSet: a light accepts one Bluetooth connection at a time. " +
                "A light's own knobs are overridden whenever the app sends it a setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Diagnostics(state)
    }
}

fun lightStatus(node: MeshNode, state: LightsUiState): LightRig.Status = when {
    state.status == LightRig.Status.ONLINE && node.unicast in state.reachable -> LightRig.Status.ONLINE
    state.status == LightRig.Status.CONNECTING -> LightRig.Status.CONNECTING
    else -> LightRig.Status.OFFLINE
}

/** Manual control of one light, sent as you move the controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightControlCard(node: MeshNode, state: LightsUiState, vm: LightsViewModel) {
    val limits = node.model.limits
    val cctRange = limits.cctMin..limits.cctMax
    val status = lightStatus(node, state)
    // Controls work whenever we're connected to the network: some lights act on
    // settings but never answer the status check.
    val online = state.status == LightRig.Status.ONLINE
    val l = state.states[node.unicast] ?: LightRig.LightState(null, 5600, 0)
    val brightness = l.brightness ?: 20
    var expanded by rememberSaveable(node.unicast) { mutableStateOf(true) }
    fun set(b: Int = brightness, cct: Int = l.cct, gm: Int = l.gm) =
        vm.setLight(node.unicast, l.copy(brightness = b, cct = cct.coerceIn(cctRange), gm = gm, mode = LightRig.Mode.CCT))
    fun setHsi(b: Int = brightness, hue: Int = l.hue, sat: Int = l.sat) =
        vm.setLight(node.unicast, l.copy(brightness = b, hue = hue, sat = sat, mode = LightRig.Mode.HSI))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${node.model.label} · " + if (l.mode == LightRig.Mode.HSI) "colour ${l.hue}°, ${l.sat}%"
                        else "${l.brightness?.let { "$it%" } ?: "—"} · ${formatKelvin(l.cct.toDouble())}" +
                            if (limits.hasGm) " · G/M %+d".format(l.gm) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LightStatusChip("", status, onRetry = vm::reconnect,
                    offlineLabel = if (state.status == LightRig.Status.ONLINE) "No reply" else "Offline · tap to retry")
                NodeMenu(node, vm)
            }
            state.notice?.takeIf { it.first == node.unicast }?.let {
                Text(it.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (status == LightRig.Status.OFFLINE && state.status == LightRig.Status.ONLINE) {
                Text("No reply from this light — it may be off or out of range. If it's on, try ⋮ → Set up again",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!expanded) return@Column
            if (node.model.rgb) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = l.mode == LightRig.Mode.CCT, onClick = { set() }, enabled = online,
                        label = { Text("White (CCT)") })
                    FilterChip(selected = l.mode == LightRig.Mode.HSI, onClick = { setHsi() }, enabled = online,
                        label = { Text("Colour (HSI)") })
                }
            }
            ValueControl("Brightness", brightness, 0..100, step = 1, format = { "$it%" }, enabled = online,
                onChange = { if (l.mode == LightRig.Mode.CCT) set(b = it) else setHsi(b = it) })
            if (l.mode == LightRig.Mode.HSI && node.model.rgb) {
                HsiControls(l, online, onHue = { setHsi(hue = it) }, onSat = { setHsi(sat = it) })
            } else {
                ValueControl(
                    "CCT", l.cct.coerceIn(cctRange), cctRange, step = 50, format = { formatKelvin(it.toDouble()) },
                    enabled = online, onChange = { set(cct = it) },
                    toSlider = { cctToSlider(it, cctRange) },
                    fromSlider = { sliderToCct(it, cctRange) },
                )
                if (limits.hasGm) {
                    ValueControl("G/M", l.gm.coerceIn(limits.gmMin, limits.gmMax), limits.gmMin..limits.gmMax, step = 1,
                        format = { if (it == 0) "0" else "%+d %s".format(it, if (it > 0) "G" else "M") },
                        enabled = online, onChange = { set(gm = it) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf(2700, 3200, 4300, 5600, 6500).filter { it in cctRange }) {
                        AssistChip(onClick = { set(cct = k) }, enabled = online, label = { Text(formatKelvin(k.toDouble())) })
                    }
                    if (limits.hasGm) AssistChip(onClick = { set(gm = 0) }, enabled = online, label = { Text("G/M 0") })
                }
            }
            state.controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HsiControls(l: LightRig.LightState, enabled: Boolean, onHue: (Int) -> Unit, onSat: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(Color.hsv(l.hue.toFloat(), l.sat / 100f, 1f), CircleShape))
        Text("  hue ${l.hue}°, saturation ${l.sat}%", style = MaterialTheme.typography.bodyMedium)
    }
    // Rainbow strip so the hue slider reads at a glance.
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp).height(6.dp)
            .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }), RoundedCornerShape(3.dp))
    )
    ValueControl("Hue", l.hue, 0..359, step = 1, format = { "$it°" }, enabled = enabled, onChange = onHue)
    ValueControl("Saturation", l.sat, 0..100, step = 1, format = { "$it%" }, enabled = enabled, onChange = onSat)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, hue) in listOf("Red" to 0, "Amber" to 35, "Green" to 120, "Cyan" to 180, "Blue" to 240, "Magenta" to 300)) {
            AssistChip(
                onClick = { onHue(hue) },
                enabled = enabled,
                label = { Text(name) },
                leadingIcon = { Box(Modifier.size(12.dp).background(Color.hsv(hue.toFloat(), 1f, 1f), CircleShape)) },
            )
        }
    }
}

/** Raw Bluetooth log, folded away. */
@Composable
private fun Diagnostics(state: LightsUiState) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Diagnostics log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (!open) return
    HorizontalDivider()
    SelectionContainer {
        Text(state.log.takeLast(80).joinToString("\n").ifEmpty { "(empty)" },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
