package com.tekware.aereset.protocol

import com.tekware.aereset.data.InkLevel
import com.tekware.aereset.data.PrinterSpec
import com.tekware.aereset.data.PrinterStatusSnapshot
import com.tekware.aereset.data.WasteCounterStatus
import kotlin.math.min

class EpsonProtocol(private val spec: PrinterSpec) {
    data class D4Frame(
        val psid: Int,
        val ssid: Int,
        val credit: Int,
        val control: Int,
        val payload: ByteArray,
    )

    data class D4ChannelState(
        val psid: Int,
        val ssid: Int,
        val mtu: Int,
        var txCredits: Int,
        var rxCredits: Int = 0,
        val rxCreditsMax: Int = 1,
    )

    fun buildEnterDot4Packet(): ByteArray {
        return byteArrayOf(0x00, 0x00, 0x00, 0x1B, 0x01) + "@EJL 1284.4\n@EJL\n@EJL\n".encodeToByteArray()
    }

    fun buildInitCommandPayload(): ByteArray = byteArrayOf(0x10)

    fun buildGetSocketIdPayload(name: String): ByteArray = name.encodeToByteArray()

    fun buildOpenChannelPayload(ssid: Int): ByteArray {
        return byteArrayOf(
            ssid.toByte(),
            ssid.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
        )
    }

    fun buildCreditPayload(psid: Int, ssid: Int, amount: Int): ByteArray {
        return byteArrayOf(
            psid.toByte(),
            ssid.toByte(),
            ((amount ushr 8) and 0xFF).toByte(),
            (amount and 0xFF).toByte(),
        )
    }

    fun buildControlCommand(command: ByteArray, payload: ByteArray): ByteArray {
        val payloadLength = payload.size
        return command +
            byteArrayOf((payloadLength and 0xFF).toByte(), ((payloadLength ushr 8) and 0xFF).toByte()) +
            payload
    }

    fun buildStatusRequest(): ByteArray = buildControlCommand("st".encodeToByteArray(), byteArrayOf(0x01))

    fun buildResetCommand(): List<ByteArray> {
        return spec.resetMap.entries.map { (address, value) ->
            buildWriteEepromCommand(address, value)
        }
    }

    fun buildReadEepromCommand(address: Int): ByteArray {
        return buildFactoryCommand(0x41, byteArrayOf((address and 0xFF).toByte(), ((address ushr 8) and 0xFF).toByte()))
    }

    fun buildWriteEepromCommand(address: Int, value: Int): ByteArray {
        val payload = byteArrayOf(
            (address and 0xFF).toByte(),
            ((address ushr 8) and 0xFF).toByte(),
            value.toByte(),
        ) + spec.keyword
        return buildFactoryCommand(0x42, payload)
    }

    fun buildFactoryCommand(action: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val inverted = action xor 0xFF
        val rotated = ((action shr 1) and 0x7F) or ((action shl 7) and 0x80)
        val actionCode = byteArrayOf(action.toByte(), inverted.toByte(), rotated.toByte())
        return buildControlCommand("||".encodeToByteArray(), spec.controlModel + actionCode + payload)
    }

    fun buildD4Command(command: Int, payload: ByteArray = byteArrayOf()): D4Frame {
        return D4Frame(
            psid = 0,
            ssid = 0,
            credit = 1,
            control = 0,
            payload = byteArrayOf(command.toByte()) + payload,
        )
    }

    fun encodeD4Frame(frame: D4Frame): ByteArray {
        val length = 6 + frame.payload.size
        return byteArrayOf(
            frame.psid.toByte(),
            frame.ssid.toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            frame.credit.toByte(),
            frame.control.toByte(),
        ) + frame.payload
    }

    fun decodeD4Frame(bytes: ByteArray): D4Frame {
        require(bytes.size >= 6) { "D4 frame too small" }
        val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        require(length == bytes.size) { "D4 frame length mismatch" }
        return D4Frame(
            psid = bytes[0].toInt() and 0xFF,
            ssid = bytes[1].toInt() and 0xFF,
            credit = bytes[4].toInt() and 0xFF,
            control = bytes[5].toInt() and 0xFF,
            payload = bytes.copyOfRange(6, bytes.size),
        )
    }

    fun chunkForChannel(channel: D4ChannelState, payload: ByteArray): List<D4Frame> {
        val maxPayload = maxOf(1, channel.mtu - 6)
        var offset = 0
        val frames = mutableListOf<D4Frame>()
        while (offset < payload.size) {
            val end = min(payload.size, offset + maxPayload)
            val chunk = payload.copyOfRange(offset, end)
            val advertisedCredit = minOf(channel.rxCreditsMax - channel.rxCredits, 0xFF)
            frames += D4Frame(
                psid = channel.psid,
                ssid = channel.ssid,
                credit = advertisedCredit,
                control = 0x02,
                payload = chunk,
            )
            channel.rxCredits += advertisedCredit
            offset = end
        }
        return frames
    }

    fun parseDeviceId(deviceId: String): Map<String, String> {
        return deviceId.split(';')
            .mapNotNull { entry ->
                val parts = entry.split(':', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    fun parseStatusResponse(payload: ByteArray, deviceId: String): PrinterStatusSnapshot {
        val expectedHeader = "@BDC ST2\r\n".encodeToByteArray()
        require(payload.size >= expectedHeader.size && payload.copyOfRange(0, expectedHeader.size).contentEquals(expectedHeader)) {
            "Unexpected status response"
        }
        val statusPayload = payload.copyOfRange(expectedHeader.size, payload.size)
        val parameters = parseStatusParameters(statusPayload)

        val state = statusCodeToText(parameters[0x01]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0x00)
        val error = errorCodeToText(parameters[0x02]?.firstOrNull()?.toInt()?.and(0xFF) ?: -1)
        val serial = parameters[0x40]?.decodeToString().orEmpty()
        val inkLevels = parseInkLevels(parameters[0x0F])
        val wasteCounters = emptyList<WasteCounterStatus>()
        val parsedId = parseDeviceId(deviceId)

        return PrinterStatusSnapshot(
            modelName = parsedId["MDL"] ?: spec.modelName,
            serialNumber = serial.ifBlank { parsedId["SN"] ?: "" },
            state = state,
            error = error,
            inkLevels = inkLevels,
            wasteCounters = wasteCounters,
        )
    }

    fun parseWasteCounter(rawBytes: ByteArray, max: Int, name: String): WasteCounterStatus {
        val value = rawBytes.foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (index * 8))
        }
        return WasteCounterStatus(name = name, rawValue = value, maxValue = max)
    }

    private fun parseStatusParameters(statusPayload: ByteArray): Map<Int, ByteArray> {
        val length = littleEndianShort(statusPayload, 0)
        require(length + 2 == statusPayload.size) { "Invalid status payload length" }
        val values = linkedMapOf<Int, ByteArray>()
        var index = 2
        while (index < statusPayload.size) {
            val header = statusPayload[index].toInt() and 0xFF
            val size = statusPayload[index + 1].toInt() and 0xFF
            val start = index + 2
            val end = start + size
            values[header] = statusPayload.copyOfRange(start, end)
            index = end
        }
        return values
    }

    private fun parseInkLevels(field: ByteArray?): List<InkLevel> {
        if (field == null || field.isEmpty()) return emptyList()
        val entrySize = field[0].toInt() and 0xFF
        val levels = mutableListOf<InkLevel>()
        var index = 1
        while (index + entrySize <= field.size) {
            val entry = field.copyOfRange(index, index + entrySize)
            val colorCode = entry.getOrNull(1)?.toInt()?.and(0xFF) ?: 0xFF
            val percentage = entry.getOrNull(2)?.toInt()?.and(0xFF) ?: -1
            levels += InkLevel(colorName(colorCode), percentage.coerceIn(0, 100))
            index += entrySize
        }
        return levels
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun statusCodeToText(code: Int): String = when (code) {
        0x01 -> "Self printing"
        0x02 -> "Busy"
        0x03 -> "Waiting"
        0x04 -> "Idle"
        0x05 -> "Pause"
        0x07 -> "Cleaning"
        else -> "Error"
    }

    private fun errorCodeToText(code: Int): String = when (code) {
        -1 -> "None"
        0x01 -> "Interface error"
        0x04 -> "Paper jam"
        0x05 -> "Ink out"
        0x10 -> "Service required"
        0x25 -> "Cover open"
        else -> "Error 0x${code.toString(16)}"
    }

    private fun colorName(code: Int): String = when (code) {
        0 -> "Black"
        1 -> "Cyan"
        2 -> "Magenta"
        3 -> "Yellow"
        4 -> "Light Cyan"
        5 -> "Light Magenta"
        7 -> "Gray"
        else -> "Color $code"
    }
}
