package com.tekware.aereset.data

import android.content.Context
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class WasteCounterSpec(
    val name: String,
    val addresses: List<Int>,
    val max: Int,
)

data class PrinterSpec(
    val modelName: String,
    val controlModel: ByteArray,
    val keyword: ByteArray,
    val wasteCounters: List<WasteCounterSpec>,
    val resetMap: Map<Int, Int>,
)

object EpsonPrinterCatalog {
    const val EPSON_VENDOR_ID: Int = 0x04B8

    @Volatile
    private var cachedSpecs: List<PrinterSpec>? = null

    @Volatile
    var logger: ((String) -> Unit)? = null

    fun resolve(context: Context, deviceId: String): PrinterSpec? {
        val parsedId = parseDeviceId(deviceId)
        val mdl = parsedId["MDL"]?.trim().orEmpty()
        val specs = load(context)

        return specs.firstOrNull { it.modelName.equals(mdl, ignoreCase = true) }
            ?: specs.firstOrNull { mdl.contains(it.modelName.removeSuffix(" Series"), ignoreCase = true) }
            ?: specs.firstOrNull { deviceId.contains(it.modelName, ignoreCase = true) }
    }

    fun load(context: Context): List<PrinterSpec> {
        cachedSpecs?.let { return it }

        synchronized(this) {
            cachedSpecs?.let { return it }
            logger?.invoke("Loading Epson model database from assets/devices.xml")
            val parsed = context.assets.open("devices.xml").use(::parseDevicesXml)
            logger?.invoke("Loaded ${parsed.size} Epson printer specs with waste reset data")
            cachedSpecs = parsed
            return parsed
        }
    }

    private fun parseDevicesXml(input: InputStream): List<PrinterSpec> {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isIgnoringComments = true
            isNamespaceAware = false
        }.newDocumentBuilder()
        val document = builder.parse(input)
        val root = document.documentElement
        val devicesRoot = root.getElementsByTagName("devices").item(0) as? Element ?: return emptyList()

        val specByName = buildMap<String, Element> {
            val children = devicesRoot.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    put(node.nodeName, node as Element)
                }
            }
        }

        val printerNodes = root.getElementsByTagName("printer")
        val printers = mutableListOf<PrinterSpec>()
        for (index in 0 until printerNodes.length) {
            val printer = printerNodes.item(index) as? Element ?: continue
            if (!printer.getAttribute("brand").equals("epson", ignoreCase = true)) continue

            val modelName = printer.getAttribute("model").trim()
            if (modelName.isEmpty()) continue

            val specNames = printer.getAttribute("specs")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var controlModel = byteArrayOf()
            var keyword = byteArrayOf()
            val wasteCounters = mutableListOf<WasteCounterSpec>()
            val resetMap = linkedMapOf<Int, Int>()

            specNames.forEach { specName ->
                val specNode = specByName[specName] ?: return@forEach

                specNode.firstChildElement("service")?.let { service ->
                    service.firstChildElement("factory")?.let { factory ->
                        val bytes = parseHexBytes(factory.textContent)
                        if (bytes.isNotEmpty()) controlModel = bytes
                    }
                    service.firstChildElement("keyword")?.let { key ->
                        val bytes = parseHexBytes(key.textContent)
                        if (bytes.isNotEmpty()) keyword = bytes
                    }
                }

                specNode.firstChildElement("waste")?.let { waste ->
                    waste.firstChildElement("query")?.let { query ->
                        val counters = query.childElements("counter").mapIndexedNotNull { counterIndex, counter ->
                            val firstEntry = counter.firstChildElement("entry")
                            val rawAddresses = if (firstEntry != null) {
                                directText(firstEntry)
                            } else {
                                directText(counter)
                            }
                            val addresses = rawAddresses
                                .split(Regex("\\s+"))
                                .filter { it.isNotBlank() }
                                .map { parseNumeric(it, "waste address for model '$modelName' counter $counterIndex") }
                            val maxText = counter.firstChildElement("max")?.textContent?.trim()
                            val max = maxText?.let {
                                parseNumeric(it, "max for model '$modelName' counter $counterIndex")
                            } ?: 0
                            if (addresses.isEmpty() || max <= 0) {
                                null
                            } else {
                                WasteCounterSpec(
                                    name = defaultCounterName(counterIndex),
                                    addresses = addresses,
                                    max = max,
                                )
                            }
                        }
                        wasteCounters += counters
                    }

                    waste.firstChildElement("reset")?.let { reset ->
                        val values = parseHexInts(directText(reset))
                        values.chunked(2).forEach { pair ->
                            if (pair.size == 2) {
                                resetMap[pair[0]] = pair[1]
                            }
                        }
                    }
                }
            }

            if (controlModel.isNotEmpty() && keyword.isNotEmpty() && wasteCounters.isNotEmpty() && resetMap.isNotEmpty()) {
                printers += PrinterSpec(
                    modelName = modelName,
                    controlModel = controlModel,
                    keyword = keyword,
                    wasteCounters = wasteCounters.distinctBy { it.name to it.addresses },
                    resetMap = resetMap,
                )
            }
        }

        return printers.distinctBy { it.modelName }
    }

    private fun parseDeviceId(deviceId: String): Map<String, String> {
        return deviceId.split(';')
            .mapNotNull { part ->
                val pieces = part.split(':', limit = 2)
                if (pieces.size == 2) pieces[0].trim() to pieces[1].trim() else null
            }
            .toMap()
    }

    private fun parseHexBytes(text: String): ByteArray {
        return parseHexInts(text).map { it.toByte() }.toByteArray()
    }

    private fun parseHexInts(text: String): List<Int> {
        return HEX_TOKEN.findAll(text)
            .map { token -> parseNumeric(token.value, "hex token in XML block") }
            .toList()
    }

    private fun parseNumeric(text: String, context: String): Int {
        val value = text.trim()
        return runCatching {
            when {
                value.startsWith("-0x", ignoreCase = true) -> -value.drop(3).toInt(16)
                value.startsWith("+0x", ignoreCase = true) -> value.drop(3).toInt(16)
                value.startsWith("0x", ignoreCase = true) -> value.drop(2).toInt(16)
                else -> value.toInt()
            }
        }.getOrElse { cause ->
            val message = "Failed to parse numeric token '$value' in $context"
            logger?.invoke(message)
            throw IllegalArgumentException(message, cause)
        }
    }

    private fun directText(element: Element): String {
        val builder = StringBuilder()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                builder.append(node.nodeValue)
                builder.append(' ')
            }
        }
        return builder.toString()
    }

    private fun defaultCounterName(index: Int): String = when (index) {
        0 -> "Main pad"
        1 -> "Platen pad"
        2 -> "Borderless pad"
        else -> "Waste counter ${index + 1}"
    }

    private fun Element.firstChildElement(tagName: String): Element? {
        val children = childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                return node as Element
            }
        }
        return null
    }

    private fun Element.childElements(tagName: String): List<Element> {
        val items = mutableListOf<Element>()
        val children = childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                items += node as Element
            }
        }
        return items
    }

    private val HEX_TOKEN = Regex("0x[0-9A-Fa-f]+")
}
