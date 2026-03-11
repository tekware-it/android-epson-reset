package info.tekware.aereset.protocol

import info.tekware.aereset.data.PrinterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EpsonProtocolTest {
    private val spec = PrinterSpec(
        modelName = "ET-2810 Series",
        controlModel = byteArrayOf(0x4A, 0x36),
        keyword = byteArrayOf(0x4E, 0x62, 0x73, 0x6A, 0x63, 0x62, 0x7A, 0x62),
        wasteCounters = emptyList(),
        resetMap = emptyMap(),
    )
    private val protocol = EpsonProtocol(spec)

    @Test
    fun `status request matches ez-reset framing`() {
        val expected = byteArrayOf(0x73, 0x74, 0x01, 0x00, 0x01)
        assertArrayEquals(expected, protocol.buildStatusRequest())
    }

    @Test
    fun `factory write command matches service payload format`() {
        val command = protocol.buildWriteEepromCommand(0x30, 0x00)
        val expectedPrefix = byteArrayOf(
            0x7C,
            0x7C,
            0x0D,
            0x00,
            0x4A,
            0x36,
            0x42,
            0xBD.toByte(),
            0x21,
            0x30,
            0x00,
            0x00,
        )
        assertArrayEquals(expectedPrefix, command.copyOfRange(0, expectedPrefix.size))
        assertEquals(spec.keyword.size + expectedPrefix.size, command.size)
    }

    @Test
    fun `waste counter follows ez-reset little-endian byte assembly`() {
        val value = protocol.parseWasteCounter(
            rawBytes = byteArrayOf(0xCA.toByte(), 0x18, 0x00),
            max = 9999,
            name = "Counter",
        )
        assertEquals(6346, value.rawValue)
    }
}
