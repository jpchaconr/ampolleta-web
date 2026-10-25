package cl.jpchacon.ampolleta.ac

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun hex(s: String) = Broadlink.hex(s)
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/**
 * Aire simulado que sigue lo que hace el módulo Broadlink: responde la autenticación con un id y una
 * clave nuevos, y guarda el estado que le fijan. Usa javax.crypto directamente, no el código de la app.
 */
private class FakeAc {
    val iv = hex("562e17996d093d28ddb3ba695a2e6f58")
    var key = hex("097628343fe99e23765c1513accf8b02")
    val sessionKey = hex("8899aabbccddeeff0011223344556677")
    var state = ByteArray(13).also { it[0] = ((24 - 8) shl 3 or 7).toByte(); it[3] = (5 shl 5).toByte(); it[5] = (1 shl 5).toByte(); it[0x0A] = 0x10 }
    var expireKey = false
    val commands = mutableListOf<Int>()

    private fun aes(mode: Int, k: ByteArray, d: ByteArray) =
        Cipher.getInstance("AES/CBC/NoPadding").apply { init(mode, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }.doFinal(d)
    private fun sum(b: ByteArray) = b.fold(0xBEAF) { a, x -> a + (x.toInt() and 0xFF) } and 0xFFFF
    private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun crc(b: ByteArray, from: Int, to: Int): Int {
        var c = 0xFFFF
        for (i in from until to) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x9BE4 else c ushr 1 }
        }
        return c
    }

    private fun reply(request: ByteArray, payload: ByteArray, error: Int = 0, k: ByteArray = key): ByteArray {
        val out = ByteArray(0x38)
        request.copyInto(out, 0, 0, 0x30)
        out[0x22] = error.toByte(); out[0x23] = (error shr 8).toByte()
        val body = if (error != 0) ByteArray(0) else aes(Cipher.ENCRYPT_MODE, k, payload.copyOf(payload.size + (16 - payload.size % 16) % 16))
        val full = out + body
        full[0x20] = 0; full[0x21] = 0
        val c = sum(full)
        full[0x20] = c.toByte(); full[0x21] = (c shr 8).toByte()
        return full
    }

    private fun wrap(data: ByteArray): ByteArray {
        val len = 10 + data.size
        val p = byteArrayOf(len.toByte(), 0, 0xBB.toByte(), 0, 0x07, 0, 0, 0, data.size.toByte(), 0) + data
        val c = crc(p, 2, p.size)
        return p + byteArrayOf(c.toByte(), (c shr 8).toByte())
    }

    fun handle(packet: ByteArray): ByteArray {
        val copy = packet.copyOf().also { it[0x20] = 0; it[0x21] = 0 }
        assertEquals("checksum del paquete", le16(packet, 0x20), sum(copy))
        assertArrayEquals(hex("5aa5aa555aa5aa55"), packet.copyOf(8))
        assertEquals(HVAC_DEVTYPE, le16(packet, 0x24))
        val type = le16(packet, 0x26)
        val payload = aes(Cipher.DECRYPT_MODE, key, packet.copyOfRange(0x38, packet.size))
        if (type == 0x65) {
            val out = ByteArray(0x20)
            out[0] = 7
            sessionKey.copyInto(out, 4)
            val r = reply(packet, out)
            key = sessionKey
            return r
        }
        assertEquals(0x6A, type)
        if (expireKey) {
            expireKey = false
            return reply(packet, ByteArray(0), error = 0xFFF9)
        }
        val len = le16(payload, 0)
        assertEquals("CRC del comando", le16(payload, len), crc(payload, 2, len))
        val cmd = (payload[0x0A].toInt() and 0xFF) shr 4
        commands += cmd
        return when (cmd) {
            0 -> { state = payload.copyOfRange(0x0C, 0x0C + 13); reply(packet, wrap(byteArrayOf(0x01, 0x01) + state)) }
            1 -> reply(packet, wrap(byteArrayOf(0x11, 0x01) + state))
            else -> reply(packet, wrap(byteArrayOf(0x21, 0x01) + ByteArray(24).also { it[5] = 25; it[0x15] = 6 }))
        }
    }
}

class BroadlinkTest {
    private val mac = hex("34ea34aabbcc")

    // Vectores generados con python-broadlink 0.19.

    @Test
    fun helloComoPythonBroadlink() {
        val c = Calendar.getInstance(TimeZone.getTimeZone("GMT-03:00")).apply { clear(); set(2026, Calendar.OCTOBER, 25, 14, 7, 0) }
        assertEquals(
            "0000000000000000fdffffffea07070e1a07190a000000000000000000000000f9c30000000006000000000000000000",
            Broadlink.helloPacket(c).toHex(),
        )
    }

    @Test
    fun paquetesComoPythonBroadlink() {
        assertEquals(
            "5aa5aa555aa5aa55000000000000000000000000000000000000000000000000d0f000002a4e65000190ccbbaa34ea3400000000b2c30000" +
                "453452e7f92eda958344930835ef9a6d93b0b6da60530408ebba79410b080296f9f7cd7779b46f2513e2c5bbd4450e907fa1ba8fc5e0169776e2620824fff3f85f6f64f7120b1f724ff1b048b76e3e30",
            Broadlink.commandPacket(HVAC_DEVTYPE, 0x9001, mac, 0, Broadlink.INIT_KEY, Broadlink.CMD_AUTH, Broadlink.authPayload()).toHex(),
        )
        val key = hex("00112233445566778899aabbccddeeff")
        assertEquals(
            "5aa5aa555aa5aa5500000000000000000000000000000000000000000000000078d100002a4e6a000290ccbbaa34ea3404030201bfc000002f76ce7874f46fe0ab4a5b5ab9d50f64",
            Broadlink.commandPacket(HVAC_DEVTYPE, 0x9002, mac, 0x01020304, key, Broadlink.CMD_DATA, Hvac.command(1)).toHex(),
        )
        val st = AcState(power = true, targetTemp = 22.5, mode = AcMode.HEAT, speed = FanSpeed.MID, display = true)
        assertEquals(
            "5aa5aa555aa5aa55000000000000000000000000000000000000000000000000e1d600002a4e6a000390ccbbaa34ea340403020114c40000" +
                "df7c8b5fdcafc4a976bb0284681fce5ae239357ec916cd33270e156c38625b61",
            Broadlink.commandPacket(HVAC_DEVTYPE, 0x9003, mac, 0x01020304, key, Broadlink.CMD_DATA, Hvac.command(0, Hvac.stateData(st))).toHex(),
        )
        assertEquals("0d00bb00068000000300010100d918", Hvac.encode(hex("010100")).toHex())
    }

    @Test
    fun leeEstadoComoPythonBroadlink() {
        val s = Hvac.parseState(Hvac.responseData(hex("1900bb00068000000f00110177e48d40006400002000100005a489")))
        assertEquals(AcState(power = true, targetTemp = 22.0, mode = AcMode.HEAT, speed = FanSpeed.MID, sleep = true, display = true), s)
        val info = Hvac.responseData(hex("2400bb00068000001a0021010001000000190000000000000000000000000000000600008167"))
        assertEquals(25.6, Hvac.parseAmbient(info)!!, 0.001)
        assertNull(Hvac.parseAmbient(ByteArray(22)))
    }

    @Test
    fun crcInvalidoSeRechaza() {
        val bad = hex("1900bb00068000000f00110177e48d40006400002000100005a488")
        try {
            Hvac.responseData(bad)
            fail()
        } catch (e: BroadlinkException) {
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test
    fun ida_y_vuelta_del_estado() {
        for (mode in AcMode.entries) for (speed in FanSpeed.entries) for (t in listOf(16.0, 21.0, 24.0, 32.0)) {
            val s = AcState(power = true, targetTemp = t, mode = mode, speed = speed, display = false, swingV = 0)
            assertEquals(s, Hvac.parseState(Hvac.stateData(s)))
        }
        // Fuera de rango se ajusta a 16–32.
        assertEquals(32.0, Hvac.parseState(Hvac.stateData(AcState(targetTemp = 40.0))).targetTemp, 0.0)
        // Turbo solo enfriando o calentando.
        assertEquals(0, Hvac.parseState(Hvac.stateData(AcState(preset = 1, mode = AcMode.DRY))).preset)
        val turbo = Hvac.parseState(Hvac.stateData(AcState(preset = 1, mode = AcMode.COOL, speed = FanSpeed.LOW)))
        assertEquals(1, turbo.preset)
        assertEquals(FanSpeed.HIGH, turbo.speed)
    }

    @Test
    fun parseaRespuestaDeBusqueda() {
        val r = ByteArray(0x80)
        r[0x34] = 0x2A; r[0x35] = 0x4E
        hex("ccbbaa34ea34").copyInto(r, 0x3A)
        "Aire pieza".toByteArray().copyInto(r, 0x40)
        val d = Broadlink.parseHello(r, "192.168.1.50")!!
        assertEquals(HVAC_DEVTYPE, d.devtype)
        assertEquals("34:EA:34:AA:BB:CC", d.macText)
        assertEquals("Aire pieza", d.name)
        assertFalse(d.locked)
        assertNull(Broadlink.parseHello(ByteArray(10), "x"))
    }

    @Test
    fun sesionConAireSimulado() = runBlocking {
        val fake = FakeAc()
        val ac = BroadlinkAc(HVAC_DEVTYPE, mac) { fake.handle(it) }
        ac.auth()
        assertTrue(ac.authenticated)
        val s = ac.getState()
        assertEquals(24.0, s.targetTemp, 0.0)
        assertEquals(AcMode.COOL, s.mode)
        assertTrue(s.display)

        val nuevo = ac.setState(s.copy(power = true, targetTemp = 20.0, speed = FanSpeed.HIGH))
        assertTrue(nuevo.power)
        assertEquals(20.0, nuevo.targetTemp, 0.0)
        assertEquals(FanSpeed.HIGH, nuevo.speed)
        assertTrue("no apaga la pantalla", nuevo.display)
        assertEquals(25.6, ac.getAmbient()!!, 0.001)
        assertEquals(listOf(1, 0, 2), fake.commands)

        // Si la clave expira, la sesión queda sin autenticar para volver a hacerlo.
        fake.expireKey = true
        try {
            ac.getState()
            fail()
        } catch (e: BroadlinkException) {
            assertEquals(Broadlink.ERROR_KEY_EXPIRED, e.code)
        }
        assertFalse(ac.authenticated)
    }
}
