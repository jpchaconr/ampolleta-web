package cl.jpchacon.ampolleta.tuya

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Cómo llegan los bytes a la característica de escritura (GATT en Android, un simulador en pruebas). */
fun interface TuyaTransport {
    suspend fun write(packet: ByteArray)
}

/**
 * Una sesión con una ampolleta Tuya BLE ya conectada. El dueño entrega las notificaciones con
 * [onNotification] y llama a [handshake] antes de enviar comandos.
 */
class TuyaDevice(
    private val credentials: Credentials,
    private val transport: TuyaTransport,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    var onDatapoints: (List<Datapoint>) -> Unit = {}

    var bound = false
        private set
    var firmware = ""
        private set
    var paired = false
        private set

    private val codec = PacketCodec()
    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Message>>()
    private val writeLock = Mutex()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    private var loginSeed = credentials.loginKey
    private var loginKey = TuyaCrypto.md5(loginSeed)
    private var sessionKey: ByteArray? = null
    private var nextSessionKey: ByteArray? = null
    private var srand = ByteArray(6)

    init {
        scope.launch {
            for (full in incoming) {
                try {
                    handle(full)
                } catch (e: Exception) {
                    log("Error al leer respuesta: ${e.message}")
                }
            }
        }
    }

    fun onNotification(packet: ByteArray) {
        val full = try {
            synchronized(codec) { codec.feed(packet) }
        } catch (e: TuyaProtocolException) {
            log("Notificación descartada: ${e.message}")
            null
        } ?: return
        incoming.trySend(full)
    }

    /**
     * Pide la información del dispositivo y empareja. Si la ampolleta está sin vincular (su clave de
     * login está en ceros, según el SDK de Tuya), la vincula con estas credenciales.
     * Devuelve true si la tuvo que vincular.
     */
    suspend fun handshake(): Boolean {
        var info = try {
            request(Code.DEVICE_INFO, ByteArray(0), INFO_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            null
        }
        if (info == null) {
            log("Sin respuesta con la clave; probando como ampolleta sin vincular…")
            loginSeed = ByteArray(Credentials.LOGIN_KEY_LEN)
            loginKey = TuyaCrypto.md5(loginSeed)
            info = try {
                request(Code.DEVICE_INFO, ByteArray(0), INFO_TIMEOUT_MS)
            } catch (e: TimeoutCancellationException) {
                throw TuyaProtocolException("La ampolleta no responde: está vinculada con otra clave")
            }
        }
        val d = info.data
        if (d.size < 46) throw TuyaProtocolException("DEVICE_INFO muy corto")
        codec.protocolVersion = d[2].toInt()
        firmware = "${d[0]}.${d[1]}"
        bound = d[5].toInt() != 0
        srand = d.copyOfRange(6, 12)
        sessionKey = TuyaCrypto.md5(loginSeed, srand)
        log("Firmware $firmware, protocolo ${d[2]}.${d[3]}, ${if (bound) "vinculada" else "sin vincular"}")

        val newlyBound = !bound
        if (newlyBound) {
            // Desde que la ampolleta guarda la clave, la de sesión se deriva de ella.
            log("Vinculando la ampolleta…")
            nextSessionKey = TuyaCrypto.md5(credentials.loginKey, srand)
        }
        val pair = request(Code.PAIR, pairingRequest())
        val result = pair.data.firstOrNull()?.toInt() ?: -1
        if (result != 0 && result != 2) {
            nextSessionKey = null
            throw TuyaProtocolException("La ampolleta rechazó el emparejamiento ($result)")
        }
        nextSessionKey?.let {
            sessionKey = it
            nextSessionKey = null
            loginSeed = credentials.loginKey
            loginKey = TuyaCrypto.md5(loginSeed)
            bound = true
            log("Ampolleta vinculada")
        }
        paired = true
        return newlyBound
    }

    suspend fun requestStatus() {
        request(Code.DEVICE_STATUS, ByteArray(0))
    }

    suspend fun setDatapoints(dps: List<Datapoint>) {
        val r = request(Code.DPS, Datapoints.encode(dps))
        val result = r.data.firstOrNull()?.toInt() ?: 0
        if (result != 0) throw TuyaProtocolException("La ampolleta rechazó el comando ($result)")
    }

    /** Borra la vinculación en la ampolleta, para poder usarla otra vez con Smart Life. */
    suspend fun unbind() {
        request(Code.UNBIND, ByteArray(0))
        paired = false
    }

    fun close() {
        incoming.close()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun pairingRequest(): ByteArray {
        val raw = credentials.uuid.toByteArray(Charsets.US_ASCII) + credentials.loginKey +
            credentials.deviceId.toByteArray(Charsets.US_ASCII)
        return raw.copyOf(44)
    }

    private suspend fun request(code: Int, data: ByteArray, timeoutMs: Long = RESPONSE_TIMEOUT_MS): Message {
        val s = seq.getAndIncrement()
        val deferred = CompletableDeferred<Message>()
        pending[s] = deferred
        try {
            send(s, code, data, 0)
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(s)
        }
    }

    private suspend fun respond(code: Int, data: ByteArray, responseTo: Int) {
        send(seq.getAndIncrement(), code, data, responseTo)
    }

    private suspend fun send(s: Int, code: Int, data: ByteArray, responseTo: Int) {
        val isInfo = code == Code.DEVICE_INFO
        val key = if (isInfo) loginKey else sessionKey ?: throw TuyaProtocolException("sin clave de sesión")
        val packets = codec.build(s, code, data, responseTo, if (isInfo) 4 else 5, key)
        writeLock.withLock {
            for (p in packets) transport.write(p)
        }
    }

    private suspend fun handle(full: ByteArray) {
        val flag = full[0].toInt()
        val keys = when (flag) {
            4 -> listOf(loginKey)
            5 -> listOfNotNull(sessionKey, nextSessionKey)
            else -> throw TuyaProtocolException("bandera de seguridad desconocida: $flag")
        }
        var msg: Message? = null
        var error: Exception? = null
        for (k in keys) {
            try {
                msg = PacketCodec.decrypt(full, k)
                break
            } catch (e: Exception) {
                error = e
            }
        }
        val m = msg ?: throw error ?: TuyaProtocolException("sin clave")

        when (m.code) {
            Code.RECEIVE_DP -> {
                onDatapoints(Datapoints.parse(m.data, 0))
                respond(m.code, ByteArray(0), m.seq)
            }
            Code.RECEIVE_TIME_DP -> {
                onDatapoints(Datapoints.parse(m.data, Datapoints.skipTimestamp(m.data, 0)))
                respond(m.code, ByteArray(0), m.seq)
            }
            Code.RECEIVE_SIGN_DP, Code.RECEIVE_SIGN_TIME_DP -> {
                val start = if (m.code == Code.RECEIVE_SIGN_TIME_DP) Datapoints.skipTimestamp(m.data, 3) else 3
                onDatapoints(Datapoints.parse(m.data, start))
                respond(m.code, m.data.copyOf(3) + byteArrayOf(0), m.seq)
            }
            Code.TIME1_REQ -> {
                val tz = tuyaTimezone()
                respond(m.code, System.currentTimeMillis().toString().toByteArray() + byteArrayOf((tz shr 8).toByte(), tz.toByte()), m.seq)
            }
            Code.TIME2_REQ -> {
                val c = Calendar.getInstance()
                val tz = tuyaTimezone()
                val out = byteArrayOf(
                    (c.get(Calendar.YEAR) % 100).toByte(), (c.get(Calendar.MONTH) + 1).toByte(),
                    c.get(Calendar.DAY_OF_MONTH).toByte(), c.get(Calendar.HOUR_OF_DAY).toByte(),
                    c.get(Calendar.MINUTE).toByte(), c.get(Calendar.SECOND).toByte(),
                    ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7).toByte(), // lunes = 0
                    (tz shr 8).toByte(), tz.toByte(),
                )
                respond(m.code, out, m.seq)
            }
        }
        if (m.responseTo != 0) pending[m.responseTo]?.complete(m)
    }

    // Zona horaria en el formato de Tuya: centésimas de hora, con signo.
    private fun tuyaTimezone(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 36_000

    companion object {
        const val RESPONSE_TIMEOUT_MS = 6_000L
        const val INFO_TIMEOUT_MS = 3_000L
    }
}
