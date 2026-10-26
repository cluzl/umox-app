package com.umox.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte vektor dihasilkan dari node-carplay 4.x (SendableMessage.serialise()) untuk config yang sama. */
class CarplayDongleTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun wireFormatMatchesNodeCarplay() {
        assertEquals("aa55aa5500000000aa00000055ffffff", hex(CarplayDongle.message(CarplayDongle.HEARTBEAT)))
        assertEquals("aa55aa550400000008000000f7ffffff0c000000", hex(CarplayDongle.command(CarplayDongle.CMD_FRAME)))
        assertEquals("aa55aa550000000015000000eaffffff", hex(CarplayDongle.message(CarplayDongle.CLOSE_DONGLE)))

        val init = CarplayDongle.initMessages(960, 360, 160, 1700000000).map(::hex)
        assertEquals("aa55aa551c0000009900000066ffffff100000002f746d702f73637265656e5f6470690004000000a0000000", init[0])
        assertEquals("aa55aa551c00000001000000feffffffc0030000680100001e0000000500000000c000000200000002000000", init[1])
        assertEquals("aa55aa551c0000009900000066ffffff100000002f746d702f6e696768745f6d6f6465000400000000000000", init[2])
        assertEquals("aa55aa551a0000009900000066ffffff0e0000002f6574632f626f785f6e616d650004000000554d4f58", init[5])
        assertEquals(
            "aa55aa555600000019000000e6ffffff7b226d6564696144656c6179223a3330302c2273796e6354696d65223a313730303030303030302c22616e64726f69644175746f53697a6557223a3936302c22616e64726f69644175746f53697a6548223a3336307d",
            init[6],
        )
    }

    @Test
    fun keyFrameDetection() {
        assertTrue(CarplayDongle.hasKeyFrame(bytes("0000000167640028") + bytes("0000000168ee") + bytes("00000001658880")))
        assertTrue(CarplayDongle.hasKeyFrame(bytes("00000165aa")))
        assertFalse(CarplayDongle.hasKeyFrame(bytes("0000000141e2")))
        assertFalse(CarplayDongle.hasKeyFrame(ByteArray(0)))
    }

    @Test
    fun parseSize() {
        assertEquals(Triple(1280, 480, 160), CarplayDongle.parseSize(null))
        assertEquals(Triple(960, 360, 160), CarplayDongle.parseSize("960x360"))
        assertEquals(Triple(1920, 720, 200), CarplayDongle.parseSize(" 1920 x 720 @ 200 "))
        assertEquals(Triple(1280, 480, 160), CarplayDongle.parseSize("garbage"))
    }
}
