package cl.jpchacon.ampolleta.tuya

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ampolleta simulada que sigue la lógica del SDK BLE de Tuya: sin vincular tiene la clave de login
 * en ceros, descarta en silencio lo que no puede descifrar y el PAIR guarda la clave y la vincula.
 * Usa javax.crypto directamente, no el código de la app.
 */
private class FakeBulb(val uuid: String, var loginKey: ByteArray, var bound: Boolean, val scope: CoroutineScope) {
    lateinit var device: TuyaDevice
    var srand: ByteArray? = null
    val received = mutableListOf<Pair<Int, ByteArray>>()
    private var buf = ByteArray(0)
    private var expected = 0
    private var seq = 100

    private fun md5(vararg p: ByteArray) = java.security.MessageDigest.getInstance("MD5").also { md -> p.forEach { md.update(it) } }.digest()
    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, d: ByteArray) =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(d)
    private fun key(flag: Int) = if (flag == 4) md5(loginKey) else md5(loginKey, srand!!)

    fun receive(packet: ByteArray) {
        assertTrue(packet.size <= 20)
        var (n, pos) = TuyaCrypto.unpackInt(packet, 0)
        if (n == 0) {
            val (len, p2) = TuyaCrypto.unpackInt(packet, pos)
            expected = len
            assertEquals(0x30, packet[p2].toInt())
            pos = p2 + 1
            buf = ByteArray(0)
        }
        buf += packet.copyOfRange(pos, packet.size)
        if (buf.size < expected) return
        val flag = buf[0].toInt()
        if (flag == 5 && srand == null) return
        val raw = aes(Cipher.DECRYPT_MODE, key(flag), buf.copyOfRange(1, 17), buf.copyOfRange(17, buf.size))
        val bb = ByteBuffer.wrap(raw)
        val s = bb.int
        bb.int
        val code = bb.short.toInt() and 0xFFFF
        val len = bb.short.toInt() and 0xFFFF
        if (12 + len + 2 > raw.size) return
        val crc = ((raw[12 + len].toInt() and 0xFF) shl 8) or (raw[13 + len].toInt() and 0xFF)
        if (TuyaCrypto.crc16(raw, 12 + len) != crc) return // clave equivocada: silencio
        val data = raw.copyOfRange(12, 12 + len)
        received += code to data
        when (code) {
            Code.DEVICE_INFO -> {
                srand = "R4nd0m".toByteArray()
                val info = ByteArray(46)
                info[0] = 1; info[2] = 3; info[3] = 2
                info[5] = if (bound) 1 else 0
                srand!!.copyInto(info, 6)
                send(code, info, s, flag)
            }
            Code.PAIR -> {
                val result = when {
                    String(data.copyOfRange(0, 16)) != uuid -> 1
                    bound -> 2
                    else -> { loginKey = data.copyOfRange(16, 22); bound = true; 0 }
                }
                send(code, byteArrayOf(result.toByte()), s, 5)
            }
            Code.DEVICE_STATUS -> {
                send(code, byteArrayOf(0), s, 5)
                val colour = "00f003e803e8".toByteArray()
                send(Code.RECEIVE_DP, byteArrayOf(1, 1, 1, 1, 2, 4, 1, 1, 5, 3, 12) + colour, 0, 5)
            }
            Code.DPS -> send(code, byteArrayOf(0), s, 5)
        }
    }

    private fun send(code: Int, data: ByteArray, responseTo: Int, flag: Int) {
        val body = ByteBuffer.allocate(14 + data.size).putInt(seq++).putInt(responseTo).putShort(code.toShort()).putShort(data.size.toShort()).put(data).array()
        val crc = TuyaCrypto.crc16(body, 12 + data.size)
        body[12 + data.size] = (crc shr 8).toByte()
        body[13 + data.size] = crc.toByte()
        val iv = ByteArray(16) { it.toByte() }
        val enc = byteArrayOf(flag.toByte()) + iv + aes(Cipher.ENCRYPT_MODE, key(flag), iv, body.copyOf((body.size + 15) / 16 * 16))
        val packets = mutableListOf<ByteArray>()
        var pos = 0
        var n = 0
        while (pos < enc.size) {
            var head = TuyaCrypto.packInt(n)
            if (n == 0) head += TuyaCrypto.packInt(enc.size) + byteArrayOf(0x30)
            val end = minOf(enc.size, pos + 20 - head.size)
            packets += head + enc.copyOfRange(pos, end)
            pos = end
            n++
        }
        scope.launch {
            delay(1)
            packets.forEach { device.onNotification(it) }
        }
    }
}

class TuyaDeviceTest {
    private val creds = Credentials("tuyaac3c9b3d9b2e", "+isNwj=H{jRPMS3s", "ebbeeagbrzm656fp")

    private fun session(bulb: FakeBulb, scope: CoroutineScope, onDps: (List<Datapoint>) -> Unit = {}): TuyaDevice {
        val d = TuyaDevice(creds, { bulb.receive(it) }, scope)
        d.onDatapoints = onDps
        bulb.device = d
        return d
    }

    @Test
    fun md5AndUuidFromRealAdvertisement() {
        // Datos reales de la ampolleta de Juan Pablo (capturados con nRF Connect).
        val service = byteArrayOf(0) + "azsnpwzx".toByteArray()
        val mfr = hex("0003000001005FA9EEC94B37EEAA5475E883EFAA0684")
        val info = Advertisement.parse(service, mfr)!!
        assertEquals("tuyaac3c9b3d9b2e", info.uuid)
        assertEquals("azsnpwzx", info.productId)
        assertFalse(info.bound)
        assertEquals(3, info.protocolVersion)
    }

    @Test
    fun alreadyBound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bulb = FakeBulb(creds.uuid, creds.loginKey, true, scope)
        var dps: List<Datapoint>? = null
        val d = session(bulb, scope) { dps = it }
        assertFalse(d.handshake())
        d.requestStatus()
        delay(100)
        assertEquals(listOf(Datapoint(1, DpType.BOOL, true), Datapoint(2, DpType.ENUM, 1), Datapoint(5, DpType.STRING, "00f003e803e8")), dps)
        d.setDatapoints(listOf(Datapoint(1, DpType.BOOL, false), Datapoint(3, DpType.VALUE, 1000)))
        assertArrayEquals(byteArrayOf(1, 1, 1, 0, 3, 2, 4, 0, 0, 3, 0xe8.toByte()), bulb.received.last { it.first == Code.DPS }.second)
        assertTrue(bulb.received.any { it.first == Code.RECEIVE_DP })
        scope.cancel()
    }

    @Test
    fun unboundGetsBoundWithOurKey() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val own = Credentials.generate(creds.uuid)
        val bulb = FakeBulb(creds.uuid, ByteArray(6), false, scope)
        val d = TuyaDevice(own, { bulb.receive(it) }, scope).also { bulb.device = it }
        assertTrue(d.handshake())
        assertTrue(bulb.bound)
        assertArrayEquals(own.loginKey, bulb.loginKey)
        d.setDatapoints(listOf(Datapoint(1, DpType.BOOL, true)))

        // Reconexión: ya vinculada con nuestra clave.
        bulb.srand = null
        val d2 = TuyaDevice(own, { bulb.receive(it) }, scope).also { bulb.device = it }
        assertFalse(d2.handshake())
        d2.setDatapoints(listOf(Datapoint(1, DpType.BOOL, false)))
        scope.cancel()
    }

    @Test
    fun wrongUuidIsRejectedAndNotBound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bulb = FakeBulb("tuya9b9e6e1dcf9c", ByteArray(6), false, scope)
        val d = session(bulb, scope)
        val error = runCatching { d.handshake() }.exceptionOrNull()
        assertTrue(error is TuyaProtocolException)
        assertFalse(bulb.bound)
        scope.cancel()
    }

    @Test
    fun lightColourEncoding() {
        val p = LightProfile()
        val s = LightState(on = true, mode = LightMode.COLOUR, brightness = 100, hue = 240, saturation = 100)
        assertEquals("00f003e803e8", Light.colourDp(p, s).value)
        val back = Light.apply(p, LightState(), listOf(Datapoint(2, DpType.ENUM, 1), Datapoint(5, DpType.STRING, "007801f40064")))
        assertEquals(LightMode.COLOUR, back.mode)
        assertEquals(120, back.hue)
        assertEquals(50, back.saturation)
        assertEquals(10, back.brightness)
        assertEquals(10, (Light.brightDp(p, LightState(brightness = 0)).value as Int).coerceAtMost(10))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
