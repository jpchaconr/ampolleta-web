@file:OptIn(ExperimentalMaterial3Api::class)

package cl.jpchacon.ampolleta.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cl.jpchacon.ampolleta.ac.AcManager
import cl.jpchacon.ampolleta.ac.AcMode
import cl.jpchacon.ampolleta.ac.AcState
import cl.jpchacon.ampolleta.ac.AcStatus
import cl.jpchacon.ampolleta.ac.AcUi
import cl.jpchacon.ampolleta.ac.Broadlink
import cl.jpchacon.ampolleta.ac.FanSpeed
import cl.jpchacon.ampolleta.ac.HVAC_DEVTYPE
import kotlinx.coroutines.launch

// ---------- utilidades ----------

private val MODES = listOf(AcMode.COOL to "Frío", AcMode.HEAT to "Calor", AcMode.DRY to "Seco", AcMode.FAN to "Ventilar", AcMode.AUTO to "Auto")
private val SPEEDS = listOf(FanSpeed.AUTO to "Auto", FanSpeed.LOW to "Baja", FanSpeed.MID to "Media", FanSpeed.HIGH to "Alta")

private fun acColor(mode: AcMode): Color = when (mode) {
    AcMode.HEAT -> Color(0xFFFF8A4C)
    AcMode.FAN -> Color(0xFFB4C0CC)
    AcMode.DRY -> Color(0xFF7FD3C4)
    else -> Color(0xFF6CBEF7)
}

private fun acIcon(mode: AcMode): ImageVector = if (mode == AcMode.HEAT) AppIcons.Sunny else AppIcons.AcUnit

private fun temp(t: Double) = if (t % 1.0 == 0.0) "${t.toInt()}°" else "%.1f°".format(t)

private fun modeName(mode: AcMode) = MODES.first { it.first == mode }.second

fun acStatusText(a: AcUi) = when (a.status) {
    AcStatus.CONNECTED -> listOfNotNull(
        if (a.state.power) "${modeName(a.state.mode)} a ${temp(a.state.targetTemp)}" else "Apagado",
        a.ambient?.let { "ambiente ${temp(it)}" },
    ).joinToString(" · ")
    AcStatus.CONNECTING -> "Conectando…"
    AcStatus.ERROR -> "No responde: ${a.message ?: "error"}"
}

// ---------- tarjeta en el inicio ----------

@Composable
fun AcCard(a: AcUi, manager: AcManager, onOpen: () -> Unit) {
    DeviceTile(
        title = a.name,
        subtitle = acStatusText(a),
        icon = acIcon(a.state.mode),
        accent = acColor(a.state.mode),
        on = a.state.power,
        available = a.status == AcStatus.CONNECTED,
        problem = a.status == AcStatus.ERROR,
        onOpen = onOpen,
        onToggle = { on -> manager.set(a.mac) { it.copy(power = on) } },
    )
}

// ---------- agregar ----------

@Composable
fun AddAcScreen(manager: AcManager, onDone: () -> Unit, onSetup: () -> Unit) {
    val acs by manager.acs.collectAsState()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<cl.jpchacon.ampolleta.ac.FoundDevice>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    LaunchedEffect(round) {
        searching = true
        found = manager.discover()
        searching = false
    }
    val candidates = found.filter { it.macText !in acs }

    Scaffold(topBar = {
        BackBar("Agregar aire", onDone) {
            IconButton(onClick = { round++ }, enabled = !searching) { Icon(Icons.Default.Refresh, contentDescription = "Buscar otra vez") }
        }
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Banner("Busco los aires conectados a tu Wi-Fi; el teléfono tiene que estar en ese mismo Wi-Fi.") }
            error?.let { item { Banner(it, BannerKind.ERROR) } }
            if (searching) {
                item { LoadingRow("Buscando en tu Wi-Fi…") }
            } else if (candidates.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("No encontré aires nuevos.", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { round++ }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Buscar otra vez")
                        }
                    }
                }
            }
            items(candidates, key = { it.macText }) { d ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(AppIcons.AcUnit, if (d.locked) null else MaterialTheme.colorScheme.primaryContainer, 40)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name.ifBlank { "Equipo Broadlink" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    d.host,
                                    if (d.devtype != HVAC_DEVTYPE) "tipo 0x%04X, puede que no sea un aire".format(d.devtype) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (d.locked) {
                                Text("Bloqueado en AC Freedom: vincúlalo de nuevo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
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
            item {
                SectionCard("¿El aire no está en tu Wi-Fi?") {
                    Text(
                        "Si es nuevo, o aparece bloqueado por AC Freedom, vincúlalo desde aquí; no necesitas AC Freedom.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSetup) { Text("Vincular un aire nuevo al Wi-Fi") }
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

private enum class SetupPhase { FORM, SENDING, WAITING, NOT_FOUND }

@Composable
private fun StepCard(number: Int, title: String, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

@Composable
fun SetupAcScreen(manager: AcManager, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Desde Android 10 la app se conecta sola a la red del aire; antes hay que hacerlo en Ajustes.
    val inApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    var ssid by remember { mutableStateOf(manager.savedHomeSsid ?: manager.currentSsid() ?: "") }
    var password by remember { mutableStateOf(manager.savedHomePassword ?: "") }
    var security by remember { mutableStateOf(Broadlink.WifiSecurity.WPA2) }
    var networks by remember { mutableStateOf<List<AcManager.WifiSeen>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var apSsid by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(SetupPhase.FORM) }
    var error by remember { mutableStateOf<String?>(null) }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = manager.scanWifi()
            networks = found
            if (apSsid == null || found.none { it.ssid == apSsid }) apSsid = found.firstOrNull { it.likelyAc }?.ssid
            scanning = false
        }
    }

    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ssid.isBlank()) ssid = manager.currentSsid() ?: ""
        if (inApp) scan()
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (inApp) scan()
        } else {
            askPermission.launch(permissions)
        }
    }

    fun link() {
        error = null
        phase = SetupPhase.SENDING
        scope.launch {
            val r = manager.provision(ssid.trim(), if (security == Broadlink.WifiSecurity.NONE) "" else password, security, if (inApp) apSsid else null)
            if (r.isFailure) {
                error = "No se pudo enviar: ${r.exceptionOrNull()?.message}"
                phase = SetupPhase.FORM
                return@launch
            }
            phase = SetupPhase.WAITING
            val d = manager.waitForNew()
            if (d == null) {
                phase = SetupPhase.NOT_FOUND
                return@launch
            }
            val added = manager.add(d)
            if (added.isSuccess) {
                onDone()
            } else {
                error = "Lo encontré, pero no se pudo agregar: ${added.exceptionOrNull()?.message}"
                phase = SetupPhase.NOT_FOUND
            }
        }
    }

    Scaffold(topBar = { BackBar("Vincular aire al Wi-Fi", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Así el aire queda en tu Wi-Fi sin pasar por AC Freedom, y sin bloqueo.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(1, "Pon el aire en modo vinculación") {
                Text(
                    "Elimina el aire de AC Freedom si lo tenías. Con el control, presiona 8 veces el botón \"Health\" hasta que " +
                        "pite (si tu manual indica otro botón, usa ese). El aire crea su propia red Wi-Fi; si no aparece abajo, " +
                        "repite la secuencia: en muchos módulos la segunda vez pasa a ese modo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            StepCard(2, "Elige la red del aire") {
                if (inApp) {
                    when {
                        scanning -> LoadingRow("Buscando redes…")
                        networks?.isEmpty() == true -> Text(
                            "No veo redes. Revisa que la ubicación del teléfono esté activada y que la app tenga permiso.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column {
                        networks.orEmpty().take(12).forEach { n ->
                            Row(
                                Modifier.fillMaxWidth().clickable { apSsid = n.ssid },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = apSsid == n.ssid, onClick = { apSsid = n.ssid })
                                Column(Modifier.weight(1f)) {
                                    Text(n.ssid, fontWeight = if (n.likelyAc) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(
                                        listOfNotNull(
                                            if (n.likelyAc) "parece el aire" else null,
                                            if (n.open) "abierta" else "con clave",
                                            "señal ${n.level} dBm",
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { scan() }, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar redes otra vez")
                    }
                } else {
                    Text("Conéctate a la red del aire en los ajustes de Wi-Fi y vuelve aquí. No tiene internet: si Android pregunta, mantén la conexión.")
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Abrir ajustes de Wi-Fi") }
                }
            }

            StepCard(3, "Tu Wi-Fi de casa") {
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it }, singleLine = true,
                    label = { Text("Nombre (2,4 GHz)") }, modifier = Modifier.fillMaxWidth(),
                )
                if (security != Broadlink.WifiSecurity.NONE) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Clave") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Android no deja leer la clave; la escribes una vez y queda guardada en este teléfono.") },
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
            }

            error?.let { Banner(it, BannerKind.ERROR) }
            when (phase) {
                SetupPhase.FORM -> Button(
                    enabled = ssid.isNotBlank() && (!inApp || apSsid != null),
                    onClick = { link() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Vincular aire", style = MaterialTheme.typography.titleMedium) }
                SetupPhase.SENDING -> Banner(
                    if (inApp) "Conectándome a la red del aire… acepta el aviso de Android." else "Enviando…",
                    BannerKind.WAIT,
                )
                SetupPhase.WAITING -> Banner(
                    if (inApp) "Listo. Esperando que el aire se conecte a \"$ssid\" (hasta 2 minutos)…"
                    else "Listo. Vuelve a conectar el teléfono a \"$ssid\"; espero que el aire aparezca (hasta 2 minutos)…",
                    BannerKind.WAIT,
                )
                SetupPhase.NOT_FOUND -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Banner("El aire no apareció en tu Wi-Fi. Revisa el nombre y la clave, que la red sea de 2,4 GHz, y vuelve a intentarlo.", BannerKind.ERROR)
                    Button(onClick = { phase = SetupPhase.FORM }, modifier = Modifier.fillMaxWidth()) { Text("Intentar de nuevo") }
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (a.status) {
                AcStatus.CONNECTED -> {}
                AcStatus.CONNECTING -> Banner("Conectando…", BannerKind.WAIT)
                AcStatus.ERROR -> Banner(acStatusText(a), BannerKind.ERROR)
            }

            AcHero(a, onPower = { on -> set { it.copy(power = on) } }, onTemp = { delta ->
                set { it.copy(targetTemp = (it.targetTemp + delta).coerceIn(AcState.MIN_TEMP, AcState.MAX_TEMP)) }
            })

            SectionCard("Modo") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES.forEach { (mode, label) ->
                        val chipIcon = if (mode == AcMode.COOL || mode == AcMode.HEAT) acIcon(mode) else null
                        FilterChip(
                            selected = s.power && s.mode == mode,
                            onClick = { set { it.copy(mode = mode, power = true) } },
                            label = { Text(label) },
                            leadingIcon = if (chipIcon != null) {
                                { Icon(chipIcon, null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            SectionCard("Ventilador") {
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
            }

            SectionCard("Opciones") {
                Column {
                    ToggleRow("Aleta oscilando", s.swingV == 0) { on -> set { it.copy(swingV = if (on) 0 else 7) } }
                    ToggleRow("Modo dormir", s.sleep, "Sube la temperatura de a poco durante la noche") { on -> set { it.copy(sleep = on) } }
                    if (s.mode == AcMode.COOL || s.mode == AcMode.HEAT) {
                        ToggleRow("Turbo", s.preset == 1, "Máxima potencia por un rato") { on -> set { it.copy(preset = if (on) 1 else 0) } }
                    }
                    ToggleRow("Pantalla del aire", s.display) { on -> set { it.copy(display = on) } }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(a.name) }
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

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("¿Quitar aire?") },
            text = { Text("Se borra solo de esta app. El aire sigue funcionando con AC Freedom.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    manager.forget(mac)
                    onBack()
                }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
        )
    }
}

/** La tarjeta principal del aire: temperatura grande, − y +, y el botón de encendido. */
@Composable
private fun AcHero(a: AcUi, onPower: (Boolean) -> Unit, onTemp: (Double) -> Unit) {
    val s = a.state
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val container by animateColorAsState(if (s.power) lerp(base, acColor(s.mode), 0.3f) else base, label = "aire")
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(acIcon(s.mode), if (s.power) acColor(s.mode) else null, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (s.power) modeName(s.mode) else "Apagado", style = MaterialTheme.typography.titleMedium)
                    a.ambient?.let {
                        Text("Ambiente ${temp(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledIconToggleButton(
                    checked = s.power,
                    onCheckedChange = onPower,
                    modifier = Modifier.size(56.dp),
                ) { Icon(AppIcons.Power, contentDescription = if (s.power) "Apagar" else "Encender") }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onTemp(-1.0) },
                    enabled = s.targetTemp > AcState.MIN_TEMP,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) { Icon(AppIcons.Remove, contentDescription = "Bajar temperatura") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(temp(s.targetTemp), fontSize = 64.sp, fontWeight = FontWeight.Light, lineHeight = 64.sp)
                    Text("Temperatura objetivo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(
                    onClick = { onTemp(1.0) },
                    enabled = s.targetTemp < AcState.MAX_TEMP,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) { Icon(Icons.Default.Add, contentDescription = "Subir temperatura") }
            }
        }
    }
}
