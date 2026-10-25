@file:OptIn(ExperimentalMaterial3Api::class)

package cl.jpchacon.ampolleta.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.jpchacon.ampolleta.ble.BulbManager
import cl.jpchacon.ampolleta.ble.BulbUi
import cl.jpchacon.ampolleta.ble.Discovered
import cl.jpchacon.ampolleta.ble.LinkStatus
import cl.jpchacon.ampolleta.tuya.Light
import cl.jpchacon.ampolleta.tuya.LightMode
import cl.jpchacon.ampolleta.tuya.LightState
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Log : Screen
    data class Control(val mac: String?) : Screen // null = todas
}

@Composable
fun AmpolletaUi(manager: BulbManager) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFF0B35A))
        else -> lightColorScheme(primary = Color(0xFFB8741A))
    }
    MaterialTheme(colorScheme = colors) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
        when (val s = screen) {
            Screen.Home -> HomeScreen(manager, onOpen = { screen = Screen.Control(it) }, onAdd = { screen = Screen.Add }, onLog = { screen = Screen.Log })
            Screen.Add -> AddScreen(manager, onDone = { screen = Screen.Home })
            Screen.Log -> LogScreen(manager, onBack = { screen = Screen.Home })
            is Screen.Control -> ControlScreen(manager, s.mac, onBack = { screen = Screen.Home })
        }
    }
}

// ---------- utilidades ----------

private fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun glow(l: LightState): Color = when {
    !l.on -> Color(0xFF55585E)
    l.mode == LightMode.COLOUR -> hsv(l.hue.toFloat(), l.saturation / 100f, 1f)
    else -> hsv(35f - l.temperature * 0.15f, (60f - l.temperature * 0.55f) / 100f, 1f)
}

@Composable
private fun BulbDot(l: LightState, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(glow(l))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

private fun statusText(b: BulbUi) = when (b.status) {
    LinkStatus.CONNECTED -> "Conectada"
    LinkStatus.CONNECTING -> "Conectando…"
    LinkStatus.SEARCHING -> "Buscando…"
    LinkStatus.ERROR -> "No responde: ${b.message ?: "error"}"
}

@Composable
private fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        },
        actions = { actions() },
    )
}

// ---------- inicio ----------

@Composable
private fun HomeScreen(manager: BulbManager, onOpen: (String?) -> Unit, onAdd: () -> Unit, onLog: () -> Unit) {
    val bulbs by manager.bulbs.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis ampolletas") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Registro") }, onClick = { menu = false; onLog() })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Agregar") })
        },
    ) { padding ->
        val list = bulbs.values.sortedBy { it.name }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Todavía no tienes ampolletas", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Toca \"Agregar\" con la ampolleta encendida y cerca del teléfono.")
                        }
                    }
                }
            }
            if (list.size >= 2) {
                item {
                    val anyOn = list.any { it.light.on }
                    Card(Modifier.fillMaxWidth().clickable { onOpen(null) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Todas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(checked = anyOn, onCheckedChange = { on ->
                                list.forEach { b -> manager.setLight(b.mac, { it.copy(on = on) }) { p, s -> listOf(Light.powerDp(p, s)) } }
                            })
                        }
                    }
                }
            }
            items(list, key = { it.mac }) { b ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.mac) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BulbDot(b.light)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium)
                            Text(statusText(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = b.light.on,
                            enabled = b.status == LinkStatus.CONNECTED,
                            onCheckedChange = { on -> manager.setLight(b.mac, { it.copy(on = on) }) { p, s -> listOf(Light.powerDp(p, s)) } },
                        )
                    }
                }
            }
        }
    }
}

// ---------- agregar ----------

@Composable
private fun AddScreen(manager: BulbManager, onDone: () -> Unit) {
    val discovered by manager.discovered.collectAsState()
    val bulbs by manager.bulbs.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidates = discovered.values.filter { it.mac !in bulbs }.sortedByDescending { it.rssi }

    Scaffold(topBar = { BackBar("Agregar ampolleta", onDone) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Las ampolletas sin vincular se pueden agregar directamente. Si una aparece vinculada a otra app, " +
                        "reiníciala: apaga y prende el interruptor 3 veces seguidas hasta que parpadee.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (candidates.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando ampolletas…")
                    }
                }
            }
            items(candidates, key = { it.mac }) { d -> CandidateCard(d, busy == d.mac, enabled = busy == null) {
                busy = d.mac
                error = null
                scope.launch {
                    val r = manager.add(d)
                    busy = null
                    if (r.isSuccess) onDone() else error = "No se pudo agregar: ${r.exceptionOrNull()?.message}"
                }
            } }
        }
    }
}

@Composable
private fun CandidateCard(d: Discovered, busy: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${d.name} · ${d.mac}", style = MaterialTheme.typography.titleSmall)
                Text(
                    (if (d.adv.bound) "Vinculada a otra app" else "Sin vincular") + " · señal ${d.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = onAdd, enabled = enabled && !d.adv.bound && d.adv.uuid != null) { Text("Agregar") }
            }
        }
    }
}

// ---------- control ----------

private val SWATCHES = listOf(0, 30, 55, 120, 195, 230, 270, 320)

@Composable
private fun ControlScreen(manager: BulbManager, mac: String?, onBack: () -> Unit) {
    val bulbs by manager.bulbs.collectAsState()
    val scope = rememberCoroutineScope()
    val targets = if (mac == null) bulbs.values.toList() else listOfNotNull(bulbs[mac])
    val main = targets.firstOrNull() ?: run { onBack(); return }
    val l = main.light
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    fun set(change: (LightState) -> LightState, dps: (cl.jpchacon.ampolleta.tuya.LightProfile, LightState) -> List<cl.jpchacon.ampolleta.tuya.Datapoint>) {
        targets.forEach { manager.setLight(it.mac, change, dps) }
    }

    Scaffold(topBar = {
        BackBar(if (mac == null) "Todas" else main.name, onBack) {
            if (mac != null) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Quitar ampolleta") }, onClick = { menu = false; confirmForget = true })
                }
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (mac != null) Text(statusText(main), color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                BulbDot(l, 44)
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(
                    onClick = { set({ it.copy(on = !l.on) }) { p, s -> listOf(Light.powerDp(p, s)) } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(if (l.on) "Apagar" else "Encender", style = MaterialTheme.typography.titleMedium) }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = l.mode == LightMode.WHITE,
                    onClick = { set({ it.copy(mode = LightMode.WHITE, on = true) }) { p, s -> Light.fullState(p, s) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Blanco") }
                SegmentedButton(
                    selected = l.mode == LightMode.COLOUR,
                    onClick = { set({ it.copy(mode = LightMode.COLOUR, on = true) }) { p, s -> Light.fullState(p, s) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Color") }
            }

            LabeledSlider("Brillo", l.brightness, 1..100) { v ->
                set({ it.copy(brightness = v) }) { p, s -> listOf(if (s.mode == LightMode.COLOUR) Light.colourDp(p, s) else Light.brightDp(p, s)) }
            }

            if (l.mode == LightMode.WHITE) {
                GradientBar(listOf(hsv(35f, 0.6f, 1f), Color.White, hsv(210f, 0.15f, 1f)))
                LabeledSlider("Temperatura", l.temperature, 0..100) { v ->
                    set({ it.copy(temperature = v) }) { p, s -> listOf(Light.tempDp(p, s)) }
                }
            } else {
                GradientBar((0..6).map { hsv(it * 60f, 1f, 1f) })
                LabeledSlider("Tono", l.hue, 0..360) { v ->
                    set({ it.copy(hue = v) }) { p, s -> listOf(Light.colourDp(p, s)) }
                }
                LabeledSlider("Saturación", l.saturation, 0..100) { v ->
                    set({ it.copy(saturation = v) }) { p, s -> listOf(Light.colourDp(p, s)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SWATCHES.forEach { h ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(hsv(h.toFloat(), 1f, 1f))
                                .clickable {
                                    set({ it.copy(hue = h, saturation = 100, mode = LightMode.COLOUR, on = true) }) { p, s -> Light.fullState(p, s) }
                                },
                        )
                    }
                }
            }
        }
    }

    if (renaming && mac != null) {
        var text by remember { mutableStateOf(main.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nombre") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) manager.rename(mac, text.trim())
                    renaming = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancelar") } },
        )
    }

    if (confirmForget && mac != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Quitar ampolleta") },
            text = { Text("Al quitarla también se desvincula, para que puedas volver a agregarla aquí o en Smart Life.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch {
                        manager.forget(mac, unbind = true)
                        onBack()
                    }
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("$value", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun GradientBar(colors: List<Color>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.horizontalGradient(colors)),
    )
}

// ---------- registro ----------

@Composable
private fun LogScreen(manager: BulbManager, onBack: () -> Unit) {
    val log by manager.log.collectAsState()
    Scaffold(topBar = { BackBar("Registro", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            if (log.isEmpty()) item { Text("Sin eventos todavía.") }
            items(log) { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal) }
        }
    }
}
