package cl.jpchacon.ampolleta.tuya

// Protocolo Tuya BLE v3, sin dependencias de Android para poder probarlo en la JVM.
// Basado en ha_tuya_ble (PlusPlus-ua/ha_tuya_ble) y en el SDK BLE de Tuya.

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TuyaUuids {
    const val SERVICE = "00001910-0000-1000-8000-00805f9b34fb"
    const val CHAR_NOTIFY = "00002b10-0000-1000-8000-00805f9b34fb"
    const val CHAR_WRITE = "00002b11-0000-1000-8000-00805f9b34fb"
    const val ADV_SERVICE = "0000a201-0000-1000-8000-00805f9b34fb"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER_ID = 0x07D0
}

object Code {
    const val DEVICE_INFO = 0x0000
    const val PAIR = 0x0001
    const val DPS = 0x0002
    const val DEVICE_STATUS = 0x0003
    const val UNBIND = 0x0005
    const val RECEIVE_DP = 0x8001
    const val RECEIVE_TIME_DP = 0x8003
    const val RECEIVE_SIGN_DP = 0x8004
    const val RECEIVE_SIGN_TIME_DP = 0x8005
    const val TIME1_REQ = 0x8011
    const val TIME2_REQ = 0x8012
}

enum class DpType(val code: Int) {
    RAW(0), BOOL(1), VALUE(2), STRING(3), ENUM(4), BITMAP(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** value es Boolean (BOOL), Int (VALUE, ENUM), String (STRING) o ByteArray (RAW, BITMAP). */
data class Datapoint(val id: Int, val type: DpType, val value: Any)

class TuyaProtocolException(message: String) : Exception(message)

object TuyaCrypto {
    private val random = SecureRandom()

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, key, iv, data)

    fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, key, iv, data)

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                val tmp = crc and 1
                crc = crc shr 1
                if (tmp != 0) crc = crc xor 0xA001
            }
        }
        return crc
    }

    fun packInt(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }

    /** Devuelve (valor, posición siguiente). */
    fun unpackInt(data: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        for (offset in 0 until 4) {
            val pos = start + offset
            if (pos >= data.size) throw TuyaProtocolException("varint truncado")
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F) shl (offset * 7))
            if (b and 0x80 == 0) return result to pos + 1
        }
        throw TuyaProtocolException("varint inválido")
    }

    private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun randomString(n: Int) = String(CharArray(n) { ALPHANUM[random.nextInt(ALPHANUM.length)] })
}

/** Lo que una ampolleta Tuya anuncia por Bluetooth, sin conectarse. */
data class AdvertisementInfo(val productId: String, val uuid: String?, val bound: Boolean, val protocolVersion: Int)

object Advertisement {
    /**
     * serviceData: datos del servicio 0xA201. manufacturerData: datos del fabricante 0x07D0
     * (sin el id de empresa). En ambos estados el uuid va cifrado con md5 de lo anunciado en serviceData.
     */
    fun parse(serviceData: ByteArray?, manufacturerData: ByteArray?): AdvertisementInfo? {
        if (serviceData == null || serviceData.size < 2 || manufacturerData == null || manufacturerData.size < 22) return null
        val pidBytes = serviceData.copyOfRange(1, serviceData.size)
        val bound = (manufacturerData[0].toInt() and 0x80) != 0
        val version = manufacturerData[1].toInt() and 0xFF
        val uuid = try {
            val key = TuyaCrypto.md5(pidBytes)
            val raw = TuyaCrypto.aesDecrypt(key, key, manufacturerData.copyOfRange(6, 22))
            String(raw, Charsets.US_ASCII).takeIf { s -> s.all { it.code in 0x21..0x7E } }
        } catch (e: Exception) {
            null
        }
        // Sin vincular el id de producto va en claro; vinculada va cifrado.
        val productId = if (!bound) String(pidBytes, Charsets.US_ASCII) else ""
        return AdvertisementInfo(productId, uuid, bound, version)
    }
}

/** Credenciales con que la app vincula y habla con una ampolleta. */
data class Credentials(val uuid: String, val localKey: String, val deviceId: String) {
    val loginKey: ByteArray get() = localKey.take(LOGIN_KEY_LEN).toByteArray(Charsets.UTF_8)

    companion object {
        const val LOGIN_KEY_LEN = 6
        const val VIRTUAL_ID_LEN = 22

        /** Credenciales propias para vincular una ampolleta sin pasar por la nube de Tuya. */
        fun generate(uuid: String) = Credentials(
            uuid = uuid,
            localKey = TuyaCrypto.randomString(LOGIN_KEY_LEN),
            deviceId = "amp" + TuyaCrypto.randomString(VIRTUAL_ID_LEN - 3),
        )
    }
}

data class Message(val seq: Int, val responseTo: Int, val code: Int, val data: ByteArray)

object Datapoints {
    fun encode(dps: List<Datapoint>): ByteArray {
        val out = ByteArrayOutputStream()
        for (dp in dps) {
            val v = encodeValue(dp.type, dp.value)
            out.write(dp.id)
            out.write(dp.type.code)
            out.write(v.size)
            out.write(v)
        }
        return out.toByteArray()
    }

    private fun encodeValue(type: DpType, value: Any): ByteArray = when (type) {
        DpType.BOOL -> byteArrayOf(if (value as Boolean) 1 else 0)
        DpType.VALUE -> ByteBuffer.allocate(4).putInt(value as Int).array()
        DpType.ENUM -> {
            val v = value as Int
            when {
                v > 0xFFFF -> ByteBuffer.allocate(4).putInt(v).array()
                v > 0xFF -> byteArrayOf((v shr 8).toByte(), v.toByte())
                else -> byteArrayOf(v.toByte())
            }
        }
        DpType.STRING -> (value as String).toByteArray(Charsets.UTF_8)
        DpType.RAW, DpType.BITMAP -> value as ByteArray
    }

    fun parse(data: ByteArray, start: Int): List<Datapoint> {
        val dps = mutableListOf<Datapoint>()
        var pos = start
        while (data.size - pos >= 4) {
            val id = data[pos].toInt() and 0xFF
            val type = DpType.of(data[pos + 1].toInt() and 0xFF) ?: throw TuyaProtocolException("tipo de datapoint inválido")
            val len = data[pos + 2].toInt() and 0xFF
            pos += 3
            if (pos + len > data.size) throw TuyaProtocolException("datapoint truncado")
            val raw = data.copyOfRange(pos, pos + len)
            val value: Any = when (type) {
                DpType.BOOL -> raw.any { it.toInt() != 0 }
                DpType.VALUE, DpType.ENUM -> {
                    var v = 0L
                    for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
                    if (raw.isNotEmpty() && raw[0] < 0) v -= 1L shl (8 * raw.size)
                    v.toInt()
                }
                DpType.STRING -> String(raw, Charsets.UTF_8)
                DpType.RAW, DpType.BITMAP -> raw
            }
            dps += Datapoint(id, type, value)
            pos += len
        }
        return dps
    }

    /** Salta el timestamp de los mensajes con hora. Devuelve la posición siguiente. */
    fun skipTimestamp(data: ByteArray, pos: Int): Int = when (data.getOrNull(pos)?.toInt()) {
        0 -> pos + 1 + 13
        1 -> pos + 1 + 4
        else -> throw TuyaProtocolException("timestamp inválido")
    }
}

/**
 * Arma y desarma paquetes: cifrado AES-CBC, CRC y fragmentación en trozos de 20 bytes.
 * Las claves las maneja quien la usa.
 */
class PacketCodec(var protocolVersion: Int = 3) {
    private var inBuf: ByteArrayOutputStream? = null
    private var expectedPacket = 0
    private var expectedLength = 0

    fun build(seq: Int, code: Int, data: ByteArray, responseTo: Int, flag: Int, key: ByteArray): List<ByteArray> {
        val raw = ByteBuffer.allocate(12 + data.size + 2)
        raw.putInt(seq).putInt(responseTo).putShort(code.toShort()).putShort(data.size.toShort()).put(data)
        val body = raw.array()
        val crc = TuyaCrypto.crc16(body, 12 + data.size)
        body[12 + data.size] = (crc shr 8).toByte()
        body[13 + data.size] = crc.toByte()
        val padded = body.copyOf((body.size + 15) / 16 * 16)

        val iv = TuyaCrypto.randomBytes(16)
        val encrypted = byteArrayOf(flag.toByte()) + iv + TuyaCrypto.aesEncrypt(key, iv, padded)

        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var n = 0
        while (pos < encrypted.size) {
            var head = TuyaCrypto.packInt(n)
            if (n == 0) head += TuyaCrypto.packInt(encrypted.size) + byteArrayOf((protocolVersion shl 4).toByte())
            val end = minOf(encrypted.size, pos + GATT_MTU - head.size)
            packets += head + encrypted.copyOfRange(pos, end)
            pos = end
            n++
        }
        return packets
    }

    /** Junta notificaciones. Devuelve el mensaje cifrado completo (bandera + iv + datos) o null si falta. */
    fun feed(packet: ByteArray): ByteArray? {
        val (num, afterNum) = TuyaCrypto.unpackInt(packet, 0)
        var pos = afterNum
        if (num != expectedPacket) {
            reset()
            if (num != 0) throw TuyaProtocolException("paquete fuera de orden ($num)")
        }
        if (num == 0) {
            val (len, afterLen) = TuyaCrypto.unpackInt(packet, pos)
            expectedLength = len
            pos = afterLen + 1 // versión de protocolo
            inBuf = ByteArrayOutputStream()
        }
        val buf = inBuf ?: throw TuyaProtocolException("paquete sin inicio")
        buf.write(packet, pos, packet.size - pos)
        expectedPacket++
        if (buf.size() < expectedLength) return null
        val full = buf.toByteArray().copyOf(expectedLength)
        reset()
        return full
    }

    fun reset() {
        inBuf = null
        expectedPacket = 0
        expectedLength = 0
    }

    companion object {
        const val GATT_MTU = 20

        fun decrypt(full: ByteArray, key: ByteArray): Message {
            val raw = TuyaCrypto.aesDecrypt(key, full.copyOfRange(1, 17), full.copyOfRange(17, full.size))
            val bb = ByteBuffer.wrap(raw)
            val seq = bb.int
            val responseTo = bb.int
            val code = bb.short.toInt() and 0xFFFF
            val length = bb.short.toInt() and 0xFFFF
            val end = 12 + length
            if (raw.size < end) throw TuyaProtocolException("longitud inválida")
            if (raw.size >= end + 2) {
                val crc = ((raw[end].toInt() and 0xFF) shl 8) or (raw[end + 1].toInt() and 0xFF)
                if (TuyaCrypto.crc16(raw, end) != crc) throw TuyaProtocolException("CRC inválido")
            }
            return Message(seq, responseTo, code, raw.copyOfRange(12, end))
        }
    }
}
