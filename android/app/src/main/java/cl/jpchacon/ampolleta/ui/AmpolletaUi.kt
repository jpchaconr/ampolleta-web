@file:OptIn(ExperimentalMaterial3Api::class)

package cl.jpchacon.ampolleta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.jpchacon.ampolleta.ac.AcManager
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
    data object AddAc : Screen
    data object SetupAc : Screen
    data class Ac(val mac: String) : Screen
}

@Composable
fun AmpolletaUi(manager: BulbManager, acManager: AcManager) {
    AmpolletaTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                manager, acManager,
                onOpen = { screen = Screen.Control(it) },
                onOpenAc = { screen = Screen.Ac(it) },
                onAdd = { screen = Screen.Add },
                onAddAc = { screen = Screen.AddAc },
                onLog = { screen = Screen.Log },
            )
            Screen.Add -> AddScreen(manager, onDone = { screen = Screen.Home })
            Screen.AddAc -> AddAcScreen(acManager, onDone = { screen = Screen.Home }, onSetup = { screen = Screen.SetupAc })
            Screen.SetupAc -> SetupAcScreen(acManager, onBack = { screen = Screen.AddAc }, onDone = { screen = Screen.Home })
            is Screen.Ac -> AcScreen(acManager, s.mac, onBack = { screen = Screen.Home })
            Screen.Log -> LogScreen(manager, onBack = { screen = Screen.Home })
            is Screen.Control -> ControlScreen(manager, s.mac, onBack = { screen = Screen.Home })
        }
    }
}

// ---------- utilidades ----------

private fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun glow(l: LightState): Color = when {
    l.mode == LightMode.COLOUR -> hsv(l.hue.toFloat(), l.saturation / 100f, 1f)
    else -> hsv(35f - l.temperature * 0.15f, (60f - l.temperature * 0.55f) / 100f, 1f)
}

private fun statusText(b: BulbUi) = when (b.status) {
    LinkStatus.CONNECTED -> if (b.light.on) "Encendida · ${b.light.brightness} %" else "Apagada"
    LinkStatus.CONNECTING -> "Conectando…"
    LinkStatus.SEARCHING -> "Buscando…"
    LinkStatus.ERROR -> "No responde: ${b.message ?: "error"}"
}

private fun power(manager: BulbManager, mac: String, on: Boolean) =
    manager.setLight(mac, { it.copy(on = on) }) { p, s -> listOf(Light.powerDp(p, s)) }

// ---------- inicio ----------

@Composable
private fun HomeScreen(
    manager: BulbManager,
    acManager: AcManager,
    onOpen: (String?) -> Unit,
    onOpenAc: (String) -> Unit,
    onAdd: () -> Unit,
    onAddAc: () -> Unit,
    onLog: () -> Unit,
) {
    val bulbs by manager.bulbs.collectAsState()
    val acs by acManager.acs.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val list = bulbs.values.sortedBy { it.name }
    val airs = acs.values.sortedBy { it.name }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Mi casa") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Registro") }, onClick = { menu = false; onLog() })
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { choosing = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Agregar") })
        },
    ) { padding ->
        if (list.isEmpty() && airs.isEmpty()) {
            EmptyHome(Modifier.padding(padding)) { choosing = true }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (list.isNotEmpty()) item(key = "t-amp") { SectionTitle("Ampolletas") }
                if (list.size >= 2) {
                    item(key = "todas") {
                        val connected = list.filter { it.status == LinkStatus.CONNECTED }
                        val lit = list.count { it.light.on }
                        DeviceTile(
                            title = "Todas",
                            subtitle = if (lit == 0) "Todas apagadas" else "$lit de ${list.size} encendidas",
                            icon = AppIcons.Lightbulb,
                            accent = glow(list.first { it.light.on || lit == 0 }.light),
                            on = lit > 0,
                            available = connected.isNotEmpty(),
                            problem = false,
                            onOpen = { onOpen(null) },
                            onToggle = { on -> list.forEach { power(manager, it.mac, on) } },
                        )
                    }
                }
                items(list, key = { it.mac }) { b ->
                    DeviceTile(
                        title = b.name,
                        subtitle = statusText(b),
                        icon = AppIcons.Lightbulb,
                        accent = glow(b.light),
                        on = b.light.on,
                        available = b.status == LinkStatus.CONNECTED,
                        problem = b.status == LinkStatus.ERROR,
                        onOpen = { onOpen(b.mac) },
                        onToggle = { on -> power(manager, b.mac, on) },
                    )
                }
                if (airs.isNotEmpty()) item(key = "t-ac") { SectionTitle("Aire acondicionado") }
                items(airs, key = { "ac-" + it.mac }) { a ->
                    AcCard(a, acManager) { onOpenAc(a.mac) }
                }
            }
        }
    }

    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    "¿Qué quieres agregar?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                AddOption(AppIcons.Lightbulb, "Ampolleta", "Tuya por Bluetooth: encendida y cerca del teléfono") {
                    choosing = false; onAdd()
                }
                AddOption(AppIcons.AcUnit, "Aire acondicionado", "Kendal o AUX con Wi-Fi, en la misma red que el teléfono") {
                    choosing = false; onAddAc()
                }
            }
        }
    }
}

@Composable
private fun AddOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { IconBubble(icon, MaterialTheme.colorScheme.primaryContainer, 44) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun EmptyHome(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBubble(AppIcons.Lightbulb, MaterialTheme.colorScheme.primaryContainer, 96)
        Spacer(Modifier.height(24.dp))
        Text("Todavía no tienes equipos", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Agrega una ampolleta (encendida y cerca del teléfono) o un aire acondicionado con Wi-Fi.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Agregar equipo")
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
                Banner(
                    "Las ampolletas sin vincular se agregan directo. Si una aparece vinculada a otra app, " +
                        "reiníciala: apaga y prende el interruptor 3 veces seguidas hasta que parpadee.",
                )
            }
            error?.let { item { Banner(it, BannerKind.ERROR) } }
            item { LoadingRow(if (candidates.isEmpty()) "Buscando ampolletas cerca…" else "Sigo buscando…") }
            items(candidates, key = { it.mac }) { d ->
                CandidateCard(d, busy == d.mac, enabled = busy == null) {
                    busy = d.mac
                    error = null
                    scope.launch {
                        val r = manager.add(d)
                        busy = null
                        if (r.isSuccess) onDone() else error = "No se pudo agregar: ${r.exceptionOrNull()?.message}"
                    }
                }
            }
        }
    }
}

/** La señal en palabras, a partir del RSSI. */
private fun bars(rssi: Int) = when {
    rssi >= -60 -> "Señal excelente"
    rssi >= -70 -> "Señal buena"
    rssi >= -80 -> "Señal regular"
    else -> "Señal débil"
}

@Composable
private fun CandidateCard(d: Discovered, busy: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    val addable = !d.adv.bound && d.adv.uuid != null
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(AppIcons.Lightbulb, if (addable) MaterialTheme.colorScheme.primaryContainer else null, 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (d.adv.bound) "Vinculada a otra app" else "Sin vincular") + " · ${bars(d.rssi)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (d.adv.bound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(d.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = onAdd, enabled = enabled && addable) { Text("Agregar") }
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
        BackBar(if (mac == null) "Todas las ampolletas" else main.name, onBack) {
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (mac != null && main.status != LinkStatus.CONNECTED) {
                Banner(statusText(main), if (main.status == LinkStatus.ERROR) BannerKind.ERROR else BannerKind.WAIT)
            }

            BulbHero(l) { set({ it.copy(on = !l.on) }) { p, s -> listOf(Light.powerDp(p, s)) } }

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

            SectionCard {
                LabeledSlider("Brillo", l.brightness, 1..100, unit = " %") { v ->
                    set({ it.copy(brightness = v) }) { p, s -> listOf(if (s.mode == LightMode.COLOUR) Light.colourDp(p, s) else Light.brightDp(p, s)) }
                }
                if (l.mode == LightMode.WHITE) {
                    LabeledSlider(
                        "Temperatura", l.temperature, 0..100,
                        gradient = listOf(hsv(35f, 0.6f, 1f), Color.White, hsv(210f, 0.15f, 1f)),
                        valueText = if (l.temperature < 34) "Cálida" else if (l.temperature < 67) "Neutra" else "Fría",
                    ) { v ->
                        set({ it.copy(temperature = v) }) { p, s -> listOf(Light.tempDp(p, s)) }
                    }
                } else {
                    LabeledSlider("Tono", l.hue, 0..360, unit = "°", gradient = (0..6).map { hsv(it * 60f, 1f, 1f) }) { v ->
                        set({ it.copy(hue = v) }) { p, s -> listOf(Light.colourDp(p, s)) }
                    }
                    LabeledSlider(
                        "Saturación", l.saturation, 0..100, unit = " %",
                        gradient = listOf(Color.White, hsv(l.hue.toFloat(), 1f, 1f)),
                    ) { v ->
                        set({ it.copy(saturation = v) }) { p, s -> listOf(Light.colourDp(p, s)) }
                    }
                }
            }

            SectionCard("Colores rápidos") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SWATCHES.forEach { h ->
                        val selected = l.mode == LightMode.COLOUR && l.hue == h && l.saturation == 100
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(hsv(h.toFloat(), 1f, 1f))
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .semantics { contentDescription = "Color $h°" }
                                .clickable {
                                    set({ it.copy(hue = h, saturation = 100, mode = LightMode.COLOUR, on = true) }) { p, s -> Light.fullState(p, s) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Default.Check, null, tint = Color.Black.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    if (renaming && mac != null) {
        var text by remember { mutableStateOf(main.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Cambiar nombre") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Nombre") }) },
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
            title = { Text("¿Quitar ampolleta?") },
            text = { Text("Al quitarla también se desvincula, para que puedas volver a agregarla aquí o en Smart Life.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch {
                        manager.forget(mac, unbind = true)
                        onBack()
                    }
                }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

/** La ampolleta grande, que brilla con su color; se toca para prenderla o apagarla. */
@Composable
private fun BulbHero(l: LightState, onToggle: () -> Unit) {
    val lightColor = glow(l)
    val fill by animateColorAsState(if (l.on) lightColor else MaterialTheme.colorScheme.surfaceContainerHighest, label = "ampolleta")
    val halo by animateColorAsState(if (l.on) lightColor.copy(alpha = 0.18f + l.brightness / 250f) else Color.Transparent, label = "halo")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(halo, Color.Transparent)), CircleShape))
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = fill,
                shadowElevation = if (l.on) 6.dp else 0.dp,
                modifier = Modifier.size(132.dp).semantics { contentDescription = if (l.on) "Apagar" else "Encender" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (l.on) AppIcons.Lightbulb else AppIcons.Power,
                        contentDescription = null,
                        tint = if (l.on) Color.Black.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
        }
        Text(if (l.on) "Encendida" else "Apagada", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (l.on) "Toca la ampolleta para apagarla" else "Toca para encenderla",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    range: IntRange,
    unit: String = "",
    gradient: List<Color>? = null,
    valueText: String? = null,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText ?: "$value$unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (gradient != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(gradient)),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

// ---------- registro ----------

@Composable
private fun LogScreen(manager: BulbManager, onBack: () -> Unit) {
    val log by manager.log.collectAsState()
    Scaffold(topBar = { BackBar("Registro", onBack) }) { padding ->
        if (log.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sin eventos todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(log) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}
