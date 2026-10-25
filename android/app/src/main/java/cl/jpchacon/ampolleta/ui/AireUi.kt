@file:OptIn(ExperimentalMaterial3Api::class)

package cl.jpchacon.ampolleta.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.jpchacon.ampolleta.ac.AcManager
import cl.jpchacon.ampolleta.ac.AcMode
import cl.jpchacon.ampolleta.ac.AcState
import cl.jpchacon.ampolleta.ac.AcStatus
import cl.jpchacon.ampolleta.ac.AcUi
import cl.jpchacon.ampolleta.ac.Broadlink
import cl.jpchacon.ampolleta.ac.FanSpeed
import cl.jpchacon.ampolleta.ac.FoundDevice
import cl.jpchacon.ampolleta.ac.HVAC_DEVTYPE
import kotlinx.coroutines.launch

// ---------- utilidades ----------

private val MODES = listOf(AcMode.COOL to "Frío", AcMode.HEAT to "Calor", AcMode.DRY to "Seco", AcMode.FAN to "Ventilar", AcMode.AUTO to "Auto")
private val SPEEDS = listOf(FanSpeed.AUTO to "Auto", FanSpeed.LOW to "Baja", FanSpeed.MID to "Media", FanSpeed.HIGH to "Alta")

private fun acColor(s: AcState): Color = when {
    !s.power -> Color(0xFF55585E)
    s.mode == AcMode.HEAT -> Color(0xFFFF7A33)
    s.mode == AcMode.FAN -> Color(0xFF9AA4B0)
    else -> Color(0xFF3FA9F5)
}

private fun temp(t: Double) = if (t % 1.0 == 0.0) "${t.toInt()}°" else "%.1f°".format(t)

fun acStatusText(a: AcUi) = when (a.status) {
    AcStatus.CONNECTED -> listOfNotNull(
        if (a.state.power) "${MODES.first { it.first == a.state.mode }.second} a ${temp(a.state.targetTemp)}" else "Apagado",
        a.ambient?.let { "ambiente ${temp(it)}" },
    ).joinToString(" · ")
    AcStatus.CONNECTING -> "Conectando…"
    AcStatus.ERROR -> "No responde: ${a.message ?: "error"}"
}

@Composable
private fun AcDot(s: AcState, size: Int = 28) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(acColor(s))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("❄", color = Color.White, fontSize = (size * 0.5).sp) }
}

// ---------- tarjeta en el inicio ----------

@Composable
fun AcCard(a: AcUi, manager: AcManager, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AcDot(a.state)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium)
                Text(acStatusText(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = a.state.power,
                enabled = a.status == AcStatus.CONNECTED,
                onCheckedChange = { on -> manager.set(a.mac) { it.copy(power = on) } },
            )
        }
    }
}

// ---------- agregar ----------

@Composable
fun AddAcScreen(manager: AcManager, onDone: () -> Unit, onSetup: () -> Unit) {
    val acs by manager.acs.collectAsState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<FoundDevice>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round) {
        searching = true
        found = manager.discover()
        searching = false
    }
    val candidates = found.filter { it.macText !in acs }

    Scaffold(topBar = { BackBar("Agregar aire", onDone) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        "Busco los aires conectados a tu Wi-Fi; el teléfono tiene que estar en ese mismo Wi-Fi. " +
                            "Si el aire todavía no está en tu Wi-Fi, o aparece bloqueado por AC Freedom, vincúlalo primero.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSetup) { Text("Configurar Wi-Fi de un aire") }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (searching) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando en tu Wi-Fi…")
                    }
                }
            } else if (candidates.isEmpty()) {
                item {
                    Column {
                        Text("No encontré aires nuevos.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { round++ }) { Text("Buscar otra vez") }
                    }
                }
            }
            items(candidates, key = { it.macText }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name.ifBlank { "Equipo Broadlink" }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    d.host,
                                    d.macText,
                                    if (d.devtype != HVAC_DEVTYPE) "tipo 0x%04X, puede que no sea un aire".format(d.devtype) else null,
                                    if (d.locked) "bloqueado en AC Freedom" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (busy == d.macText) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(enabled = busy == null, onClick = {
                                busy = d.macText
                                error = null
                                scope.launch {
                                    val r = manager.add(d)
                                    busy = null
                                    if (r.isSuccess) onDone() else error = "No se pudo agregar: ${r.exceptionOrNull()?.message}"
                                }
                            }) { Text("Agregar") }
                        }
                    }
                }
            }
        }
    }
}

// ---------- vincular al Wi-Fi sin AC Freedom ----------

private val SECURITIES = listOf(
    Broadlink.WifiSecurity.WPA2 to "WPA2",
    Broadlink.WifiSecurity.WPA_WPA2 to "WPA/WPA2",
    Broadlink.WifiSecurity.WPA to "WPA",
    Broadlink.WifiSecurity.NONE to "Sin clave",
)

@Composable
fun SetupAcScreen(manager: AcManager, onBack: () -> Unit, onSearch: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(Broadlink.WifiSecurity.WPA2) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<Unit>?>(null) }

    Scaffold(topBar = { BackBar("Configurar Wi-Fi del aire", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Así el aire queda en tu Wi-Fi sin pasar por AC Freedom, y sin bloqueo.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "1. Pon el aire en modo vinculación: con el control, presiona 8 veces el botón \"Health\" hasta que pite " +
                    "(si tu manual indica otro botón, usa ese).\n" +
                    "2. En los ajustes de Wi-Fi del teléfono debería aparecer una red nueva del aire. Si no aparece, repite el paso 1: " +
                    "en muchos módulos la segunda vez pasa al modo que crea esa red.\n" +
                    "3. Conéctate a esa red. No tiene internet: si Android pregunta, elige mantener la conexión.\n" +
                    "4. Vuelve aquí, escribe tu Wi-Fi de casa (tiene que ser de 2,4 GHz) y toca \"Enviar al aire\".\n" +
                    "5. Vuelve a conectar el teléfono a tu Wi-Fi de casa y busca el aire.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Abrir ajustes de Wi-Fi") }

            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it }, singleLine = true,
                label = { Text("Nombre de tu Wi-Fi") }, modifier = Modifier.fillMaxWidth(),
            )
            if (security != Broadlink.WifiSecurity.NONE) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Clave") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SECURITIES.forEachIndexed { i, (sec, label) ->
                    SegmentedButton(
                        selected = security == sec,
                        onClick = { security = sec },
                        shape = SegmentedButtonDefaults.itemShape(i, SECURITIES.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Button(
                enabled = !busy && ssid.isNotBlank(),
                onClick = {
                    busy = true
                    result = null
                    scope.launch {
                        result = manager.provision(ssid.trim(), if (security == Broadlink.WifiSecurity.NONE) "" else password, security)
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Enviando…" else "Enviar al aire") }

            result?.let { r ->
                if (r.isSuccess) {
                    Text(
                        "Listo, se lo envié. El aire se reinicia y se conecta a \"$ssid\" en menos de un minuto. " +
                            "Ahora vuelve a conectar el teléfono a ese Wi-Fi y busca el aire.",
                    )
                    Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text("Buscar aires") }
                } else {
                    Text("No se pudo enviar: ${r.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------- control ----------

@Composable
fun AcScreen(manager: AcManager, mac: String, onBack: () -> Unit) {
    val acs by manager.acs.collectAsState()
    val a = acs[mac] ?: run { onBack(); return }
    val s = a.state
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val set = { change: (AcState) -> AcState -> manager.set(mac, change) }

    Scaffold(topBar = {
        BackBar(a.name, onBack) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text("Quitar aire") }, onClick = { menu = false; confirmForget = true })
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(acStatusText(a), color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically) {
                AcDot(s, 44)
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(
                    onClick = { set { it.copy(power = !s.power) } },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) { Text(if (s.power) "Apagar" else "Encender", style = MaterialTheme.typography.titleMedium) }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { set { it.copy(targetTemp = (it.targetTemp - 1).coerceAtLeast(AcState.MIN_TEMP)) } },
                    modifier = Modifier.size(64.dp),
                ) { Text("−", fontSize = 28.sp) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(temp(s.targetTemp), fontSize = 52.sp, fontWeight = FontWeight.Bold)
                    a.ambient?.let { Text("Ambiente ${temp(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                FilledTonalIconButton(
                    onClick = { set { it.copy(targetTemp = (it.targetTemp + 1).coerceAtMost(AcState.MAX_TEMP)) } },
                    modifier = Modifier.size(64.dp),
                ) { Text("+", fontSize = 28.sp) }
            }

            Text("Modo", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MODES.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = s.mode == mode,
                        onClick = { set { it.copy(mode = mode, power = true) } },
                        shape = SegmentedButtonDefaults.itemShape(i, MODES.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Text("Ventilador", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SPEEDS.forEachIndexed { i, (speed, label) ->
                    SegmentedButton(
                        selected = s.speed == speed && s.preset == 0,
                        // Elegir una velocidad saca el turbo o el silencio.
                        onClick = { set { it.copy(speed = speed, preset = 0) } },
                        shape = SegmentedButtonDefaults.itemShape(i, SPEEDS.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            ToggleRow("Aleta oscilando", s.swingV == 0) { on -> set { it.copy(swingV = if (on) 0 else 7) } }
            ToggleRow("Modo dormir", s.sleep) { on -> set { it.copy(sleep = on) } }
            if (s.mode == AcMode.COOL || s.mode == AcMode.HEAT) {
                ToggleRow("Turbo", s.preset == 1) { on -> set { it.copy(preset = if (on) 1 else 0) } }
            }
            ToggleRow("Pantalla del aire", s.display) { on -> set { it.copy(display = on) } }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(a.name) }
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

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Quitar aire") },
            text = { Text("Se borra solo de esta app. El aire sigue funcionando con AC Freedom.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    manager.forget(mac)
                    onBack()
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
