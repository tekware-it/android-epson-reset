package info.tekware.aereset.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime

object Escp2MaintenanceProtocol {
    private val exitPacketMode = byteArrayOf(
        0x00,
        0x00,
        0x00,
        0x1B,
        0x01,
    ) + "@EJL 1284.4\n@EJL     \n".encodeToByteArray()

    private val initializePrinter = byteArrayOf(0x1B, 0x40)
    private val enterRemoteMode = initializePrinter + initializePrinter + byteArrayOf(0x1B) + remoteCommand("(R", "\u0000REMOTE1".encodeToByteArray())
    private val exitRemoteMode = byteArrayOf(0x1B, 0x00, 0x00, 0x00)
    private val jobEnd = remoteCommand("JE", byteArrayOf(0x00))
    private val printNozzleCheck = remoteCommand("NC", byteArrayOf(0x00, 0x00))
    private val loadCommand = remoteCommand("LD", byteArrayOf())
    private val carriageReturn = byteArrayOf('\r'.code.toByte())
    private val formFeed = byteArrayOf(0x0C)

    fun buildNozzleCheckJob(type: Int = 0): ByteArray {
        val nozzleCheck = if (type == 1) {
            printNozzleCheck.copyOf().also { it[it.lastIndex] = 0x10 }
        } else {
            printNozzleCheck
        }
        return exitPacketMode + enterRemoteMode + nozzleCheck + buildJobTrailer(includeFormFeed = true)
    }

    fun buildHeadCleaningJob(
        groupIndex: Int,
        powerClean: Boolean = false,
        alternativeMode: Boolean = false,
    ): ByteArray {
        if (alternativeMode) {
            return initializePrinter + if (powerClean) {
                byteArrayOf(0x1B, 0x7C, 0x00, 0x06, 0x00, 0x19, 0x07, 0x84.toByte(), 0x7B, 0x42, 0x0A)
            } else {
                byteArrayOf(0x1B, 0x7C, 0x00, 0x06, 0x00, 0x19, 0x07, 0x84.toByte(), 0x7B, 0x42, 0x02)
            }
        }

        require(groupIndex in 0..5) { "Cleaning group index must be between 0 and 5" }
        var group = groupIndex
        if (powerClean) {
            group = group or 0x10
        }

        return exitPacketMode +
            enterRemoteMode +
            setTimer() +
            remoteCommand("CH", byteArrayOf(0x00, group.toByte())) +
            buildJobTrailer(includeFormFeed = false)
    }

    private fun buildJobTrailer(includeFormFeed: Boolean): ByteArray {
        val builder = ByteArray(0) +
            exitRemoteMode +
            initializePrinter +
            carriageReturn
        val flushBytes = if (includeFormFeed) formFeed else byteArrayOf()
        return builder +
            flushBytes +
            initializePrinter +
            enterRemoteMode.drop(initializePrinter.size) +
            loadCommand +
            exitRemoteMode +
            initializePrinter +
            enterRemoteMode.drop(initializePrinter.size) +
            loadCommand +
            jobEnd +
            exitRemoteMode
    }

    private fun setTimer(now: LocalDateTime = LocalDateTime.now()): ByteArray {
        val payload = ByteArray(8)
        payload[0] = 0x00
        ByteBuffer.wrap(payload, 1, 2).order(ByteOrder.BIG_ENDIAN).putShort(now.year.toShort())
        payload[3] = now.monthValue.toByte()
        payload[4] = now.dayOfMonth.toByte()
        payload[5] = now.hour.toByte()
        payload[6] = now.minute.toByte()
        payload[7] = now.second.toByte()
        return remoteCommand("TI", payload)
    }

    private fun remoteCommand(command: String, args: ByteArray): ByteArray {
        require(command.length == 2) { "Remote command must be 2 characters" }
        val length = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(args.size.toShort()).array()
        return command.encodeToByteArray() + length + args
    }
}
