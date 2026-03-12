package info.tekware.aereset.service

import android.content.Context
import android.hardware.usb.UsbDevice
import info.tekware.aereset.data.EpsonPrinterCatalog
import info.tekware.aereset.data.PrinterSpec
import info.tekware.aereset.data.PrinterStatusSnapshot
import info.tekware.aereset.data.WasteCounterStatus
import info.tekware.aereset.protocol.EpsonProtocol
import info.tekware.aereset.usb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrinterService(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    data class ConnectionState(
        val session: UsbTransport.Session,
        val spec: PrinterSpec,
        val protocol: EpsonProtocol,
        val deviceId: String,
        val backend: EpsonProtocol.ControlBackend,
        val controlChannel: EpsonProtocol.D4ChannelState? = null,
    )

    private val transport = UsbTransport(appContext)

    init {
        EpsonPrinterCatalog.logger = log
    }

    fun detectPrinters(): List<UsbDevice> = transport.findEpsonDevices()

    suspend fun connectPrinter(device: UsbDevice): ConnectionState = withContext(Dispatchers.IO) {
        log("USB device detected: vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)} name=${device.productName ?: device.deviceName}")
        require(transport.ensurePermission(device)) { "USB permission denied" }
        transport.listInterfaces(device).forEach { info ->
            log(
                "USB interface ${info.id}: class=0x${info.interfaceClass.toString(16)} " +
                    "subclass=0x${info.interfaceSubclass.toString(16)} protocol=0x${info.interfaceProtocol.toString(16)}",
            )
            info.endpointDescriptions.forEach { endpoint ->
                log("  $endpoint")
            }
        }
        val candidates = transport.candidateInterfaces(device)
        require(candidates.isNotEmpty()) { "No USB interfaces with bulk IN/OUT endpoints found" }

        var lastError: Throwable? = null
        candidates.forEachIndexed { index, usbInterface ->
            log("Trying USB interface ${usbInterface.id} (${index + 1}/${candidates.size})")

            val firstAttempt = runCatching { connectOnce(device, usbInterface.id, performSoftReset = true) }
            firstAttempt.getOrNull()?.let {
                transport.rememberPreferredInterface(device, usbInterface.id)
                return@withContext it
            }

            val firstError = firstAttempt.exceptionOrNull()
            if (firstError?.message?.contains("Printer rejected IEEE 1284.4 enter sequence") == true) {
                log("1284.4 enter was rejected after Android soft reset on interface ${usbInterface.id}, retrying without soft reset")
                val secondAttempt = runCatching { connectOnce(device, usbInterface.id, performSoftReset = false) }
                secondAttempt.getOrNull()?.let {
                    transport.rememberPreferredInterface(device, usbInterface.id)
                    return@withContext it
                }

                val secondError = secondAttempt.exceptionOrNull()
                lastError = secondError
                log("D4 failed on interface ${usbInterface.id}: ${lastError?.message}")
                return@forEachIndexed
            }

            lastError = firstError
            log("Interface ${usbInterface.id} failed before D4 fallback: ${lastError?.message}")
        }

        throw lastError ?: error("Unable to connect to printer")
    }

    suspend fun readPrinterStatus(connection: ConnectionState): PrinterStatusSnapshot = withContext(Dispatchers.IO) {
        log("Requesting printer status")
        val statusResponse = sendControlPayload(connection, connection.protocol.buildStatusRequest())
        val baseSnapshot = connection.protocol.parseStatusResponse(statusResponse, connection.deviceId)
        log("Status response parsed: state=${baseSnapshot.state} error=${baseSnapshot.error} inks=${baseSnapshot.inkLevels.size}")
        val wasteCounters = connection.spec.wasteCounters.map { counter ->
            val bytes = counter.addresses.map { address ->
                val response = sendControlPayload(connection, connection.protocol.buildReadEepromCommand(address))
                parseEepromReadByte(response)
            }.toByteArray()
            log(
                "Waste counter '${counter.name}' addresses=${
                    counter.addresses.joinToString(prefix = "[", postfix = "]") { "0x%X".format(it) }
                } bytes=${bytes.joinToString(" ") { "%02X".format(it) }}",
            )
            connection.protocol.parseWasteCounter(bytes, counter.max, counter.name)
        }
        baseSnapshot.copy(wasteCounters = wasteCounters)
    }

    suspend fun resetWasteCounters(connection: ConnectionState): List<WasteCounterStatus> = withContext(Dispatchers.IO) {
        log("Resetting waste counters with ${connection.spec.resetMap.size} EEPROM writes")
        connection.protocol.buildResetCommand().forEach { command ->
            log("Reset command: ${command.joinToString(" ") { "%02X".format(it) }}")
            sendControlPayload(connection, command)
        }
        readPrinterStatus(connection).wasteCounters
    }

    fun close(connection: ConnectionState) {
        runCatching {
            val channel = connection.controlChannel ?: return@runCatching
            sendD4Command(
                connection.session,
                connection.protocol,
                0x02,
                byteArrayOf(channel.psid.toByte(), channel.ssid.toByte()),
            )
        }
        connection.session.close()
    }

    private suspend fun connectOnce(device: UsbDevice, interfaceId: Int, performSoftReset: Boolean): ConnectionState {
        val usbInterface = transport.candidateInterfaces(device).firstOrNull { it.id == interfaceId }
            ?: error("USB interface $interfaceId not found")
        val session = transport.openOnInterface(device, usbInterface, permissionAlreadyGranted = true)
        try {
            log("USB session opened, claiming interface ${session.usbInterface.id}")
            if (performSoftReset) {
                transport.softReset(session)
                log("USB soft reset sent")
            } else {
                log("Skipping Android USB soft reset for this attempt")
            }

            val deviceId = transport.getDeviceId(session)
            log("IEEE1284 device ID: $deviceId")
            val spec = EpsonPrinterCatalog.resolve(appContext, deviceId)
                ?: error("Unsupported Epson model: $deviceId")
            log("Resolved model spec: ${spec.modelName}, counters=${spec.wasteCounters.size}, resetEntries=${spec.resetMap.size}")
            val protocol = EpsonProtocol(spec)

            val enterReply = enterDot4(session, protocol)
            if (enterReply.contentEquals(byteArrayOf(0x15))) {
                error("Printer rejected IEEE 1284.4 enter sequence with NAK (0x15)")
            }
            log("Entered IEEE 1284.4 mode")

            sendD4Command(session, protocol, 0x00, protocol.buildInitCommandPayload())
            log("D4 Init completed")
            val socketReply = sendD4Command(session, protocol, 0x09, protocol.buildGetSocketIdPayload("EPSON-CTRL"))
            val ssid = socketReply.firstOrNull()?.toInt()?.and(0xFF) ?: error("Missing EPSON-CTRL SSID")
            log("EPSON-CTRL socket ID = $ssid")

            val openReply = sendD4Command(session, protocol, 0x01, protocol.buildOpenChannelPayload(ssid))
            require(openReply.size >= 8) { "Invalid open channel response" }
            val psid = openReply[0].toInt() and 0xFF
            val mtu = ((openReply[2].toInt() and 0xFF) shl 8) or (openReply[3].toInt() and 0xFF)
            val credit = ((openReply[6].toInt() and 0xFF) shl 8) or (openReply[7].toInt() and 0xFF)
            val channel = EpsonProtocol.D4ChannelState(psid = psid, ssid = ssid, mtu = mtu, txCredits = credit)
            log("Channel opened: psid=$psid ssid=$ssid mtu=$mtu credit=$credit")

            sendD4Command(session, protocol, 0x03, protocol.buildCreditPayload(psid, ssid, channel.rxCreditsMax))
            log("Initial D4 credit granted=${channel.rxCreditsMax}")

            return ConnectionState(
                session = session,
                spec = spec,
                protocol = protocol,
                deviceId = deviceId,
                backend = EpsonProtocol.ControlBackend.D4,
                controlChannel = channel,
            )
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }

    private fun enterDot4(session: UsbTransport.Session, protocol: EpsonProtocol): ByteArray {
        transport.drain(session)
        log("Transport drained before entering IEEE 1284.4 mode")
        transport.bulkWrite(session, protocol.buildEnterDot4Packet())
        val enterChunks = transport.captureIncoming(session, windowMs = 1000, idleTimeoutMs = 120)
        if (enterChunks.isEmpty()) {
            log("1284.4 enter reply: <no data>")
        } else {
            enterChunks.forEachIndexed { index, chunk ->
                log(
                    "1284.4 RX chunk[$index] +${chunk.offsetMs}ms: ${
                        chunk.bytes.joinToString(" ") { "%02X".format(it) }
                    }",
                )
            }
        }
        val buffered = session.readBuffer.size
        val enterReply = if (buffered >= 8) {
            transport.bulkRead(session, 8, timeoutMs = 1)
        } else {
            session.readBuffer.take(buffered).toByteArray().also {
                repeat(buffered) { session.readBuffer.removeAt(0) }
            }
        }
        log("1284.4 enter aggregate: ${enterReply.joinToString(" ") { "%02X".format(it) }}")
        if (enterReply.size < 8) {
            log("1284.4 enter reply shorter than expected (${enterReply.size}/8)")
        }
        return enterReply
    }

    private fun sendControlPayload(connection: ConnectionState, payload: ByteArray): ByteArray {
        ensureChannelCredit(connection)
        log("CTRL TX: ${payload.joinToString(" ") { "%02X".format(it) }}")
        val channel = requireNotNull(connection.controlChannel) { "D4 control channel missing" }
        val frames = connection.protocol.chunkForChannel(channel, payload)
        frames.forEach { frame ->
            log("D4 TX frame psid=${frame.psid} ssid=${frame.ssid} credit=${frame.credit} control=${frame.control} len=${frame.payload.size}")
            transport.bulkWrite(connection.session, connection.protocol.encodeD4Frame(frame))
            channel.txCredits -= 1
        }
        val reply = readD4Reply(connection.session, connection.protocol)
        channel.txCredits += reply.credit
        channel.rxCredits = (channel.rxCredits - 1).coerceAtLeast(0)
        log("CTRL RX: ${reply.payload.joinToString(" ") { "%02X".format(it) }}")
        return reply.payload
    }

    private fun sendD4Command(
        session: UsbTransport.Session,
        protocol: EpsonProtocol,
        command: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val frame = protocol.buildD4Command(command, payload)
        log("D4 CMD TX command=0x${command.toString(16)} payload=${payload.joinToString(" ") { "%02X".format(it) }}")
        transport.bulkWrite(session, protocol.encodeD4Frame(frame))
        val reply = readD4Reply(session, protocol)
        log("D4 CMD RX command=0x${command.toString(16)} payload=${reply.payload.joinToString(" ") { "%02X".format(it) }}")
        require(reply.payload.isNotEmpty()) { "Empty D4 command response" }
        require((reply.payload[0].toInt() and 0xFF) == (command or 0x80)) { "Unexpected D4 response for 0x${command.toString(16)}" }
        require((reply.payload[1].toInt() and 0xFF) == 0x00) { "D4 command returned error" }
        return reply.payload.copyOfRange(2, reply.payload.size)
    }

    private fun ensureChannelCredit(connection: ConnectionState) {
        val channel = requireNotNull(connection.controlChannel) { "D4 control channel missing" }
        if (channel.txCredits > 0) {
            return
        }

        log("TX credit exhausted, requesting more D4 credit")
        val reply = sendD4Command(
            connection.session,
            connection.protocol,
            0x04,
            connection.protocol.buildCreditPayload(channel.psid, channel.ssid, 0xFFFF),
        )
        require(reply.size >= 4) { "Invalid credit request response" }
        val granted = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        channel.txCredits += granted
        log("D4 credit request granted=$granted")
    }

    private fun readD4Reply(session: UsbTransport.Session, protocol: EpsonProtocol): EpsonProtocol.D4Frame {
        val header = transport.bulkRead(session, 6, timeoutMs = 8000)
        require(header.size == 6) { "Incomplete D4 header" }
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        log("D4 RX header: ${header.joinToString(" ") { "%02X".format(it) }}")
        val payload = if (length > 6) transport.bulkRead(session, length - 6, timeoutMs = 8000) else byteArrayOf()
        return protocol.decodeD4Frame(header + payload)
    }

    private fun parseEepromReadByte(payload: ByteArray): Byte {
        val expected = "@BDC PS\r\n".encodeToByteArray()
        require(payload.size >= 18 && payload.copyOfRange(0, expected.size).contentEquals(expected)) { "Unexpected EEPROM response" }
        val hex = payload.copyOfRange(16, 18).decodeToString()
        return hex.toInt(16).toByte()
    }
}
