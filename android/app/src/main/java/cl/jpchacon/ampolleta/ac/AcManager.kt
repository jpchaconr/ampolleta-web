package cl.jpchacon.ampolleta.ac

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

enum class AcStatus { CONNECTING, CONNECTED, ERROR }

data class AcUi(
    val mac: String,
    val name: String,
    val host: String,
    val status: AcStatus = AcStatus.CONNECTING,
    val state: AcState = AcState(),
    val ambient: Double? = null,
    val message: String? = null,
)

data class SavedAc(val mac: String, val name: String, val host: String, val devtype: Int)

/**
 * Los aires con módulo Wi-Fi Broadlink (AC Freedom). Se hablan por UDP en la red local, así que el
 * teléfono tiene que estar en el mismo Wi-Fi. Mientras la app está abierta, lee su estado cada tanto.
 */
class AcManager(private val context: Context, private val log: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("aires", Context.MODE_PRIVATE)

    private val saved = MutableStateFlow(load())
    private val _acs = MutableStateFlow(saved.value.associate { it.mac to AcUi(it.mac, it.name, it.host) })
    val acs: StateFlow<Map<String, AcUi>> = _acs.asStateFlow()

    private class Session(val ac: BroadlinkAc, var host: String) {
        val lock = Mutex()
        val changes = Channel<AcState>(Channel.CONFLATED)
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private var polling: Job? = null

    // ---------- guardado ----------

    private fun load(): List<SavedAc> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedAc(o.getString("mac"), o.getString("name"), o.getString("host"), o.getInt("devtype"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun store() {
        val arr = JSONArray()
        for (a in saved.value) {
            arr.put(JSONObject().put("mac", a.mac).put("name", a.name).put("host", a.host).put("devtype", a.devtype))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun setUi(mac: String, f: (AcUi) -> AcUi) = _acs.update { m -> m[mac]?.let { m + (mac to f(it)) } ?: m }

    // ---------- red ----------

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            @Suppress("DEPRECATION")
            val dhcp = context.applicationContext.getSystemService(WifiManager::class.java)?.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val bcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                out += InetAddress.getByAddress(ByteArray(4) { (bcast shr (8 * it)).toByte() })
            }
        } catch (e: Exception) {
            // Sin acceso al Wi-Fi: queda el broadcast general.
        }
        return out
    }

    /** Busca equipos Broadlink en el Wi-Fi durante unos segundos. */
    suspend fun discover(seconds: Int = 4): List<FoundDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, FoundDevice>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = 500
            val buf = ByteArray(1024)
            val end = System.currentTimeMillis() + seconds * 1000L
            var nextHello = 0L
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextHello) {
                    val hello = Broadlink.helloPacket()
                    for (addr in broadcastAddresses()) {
                        try {
                            socket.send(DatagramPacket(hello, hello.size, addr, Broadlink.PORT))
                        } catch (e: Exception) {
                            log("No se pudo buscar en $addr: ${e.message}")
                        }
                    }
                    nextHello = System.currentTimeMillis() + 1000
                }
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val host = p.address.hostAddress ?: continue
                val d = Broadlink.parseHello(p.data.copyOf(p.length), host) ?: continue
                found[d.macText] = d
            }
        } finally {
            socket.close()
        }
        found.values.toList()
    }

    private fun udpTransport(session: () -> Session?) = BroadlinkTransport { packet ->
        withContext(Dispatchers.IO) {
            val host = session()?.host ?: throw BroadlinkException("aire desconocido")
            DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val target = InetSocketAddress(host, Broadlink.PORT)
                val buf = ByteArray(2048)
                repeat(5) {
                    socket.send(DatagramPacket(packet, packet.size, target))
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        return@withContext p.data.copyOf(p.length)
                    } catch (e: SocketTimeoutException) {
                        // Reintenta: es UDP.
                    }
                }
                throw BroadlinkException("el aire no responde en $host")
            }
        }
    }

    // ---------- sesiones ----------

    private fun sessionFor(a: SavedAc): Session = sessions.getOrPut(a.mac) {
        var self: Session? = null
        val mac = Broadlink.hex(a.mac.replace(":", ""))
        val s = Session(BroadlinkAc(a.devtype, mac, udpTransport { self }), a.host)
        self = s
        scope.launch {
            for (st in s.changes) {
                try {
                    val reported = withSession(a) { it.ac.setState(st) }
                    setUi(a.mac) { it.copy(state = reported, status = AcStatus.CONNECTED, message = null) }
                } catch (e: Exception) {
                    log("${a.name}: no se pudo enviar (${e.message})")
                    setUi(a.mac) { it.copy(status = AcStatus.ERROR, message = e.message) }
                }
            }
        }
        s
    }

    /** Ejecuta un comando, autenticando si hace falta y buscando el aire de nuevo si cambió de IP. */
    private suspend fun <T> withSession(a: SavedAc, block: suspend (Session) -> T): T {
        val s = sessionFor(a)
        return s.lock.withLock {
            try {
                if (!s.ac.authenticated) s.ac.auth()
                block(s)
            } catch (e: BroadlinkException) {
                if (e.code == Broadlink.ERROR_KEY_EXPIRED) {
                    s.ac.auth()
                    return@withLock block(s)
                }
                if (e.code != 0) throw e
                // Sin respuesta: puede que el router le haya dado otra IP.
                val now = discover(3).firstOrNull { it.macText == a.mac } ?: throw e
                if (now.host == s.host) throw e
                log("${a.name}: ahora está en ${now.host}")
                s.host = now.host
                updateSaved(a.mac) { it.copy(host = now.host) }
                setUi(a.mac) { it.copy(host = now.host) }
                s.ac.auth()
                block(s)
            }
        }
    }

    private fun updateSaved(mac: String, f: (SavedAc) -> SavedAc) {
        saved.update { list -> list.map { if (it.mac == mac) f(it) else it } }
        store()
    }

    private suspend fun refresh(a: SavedAc) {
        try {
            val (state, ambient) = withSession(a) { s ->
                val st = s.ac.getState()
                val amb = try { s.ac.getAmbient() } catch (e: BroadlinkException) { null }
                st to amb
            }
            setUi(a.mac) { it.copy(state = state, ambient = ambient ?: it.ambient, status = AcStatus.CONNECTED, message = null) }
        } catch (e: Exception) {
            if (_acs.value[a.mac]?.status != AcStatus.ERROR) log("${a.name}: ${e.message}")
            setUi(a.mac) { it.copy(status = AcStatus.ERROR, message = e.message) }
        }
    }

    /** Lee el estado de los aires cada 15 s mientras la app está a la vista. */
    fun start() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                saved.value.forEach { launch { refresh(it) } }
                delay(15_000)
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
    }

    // ---------- acciones ----------

    /** Prueba el equipo y lo guarda. */
    suspend fun add(d: FoundDevice): Result<Unit> {
        val a = SavedAc(d.macText, d.name.ifBlank { "Aire ${saved.value.size + 1}" }, d.host, d.devtype)
        saved.update { list -> list.filterNot { it.mac == a.mac } + a }
        _acs.update { it + (a.mac to AcUi(a.mac, a.name, a.host)) }
        return try {
            sessions.remove(a.mac)?.changes?.close()
            val state = withSession(a) { it.ac.getState() }
            store()
            setUi(a.mac) { it.copy(state = state, status = AcStatus.CONNECTED) }
            log("${a.name}: agregado (${a.host})")
            scope.launch { refresh(a) }
            Result.success(Unit)
        } catch (e: Exception) {
            saved.update { list -> list.filterNot { it.mac == a.mac } }
            _acs.update { it - a.mac }
            sessions.remove(a.mac)?.changes?.close()
            log("${a.name}: ${e.message}")
            if (d.locked) {
                Result.failure(Exception("${e.message}. El aire está bloqueado por AC Freedom: vincúlalo de nuevo con \"Vincular un aire nuevo al Wi-Fi\"."))
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * El Wi-Fi conectado, aunque no tenga internet. Así los paquetes no se van por los datos móviles
     * cuando el teléfono está conectado a la red propia del aire.
     */
    private fun wifiNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    // ---------- vincular al Wi-Fi ----------

    /** Un Wi-Fi visible, para elegir la red que crea el aire en modo vinculación. */
    data class WifiSeen(val ssid: String, val level: Int, val open: Boolean, val likelyAc: Boolean)

    private val wifi get() = context.applicationContext.getSystemService(WifiManager::class.java)

    /** El Wi-Fi al que está conectado el teléfono, o null si Android no lo dice (falta el permiso de ubicación). */
    fun currentSsid(): String? = try {
        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (e: Exception) {
        null
    }

    /** Lo que se usó la última vez para vincular, guardado solo en este teléfono. */
    val savedHomeSsid: String? get() = prefs.getString("wifi_ssid", null)
    val savedHomePassword: String? get() = prefs.getString("wifi_password", null)

    /** Busca redes Wi-Fi cercanas. La del aire en modo vinculación suele ser abierta (sin clave). */
    suspend fun scanWifi(): List<WifiSeen> = withContext(Dispatchers.IO) {
        val wm = wifi ?: return@withContext emptyList()
        try {
            @Suppress("DEPRECATION")
            wm.startScan() // Android limita cuántas veces se puede; si no, quedan los resultados recientes.
            delay(3000)
            val home = currentSsid()
            wm.scanResults
                .mapNotNull { r ->
                    @Suppress("DEPRECATION")
                    val name = r.SSID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (name == home) return@mapNotNull null
                    val open = listOf("WPA", "WEP", "SAE", "PSK", "EAP").none { r.capabilities.contains(it) }
                    val likely = AC_HINTS.any { name.contains(it, ignoreCase = true) }
                    WifiSeen(name, r.level, open, likely)
                }
                .groupBy { it.ssid }.map { (_, v) -> v.maxBy { it.level } }
                .sortedWith(compareByDescending<WifiSeen> { it.likelyAc }.thenByDescending { it.open }.thenByDescending { it.level })
        } catch (e: SecurityException) {
            log("Sin permiso para buscar redes Wi-Fi")
            emptyList()
        }
    }

    /**
     * Conecta el teléfono a la red del aire solo para esta app (Android 10+). Android muestra un diálogo
     * para aceptar; al soltarla, el teléfono vuelve solo a su Wi-Fi.
     */
    private suspend fun connectToAp(apSsid: String): Pair<Network, ConnectivityManager.NetworkCallback> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw BroadlinkException("conectarse desde la app necesita Android 10 o superior")
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: throw BroadlinkException("sin acceso a la red")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(apSsid).build())
            .build()
        val result = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                result.complete(network)
            }

            override fun onUnavailable() {
                result.completeExceptionally(BroadlinkException("no me pude conectar a \"$apSsid\""))
            }
        }
        cm.requestNetwork(request, callback)
        return try {
            withTimeout(90_000) { result.await() } to callback
        } catch (e: Exception) {
            cm.unregisterNetworkCallback(callback)
            throw if (e is BroadlinkException) e else BroadlinkException("no me pude conectar a \"$apSsid\"")
        }
    }

    /** Dónde enviar en la red del aire: broadcast y la puerta de enlace, que en modo AP suele ser el aire. */
    private fun targetsOn(network: Network?): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = network?.let { cm?.getLinkProperties(it) }
        if (lp != null) {
            for (la in lp.linkAddresses) {
                val a = la.address as? Inet4Address ?: continue
                val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                val bcast = (ip and mask) or mask.inv()
                out += InetAddress.getByAddress(ByteArray(4) { (bcast shr (24 - 8 * it)).toByte() })
            }
            lp.routes.mapNotNull { it.gateway as? Inet4Address }.filterNot { it.isAnyLocalAddress }.forEach { out += it }
        } else {
            out.clear()
            out += broadcastAddresses()
            try {
                @Suppress("DEPRECATION")
                val gw = wifi?.dhcpInfo?.gateway ?: 0
                if (gw != 0) out += InetAddress.getByAddress(ByteArray(4) { (gw shr (8 * it)).toByte() })
            } catch (e: Exception) {
                // Queda el broadcast.
            }
        }
        return out.distinct()
    }

    /**
     * Configura el Wi-Fi de un aire en modo AP. Con apSsid, la app se conecta sola a la red del aire;
     * sin él, el teléfono ya tiene que estar conectado a esa red. Se envía varias veces porque es UDP
     * y el aire no responde.
     */
    suspend fun provision(ssid: String, password: String, security: Broadlink.WifiSecurity, apSsid: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            var callback: ConnectivityManager.NetworkCallback? = null
            try {
                val packet = Broadlink.setupPacket(ssid, password, security)
                prefs.edit().putString("wifi_ssid", ssid).putString("wifi_password", password).apply()
                val network = if (apSsid != null) {
                    log("Conectándome a la red del aire \"$apSsid\"…")
                    connectToAp(apSsid).let { (n, cb) -> callback = cb; n }
                } else {
                    wifiNetwork()
                }
                val targets = targetsOn(network)
                val socket = DatagramSocket()
                try {
                    network?.bindSocket(socket) ?: log("No encontré el Wi-Fi conectado; envío igual")
                    socket.broadcast = true
                    repeat(3) {
                        for (addr in targets) {
                            try {
                                socket.send(DatagramPacket(packet, packet.size, addr, Broadlink.PORT))
                            } catch (e: Exception) {
                                log("No se pudo enviar a $addr: ${e.message}")
                            }
                        }
                        delay(500)
                    }
                } finally {
                    socket.close()
                }
                log("Wi-Fi \"$ssid\" enviado al aire (${targets.joinToString { it.hostAddress ?: "?" }})")
                Result.success(Unit)
            } catch (e: Exception) {
                log("No se pudo configurar el Wi-Fi del aire: ${e.message}")
                Result.failure(e)
            } finally {
                callback?.let { cb -> context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
        }

    /** Espera a que aparezca en el Wi-Fi un aire que todavía no está en la app (después de vincularlo). */
    suspend fun waitForNew(timeoutMs: Long = 120_000): FoundDevice? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            discover(4).firstOrNull { it.macText !in _acs.value }?.let { return it }
            delay(2000)
        }
        return null
    }

    /** Cambia el estado del aire en pantalla al tiro y lo envía completo. */
    fun set(mac: String, change: (AcState) -> AcState) {
        var newState: AcState? = null
        setUi(mac) { ui -> ui.copy(state = change(ui.state)).also { newState = it.state } }
        val st = newState ?: return
        val a = saved.value.firstOrNull { it.mac == mac } ?: return
        sessionFor(a).changes.trySend(st)
    }

    fun rename(mac: String, name: String) {
        updateSaved(mac) { it.copy(name = name) }
        setUi(mac) { it.copy(name = name) }
    }

    fun forget(mac: String) {
        sessions.remove(mac)?.changes?.close()
        saved.update { list -> list.filterNot { it.mac == mac } }
        store()
        _acs.update { it - mac }
    }

    private companion object {
        const val KEY = "aires"
        // Nombres típicos de la red que crea el módulo en modo vinculación.
        val AC_HINTS = listOf("broadlink", "aux", "kendal", "ac-", "ac_", "hvac", "bl_")
    }
}
