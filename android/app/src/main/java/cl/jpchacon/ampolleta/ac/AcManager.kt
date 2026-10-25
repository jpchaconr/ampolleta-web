package cl.jpchacon.ampolleta.ac

import android.content.Context
import android.net.wifi.WifiManager
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
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
            Result.failure(e)
        }
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
    }
}
