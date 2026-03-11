package com.tekware.aereset.data

data class InkLevel(
    val color: String,
    val percentage: Int,
)

data class WasteCounterStatus(
    val name: String,
    val rawValue: Int,
    val maxValue: Int,
) {
    val percentage: Double
        get() = if (maxValue <= 0) 0.0 else ((rawValue.toDouble() * 100.0) / maxValue.toDouble()).coerceIn(0.0, 999.0)
}

data class PrinterStatusSnapshot(
    val modelName: String,
    val serialNumber: String,
    val state: String,
    val error: String,
    val inkLevels: List<InkLevel>,
    val wasteCounters: List<WasteCounterStatus>,
)
