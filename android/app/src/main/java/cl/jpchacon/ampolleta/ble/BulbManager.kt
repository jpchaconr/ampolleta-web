package cl.jpchacon.ampolleta.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import cl.jpchacon.ampolleta.tuya.AdvertisementInfo
import cl.jpchacon.ampolleta.tuya.Advertisement
import cl.jpchacon.ampolleta.tuya.Credentials
import cl.jpchacon.ampolleta.tuya.Datapoint
import cl.jpchacon.ampolleta.tuya.Light
import cl.jpchacon.ampolleta.tuya.LightProfile
import cl.jpchacon.ampolleta.tuya.LightState
import cl.jpchacon.ampolleta.tuya.TuyaDevice
import cl.jpchacon.ampolleta.tuya.TuyaUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class LinkStatus { SEARCHING, CONNECTING, CONNECTED, ERROR }

data class BulbUi(
    val mac: String,
    val name: String,
    val status: LinkStatus = LinkStatus.SEARCHING,
    val light: LightState = LightState(),
    val message: String? = null,
)

data class Discovered(val mac: String, val name: String, val rssi: Int, val adv: AdvertisementInfo)

/**
 * Busca ampolletas Tuya, mantiene conectadas las que la app vinculó y les envía los cambios.
 * Vive mientras viva la app (ver [cl.jpchacon.ampolleta.AmpolletaApp]).
 */
@SuppressLint("MissingPermission")
class BulbManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = BulbStore(context)
    private val profile = LightProfile()
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val saved = MutableStateFlow(store.load())
    private val _bulbs = MutableStateFlow(saved.value.associate { it.mac to BulbUi(it.mac, it.name) })
    val bulbs: StateFlow<Map<String, BulbUi>> = _bulbs.asStateFlow()

    private val _discovered = MutableStateFlow<Map<String, Discovered>>(emptyMap())
    val discovered: StateFlow<Map<String, Discovered>> = _discovered.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private class Session(val gatt: GattConnection, val tuya: TuyaDevice) {
        val pending = LinkedHashMap<Int, Datapoint>()
        val signal = Channel<Unit>(Channel.CONFLATED)
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private var scanning = false

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.update { (listOf("$t  $msg") + it).take(300) }
    }

    // ---------- escaneo ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("La búsqueda Bluetooth falló (código $errorCode)")
        }
    }

    fun startScan() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            log("Sin permiso para buscar dispositivos Bluetooth")
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // El Bluetooth pudo apagarse mientras tanto.
        }
    }

    private fun onResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val mfr = record.getManufacturerSpecificData(TuyaUuids.MANUFACTURER_ID) ?: return
        val service = record.getServiceData(ParcelUuid.fromString(TuyaUuids.ADV_SERVICE))
        val adv = Advertisement.parse(service, mfr) ?: return
        val mac = result.device.address
        val name = record.deviceName ?: "TY"
        _discovered.update { it + (mac to Discovered(mac, name, result.rssi, adv)) }

        val bulb = saved.value.firstOrNull { it.mac == mac } ?: return
        if (sessions.containsKey(mac)) return
        val status = _bulbs.value[mac]?.status
        if (status == LinkStatus.CONNECTING) return
        val wait = if (status == LinkStatus.ERROR) 20_000L else 4_000L
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAttempt[mac] ?: 0L) < wait) return
        lastAttempt[mac] = now
        scope.launch { connectSaved(bulb, result.device) }
    }

    // ---------- conexión ----------

    private fun setUi(mac: String, f: (BulbUi) -> BulbUi) = _bulbs.update { m -> m[mac]?.let { m + (mac to f(it)) } ?: m }

    private suspend fun openSession(mac: String, name: String, device: BluetoothDevice, creds: Credentials): Pair<Session, Boolean> {
        var tuya: TuyaDevice? = null
        val gatt = GattConnection(
            context, device,
            onNotification = { tuya?.onNotification(it) },
            onDisconnected = { onLost(mac) },
        )
        log("$name: conectando…")
        gatt.connect()
        val t = TuyaDevice(creds, { gatt.write(it) }, scope) { log("$name: $it") }
        tuya = t
        val newlyBound = try {
            t.handshake()
        } catch (e: Exception) {
            t.close()
            gatt.close()
            throw e
        }
        val session = Session(gatt, t)
        t.onDatapoints = { dps -> setUi(mac) { it.copy(light = Light.apply(profile, it.light, dps)) } }
        scope.launch {
            for (unit in session.signal) {
                val batch = synchronized(session.pending) { session.pending.values.toList().also { session.pending.clear() } }
                if (batch.isEmpty()) continue
                try {
                    t.setDatapoints(batch)
                } catch (e: Exception) {
                    log("$name: no se pudo enviar (${e.message})")
                }
            }
        }
        return session to newlyBound
    }

    private suspend fun connectSaved(bulb: SavedBulb, device: BluetoothDevice) {
        setUi(bulb.mac) { it.copy(status = LinkStatus.CONNECTING, message = null) }
        try {
            val (session, newlyBound) = openSession(bulb.mac, bulb.name, device, bulb.credentials)
            sessions[bulb.mac] = session
            if (newlyBound) log("${bulb.name}: se había reiniciado; quedó vinculada de nuevo")
            setUi(bulb.mac) { it.copy(status = LinkStatus.CONNECTED) }
            session.tuya.requestStatus()
        } catch (e: Exception) {
            log("${bulb.name}: ${e.message}")
            setUi(bulb.mac) { it.copy(status = LinkStatus.ERROR, message = e.message) }
        }
    }

    private fun onLost(mac: String) {
        sessions.remove(mac)?.let {
            it.tuya.close()
            it.signal.close()
        }
        setUi(mac) { it.copy(status = LinkStatus.SEARCHING) }
        log("${_bulbs.value[mac]?.name ?: mac}: se perdió la conexión")
    }

    /** Vincula una ampolleta sin vincular con una clave nueva y la guarda. */
    suspend fun add(d: Discovered): Result<Unit> {
        val uuid = d.adv.uuid ?: return Result.failure(Exception("No pude leer el uuid de la ampolleta"))
        if (d.adv.bound) return Result.failure(Exception("Está vinculada a otra app. Reiníciala para poder agregarla."))
        val device = adapter?.getRemoteDevice(d.mac) ?: return Result.failure(Exception("Bluetooth no disponible"))
        val name = "Ampolleta ${saved.value.size + 1}"
        val creds = Credentials.generate(uuid)
        lastAttempt[d.mac] = SystemClock.elapsedRealtime()
        return try {
            val (session, _) = openSession(d.mac, name, device, creds)
            val bulb = SavedBulb(d.mac, name, creds)
            saved.update { it + bulb }
            store.save(saved.value)
            sessions[d.mac] = session
            _bulbs.update { it + (d.mac to BulbUi(d.mac, name, LinkStatus.CONNECTED)) }
            session.tuya.requestStatus()
            Result.success(Unit)
        } catch (e: Exception) {
            log("$name: ${e.message}")
            Result.failure(e)
        }
    }

    // ---------- control ----------

    /** Cambia el estado de una luz y envía solo los DP indicados. */
    fun setLight(mac: String, change: (LightState) -> LightState, dps: (LightProfile, LightState) -> List<Datapoint>) {
        var newState: LightState? = null
        setUi(mac) { ui -> ui.copy(light = change(ui.light)).also { newState = it.light } }
        val st = newState ?: return
        val session = sessions[mac] ?: return
        synchronized(session.pending) {
            for (dp in dps(profile, st)) session.pending[dp.id] = dp
        }
        session.signal.trySend(Unit)
    }

    fun rename(mac: String, name: String) {
        saved.update { list -> list.map { if (it.mac == mac) it.copy(name = name) else it } }
        store.save(saved.value)
        setUi(mac) { it.copy(name = name) }
    }

    /** Olvida la ampolleta. Con unbind, además borra la vinculación en ella para que Smart Life pueda volver a agregarla. */
    suspend fun forget(mac: String, unbind: Boolean) {
        val session = sessions.remove(mac)
        if (unbind && session != null) {
            try {
                session.tuya.unbind()
                log("Ampolleta desvinculada")
            } catch (e: Exception) {
                log("No se pudo desvincular: ${e.message}")
            }
        }
        session?.let {
            it.tuya.close()
            it.signal.close()
            it.gatt.close()
        }
        saved.update { list -> list.filterNot { it.mac == mac } }
        store.save(saved.value)
        _bulbs.update { it - mac }
    }
}
