package cl.jpchacon.ampolleta.ac

// Protocolo Broadlink por Wi-Fi (UDP, puerto 80), el del módulo de AC Freedom en los aires AUX y sus marcas
// (Kendal, Tornado, Rinnai…). Sin dependencias de Android para poder probarlo en la JVM.
// Basado en python-broadlink (mjg59/python-broadlink: device.py y la clase hvac de climate.py).

import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BroadlinkException(message: String, val code: Int = 0) : Exception(message)

/** Un equipo que respondió a la búsqueda. mac va en el orden en que se muestra (AA:BB:…). */
data class FoundDevice(val host: String, val mac: ByteArray, val devtype: Int, val name: String, val locked: Boolean) {
    val macText get() = mac.joinToString(":") { "%02X".format(it) }
}

object Broadlink {
    const val PORT = 80
    const val CMD_HELLO = 0x06
    const val CMD_AUTH = 0x65
    const val CMD_DATA = 0x6A
    const val ERROR_KEY_EXPIRED = -7

    val INIT_KEY = hex("097628343fe99e23765c1513accf8b02")
    private val IV = hex("562e17996d093d28ddb3ba695a2e6f58")
    private val MAGIC = hex("5aa5aa555aa5aa55")
    private val random = SecureRandom()

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun randomCount() = 0x8000 + random.nextInt(0x8000)

    /** Suma de todos los bytes más 0xBEAF, en 16 bits. */
    fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var sum = 0xBEAF
        for (i in from until to) sum += data[i].toInt() and 0xFF
        return sum and 0xFFFF
    }

    fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(IV)) }.doFinal(data)

    private fun putLe16(b: ByteArray, pos: Int, v: Int) {
        b[pos] = v.toByte()
        b[pos + 1] = (v shr 8).toByte()
    }

    fun le16(b: ByteArray, pos: Int) = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, pos: Int) = le16(b, pos) or (le16(b, pos + 2) shl 16)

    // ---------- búsqueda ----------

    /** Paquete de búsqueda que se envía por broadcast; los equipos responden con su tipo, MAC y nombre. */
    fun helloPacket(now: Calendar = Calendar.getInstance()): ByteArray {
        val p = ByteArray(0x30)
        val offsetHours = now.timeZone.getOffset(now.timeInMillis) / 3_600_000
        for (i in 0 until 4) p[0x08 + i] = (offsetHours shr (8 * i)).toByte()
        putLe16(p, 0x0C, now.get(Calendar.YEAR))
        p[0x0E] = now.get(Calendar.MINUTE).toByte()
        p[0x0F] = now.get(Calendar.HOUR_OF_DAY).toByte()
        p[0x10] = (now.get(Calendar.YEAR) % 100).toByte()
        p[0x11] = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1).toByte() // lunes = 1 … domingo = 7
        p[0x12] = now.get(Calendar.DAY_OF_MONTH).toByte()
        p[0x13] = (now.get(Calendar.MONTH) + 1).toByte()
        // IP y puerto locales en cero: el equipo responde a quien le habló.
        p[0x26] = CMD_HELLO.toByte()
        putLe16(p, 0x20, checksum(p))
        return p
    }

    fun parseHello(resp: ByteArray, host: String): FoundDevice? {
        if (resp.size < 0x40) return null
        val nameBytes = resp.copyOfRange(0x40, minOf(resp.size, 0x7F))
        val end = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        return FoundDevice(
            host = host,
            mac = resp.copyOfRange(0x3A, 0x40).reversedArray(),
            devtype = le16(resp, 0x34),
            name = String(nameBytes, 0, end, Charsets.UTF_8),
            locked = resp.size > 0x7F && resp[0x7F].toInt() != 0,
        )
    }

    // ---------- comandos ----------

    fun commandPacket(devtype: Int, count: Int, mac: ByteArray, id: Int, key: ByteArray, type: Int, payload: ByteArray): ByteArray {
        val head = ByteArray(0x38)
        MAGIC.copyInto(head)
        putLe16(head, 0x24, devtype)
        putLe16(head, 0x26, type)
        putLe16(head, 0x28, count)
        mac.reversedArray().copyInto(head, 0x2A)
        for (i in 0 until 4) head[0x30 + i] = (id shr (8 * i)).toByte()
        putLe16(head, 0x34, checksum(payload))
        val padded = payload.copyOf(payload.size + (16 - payload.size % 16) % 16)
        val packet = head + aes(Cipher.ENCRYPT_MODE, key, padded)
        putLe16(packet, 0x20, checksum(packet))
        return packet
    }

    /** Revisa la respuesta y devuelve su contenido descifrado. */
    fun parseResponse(resp: ByteArray, key: ByteArray): ByteArray {
        if (resp.size < 0x38) throw BroadlinkException("respuesta muy corta (${resp.size} bytes)")
        val expected = le16(resp, 0x20)
        val real = (checksum(resp) - (resp[0x20].toInt() and 0xFF) - (resp[0x21].toInt() and 0xFF)) and 0xFFFF
        if (expected != real) throw BroadlinkException("respuesta con checksum inválido")
        val error = le16(resp, 0x22).toShort().toInt()
        if (error != 0) throw BroadlinkException(errorText(error), error)
        val body = resp.copyOfRange(0x38, resp.size)
        if (body.size % 16 != 0) throw BroadlinkException("respuesta cifrada de largo inválido")
        return aes(Cipher.DECRYPT_MODE, key, body)
    }

    private fun errorText(code: Int) = when (code) {
        -1 -> "autenticación rechazada (¿el aire está bloqueado en AC Freedom?)"
        -2 -> "sesión cerrada"
        -3 -> "el equipo está desconectado"
        -4 -> "comando no soportado"
        -5 -> "el equipo rechazó la conexión (¿está bloqueado por AC Freedom?)"
        ERROR_KEY_EXPIRED -> "la clave de control expiró"
        else -> "error $code del equipo"
    }

    /** Seguridad del Wi-Fi para [setupPacket], con los códigos de Broadlink. */
    enum class WifiSecurity(val code: Int, val label: String) {
        NONE(0, "Sin clave"), WEP(1, "WEP"), WPA(2, "WPA"), WPA2(3, "WPA2"), WPA_WPA2(4, "WPA/WPA2"),
    }

    const val MAX_SSID = 32
    const val MAX_PASSWORD = 32

    /**
     * Paquete que configura el Wi-Fi de un equipo en modo AP (cuando crea su propia red). El equipo se reinicia,
     * se conecta a la red indicada y queda sin bloqueo, porque no pasa por la nube.
     */
    fun setupPacket(ssid: String, password: String, security: WifiSecurity): ByteArray {
        val s = ssid.toByteArray(Charsets.UTF_8)
        val pw = password.toByteArray(Charsets.UTF_8)
        if (s.isEmpty() || s.size > MAX_SSID) throw BroadlinkException("el nombre del Wi-Fi debe tener entre 1 y $MAX_SSID caracteres")
        if (pw.size > MAX_PASSWORD) throw BroadlinkException("la clave del Wi-Fi puede tener hasta $MAX_PASSWORD caracteres")
        val p = ByteArray(0x88)
        p[0x26] = 0x14
        s.copyInto(p, 0x44)
        pw.copyInto(p, 0x64)
        p[0x84] = s.size.toByte()
        p[0x85] = pw.size.toByte()
        p[0x86] = security.code.toByte()
        putLe16(p, 0x20, checksum(p))
        return p
    }

    fun authPayload(): ByteArray {
        val p = ByteArray(0x50)
        for (i in 0x04 until 0x14) p[i] = 0x31
        p[0x1E] = 0x01
        p[0x2D] = 0x01
        "Test 1".toByteArray(Charsets.US_ASCII).copyInto(p, 0x30)
        return p
    }

    /** De la respuesta de autenticación: el id de sesión y la clave AES nueva. */
    fun parseAuth(payload: ByteArray): Pair<Int, ByteArray> {
        if (payload.size < 0x14) throw BroadlinkException("respuesta de autenticación muy corta")
        return le32(payload, 0) to payload.copyOfRange(0x04, 0x14)
    }
}

// ---------- aire acondicionado (tipo "HVAC" de python-broadlink) ----------

enum class AcMode(val code: Int) {
    AUTO(0), COOL(1), DRY(2), HEAT(3), FAN(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class FanSpeed(val code: Int) {
    AUTO(5), LOW(3), MID(2), HIGH(1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Estado completo del aire. Al cambiar algo se envía todo, así que los campos que la app no muestra
 * se guardan tal como los reportó el aire para no tocarlos.
 */
data class AcState(
    val power: Boolean = false,
    val targetTemp: Double = 24.0,
    val mode: AcMode = AcMode.COOL,
    val speed: FanSpeed = FanSpeed.AUTO,
    val preset: Int = 0, // 0 normal, 1 turbo, 2 silencio
    val swingH: Int = 7, // 0 oscila, 7 fijo
    val swingV: Int = 7, // 0 oscila, 1–5 posición, 7 fijo
    val sleep: Boolean = false,
    val ifeel: Boolean = false,
    val display: Boolean = true,
    val health: Boolean = false,
    val clean: Boolean = false,
    val mildew: Boolean = false,
) {
    companion object {
        const val MIN_TEMP = 16.0
        const val MAX_TEMP = 32.0
    }
}

object Hvac {
    /** CRC-16 reflejado con polinomio 0x9BE4 e inicio 0xFFFF, como en python-broadlink. */
    fun crc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x9BE4 else crc ushr 1 }
        }
        return crc
    }

    /** Envuelve un comando del aire: largo, encabezado fijo, datos y CRC. */
    fun encode(data: ByteArray): ByteArray {
        val len = 10 + data.size
        val head = byteArrayOf(
            len.toByte(), (len shr 8).toByte(), 0xBB.toByte(), 0x00, 0x06, 0x80.toByte(), 0x00, 0x00,
            data.size.toByte(), (data.size shr 8).toByte(),
        )
        val packet = head + data
        val c = crc(packet, 2)
        return packet + byteArrayOf(c.toByte(), (c shr 8).toByte())
    }

    fun decode(payload: ByteArray): ByteArray {
        if (payload.size < 0x0A) throw BroadlinkException("respuesta del aire muy corta")
        val len = Broadlink.le16(payload, 0)
        if (len + 2 > payload.size || len < 0x0A) throw BroadlinkException("respuesta del aire de largo inválido")
        if (Broadlink.le16(payload, len) != crc(payload, 2, len)) throw BroadlinkException("respuesta del aire con CRC inválido")
        val dataLen = Broadlink.le16(payload, 0x08)
        if (0x0A + dataLen > len) throw BroadlinkException("respuesta del aire de largo inválido")
        return payload.copyOfRange(0x0A, 0x0A + dataLen)
    }

    /** Comando 0 fija el estado, 1 lo pide, 2 pide la información (temperatura ambiente). */
    fun command(cmd: Int, data: ByteArray = ByteArray(0)) = encode(byteArrayOf(((cmd shl 4) or 1).toByte(), 1) + data)

    /** Datos de la respuesta, sin los dos bytes de prefijo. */
    fun responseData(payload: ByteArray): ByteArray {
        val d = decode(payload)
        if (d.size < 2) throw BroadlinkException("respuesta del aire vacía")
        return d.copyOfRange(2, d.size)
    }

    private fun bit(b: Byte, n: Int) = (b.toInt() shr n) and 1 == 1

    fun parseState(d: ByteArray): AcState {
        if (d.size < 13) throw BroadlinkException("estado del aire muy corto (${d.size} bytes)")
        val u = { i: Int -> d[i].toInt() and 0xFF }
        return AcState(
            power = bit(d[0x08], 5),
            targetTemp = 8 + (u(0x00) shr 3) + (u(0x04) shr 7) * 0.5,
            mode = AcMode.of(u(0x05) shr 5),
            speed = FanSpeed.of(u(0x03) shr 5),
            preset = (u(0x04) shr 6) and 0b11,
            swingH = u(0x01) shr 5,
            swingV = u(0x00) and 0b111,
            sleep = bit(d[0x05], 2),
            ifeel = bit(d[0x05], 3),
            display = bit(d[0x0A], 4),
            health = bit(d[0x08], 1),
            clean = bit(d[0x08], 2),
            mildew = bit(d[0x0A], 3),
        )
    }

    fun stateData(s: AcState): ByteArray {
        var speed = s.speed
        var preset = s.preset
        // Las mismas reglas de python-broadlink: silencio solo ventilando y turbo solo enfriando o calentando.
        if (preset == 2) {
            if (s.mode == AcMode.FAN) speed = FanSpeed.LOW else preset = 0
        } else if (preset == 1) {
            if (s.mode == AcMode.COOL || s.mode == AcMode.HEAT) speed = FanSpeed.HIGH else preset = 0
        }
        val temp = (Math.round(s.targetTemp.coerceIn(AcState.MIN_TEMP, AcState.MAX_TEMP) * 2) / 2.0)
        val whole = temp.toInt()
        val half = temp - whole >= 0.5
        val b = { v: Boolean -> if (v) 1 else 0 }
        val d = ByteArray(0x0D)
        d[0x00] = (((whole - 8) shl 3) or (s.swingV and 0b111)).toByte()
        d[0x01] = (((s.swingH and 0b111) shl 5) or 0b100).toByte()
        d[0x02] = ((b(half) shl 7) or 0b1101).toByte()
        d[0x03] = (speed.code shl 5).toByte()
        d[0x04] = (preset shl 6).toByte()
        d[0x05] = ((s.mode.code shl 5) or (b(s.sleep) shl 2) or (b(s.ifeel) shl 3)).toByte()
        d[0x08] = ((b(s.power) shl 5) or (b(s.clean) shl 2) or (if (s.health) 0b11 else 0)).toByte()
        d[0x0A] = ((b(s.display) shl 4) or (b(s.mildew) shl 3)).toByte()
        d[0x0C] = 0b101
        return d
    }

    /** Temperatura ambiente de la respuesta al comando 2, o null si el aire no la informa. */
    fun parseAmbient(d: ByteArray): Double? {
        if (d.size < 22) return null
        val whole = d[0x05].toInt() and 0b11111
        val tenths = d[0x15].toInt() and 0b11111
        if (whole == 0 && tenths == 0) return null
        return whole + tenths / 10.0
    }
}

/** Cómo viaja un paquete y vuelve su respuesta (UDP en Android, un simulador en pruebas). */
fun interface BroadlinkTransport {
    suspend fun request(packet: ByteArray): ByteArray
}

/** Una sesión con un aire. Hay que llamar a [auth] antes de los demás comandos. */
class BroadlinkAc(val devtype: Int, val mac: ByteArray, private val transport: BroadlinkTransport) {
    private var id = 0
    private var key = Broadlink.INIT_KEY
    private var count = Broadlink.randomCount()

    var authenticated = false
        private set

    private suspend fun send(type: Int, payload: ByteArray): ByteArray {
        count = ((count + 1) or 0x8000) and 0xFFFF
        val packet = Broadlink.commandPacket(devtype, count, mac, id, key, type, payload)
        return Broadlink.parseResponse(transport.request(packet), key)
    }

    suspend fun auth() {
        id = 0
        key = Broadlink.INIT_KEY
        authenticated = false
        val (newId, newKey) = Broadlink.parseAuth(send(Broadlink.CMD_AUTH, Broadlink.authPayload()))
        id = newId
        key = newKey
        authenticated = true
    }

    private suspend fun hvac(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        try {
            Hvac.responseData(send(Broadlink.CMD_DATA, Hvac.command(cmd, data)))
        } catch (e: BroadlinkException) {
            if (e.code == Broadlink.ERROR_KEY_EXPIRED) authenticated = false
            throw e
        }

    suspend fun getState(): AcState = Hvac.parseState(hvac(1))

    suspend fun setState(s: AcState): AcState = Hvac.parseState(hvac(0, Hvac.stateData(s)))

    suspend fun getAmbient(): Double? = Hvac.parseAmbient(hvac(2))
}

/** El tipo de equipo de los aires con módulo AC Freedom, según python-broadlink. */
const val HVAC_DEVTYPE = 0x4E2A
