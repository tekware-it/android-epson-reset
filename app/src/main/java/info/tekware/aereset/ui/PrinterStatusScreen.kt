package info.tekware.aereset.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.tekware.aereset.data.InkLevel
import info.tekware.aereset.data.PrinterStatusSnapshot
import info.tekware.aereset.data.WasteCounterStatus
import java.util.Locale

@Composable
fun PrinterStatusScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: (Set<String>, Int) -> Unit,
    onServiceClean: (String) -> Unit,
    onGenericClean: (Int, Boolean, Boolean) -> Unit,
    onNozzleCheck: (Int) -> Unit,
    onDismissActionSuccess: () -> Unit,
) {
    val context = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    var showServiceCleaningDialog by remember { mutableStateOf(false) }
    var showGenericCleaningDialog by remember { mutableStateOf(false) }
    var showNozzleCheckDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val availableCounters = uiState.status?.wasteCounters.orEmpty()
    val cleaningOptions = uiState.cleaningOptions
    var selectedCounters by remember(availableCounters) {
        mutableStateOf(availableCounters.map { it.name }.toSet())
    }
    var selectedCleaning by remember(cleaningOptions) {
        mutableStateOf(cleaningOptions.firstOrNull().orEmpty())
    }
    var selectedNozzleCheckType by remember { mutableStateOf(0) }
    var genericCleaningGroup by remember { mutableStateOf(0) }
    var genericPowerClean by remember { mutableStateOf(false) }
    var targetPercentageText by remember { mutableStateOf("0") }

    BackHandler(enabled = !showWarning && !showServiceCleaningDialog && !showGenericCleaningDialog && !showNozzleCheckDialog && !showAbout && !showExitDialog) {
        showExitDialog = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFD9D9D9))
            .padding(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            TitleBar(
                uiState = uiState,
                onAbout = { showAbout = true },
            )
            Spacer(Modifier.height(8.dp))

            Panel("Ink Levels") {
                InkLevelsPanel(uiState.status?.inkLevels.orEmpty())
            }

            Spacer(Modifier.height(8.dp))

            Panel("Waste Levels") {
                WasteLevelsPanel(
                    counters = uiState.status?.wasteCounters.orEmpty(),
                    connected = uiState.status != null,
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
                )
            }

            Spacer(Modifier.height(8.dp))

            Panel("Maintenance") {
                MaintenancePanel(
                    cleaningOptions = cleaningOptions,
                    supportsGenericMaintenanceJobs = uiState.supportsGenericMaintenanceJobs,
                    connected = uiState.status != null,
                    busy = uiState.isBusy,
                    onClean = {
                        if (cleaningOptions.isNotEmpty()) {
                            showServiceCleaningDialog = true
                        } else {
                            showGenericCleaningDialog = true
                        }
                    },
                    onNozzleCheck = { showNozzleCheckDialog = true },
                )
            }

            Spacer(Modifier.height(8.dp))

            ClassicButton(
                text = "Refresh",
                enabled = !uiState.isBusy,
                onClick = if (uiState.status == null) onConnect else onRefresh,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            if (!uiState.error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.error,
                    color = Color(0xFF8B0000),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            Panel(
                title = "Protocol Log",
                headerAction = {
                    if (uiState.logs.isNotEmpty()) {
                        CopyLogButton(uiState.logs)
                    }
                },
            ) {
                ProtocolLogPanel(uiState.logs)
            }
        }

        if (uiState.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                strokeWidth = 3.dp,
            )
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Reset waste counters") },
            confirmButton = {
                Button(
                    onClick = {
                        showWarning = false
                        onReset(selectedCounters, targetPercentageText.toIntOrNull()?.coerceIn(0, 100) ?: 0)
                    },
                    enabled = selectedCounters.isNotEmpty(),
                ) {
                    Text("Write Counters")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWarning = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resetting waste ink counters modifies internal printer firmware values. Use at your own risk.")
                    OutlinedTextField(
                        value = targetPercentageText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 3) {
                                targetPercentageText = input
                            }
                        },
                        label = { Text("Target percentage") },
                        singleLine = true,
                    )
                    availableCounters.forEach { counter ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(counter.name, color = Color.Black)
                            Checkbox(
                                checked = counter.name in selectedCounters,
                                onCheckedChange = { checked ->
                                    selectedCounters = if (checked) {
                                        selectedCounters + counter.name
                                    } else {
                                        selectedCounters - counter.name
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    if (showServiceCleaningDialog) {
        AlertDialog(
            onDismissRequest = { showServiceCleaningDialog = false },
            title = { Text("Head cleaning") },
            confirmButton = {
                Button(
                    onClick = {
                        showServiceCleaningDialog = false
                        onServiceClean(selectedCleaning)
                    },
                    enabled = selectedCleaning.isNotBlank(),
                ) {
                    Text("Start Cleaning")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showServiceCleaningDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Head cleaning consumes ink. Choose the cleaning level supported by this printer.")
                    cleaningOptions.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(option, color = Color.Black)
                            Checkbox(
                                checked = option == selectedCleaning,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedCleaning = option
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    if (showGenericCleaningDialog) {
        val cleaningModes = listOf(
            "Clean all nozzles",
            "Clean the black ink nozzle",
            "Clean the color ink nozzles",
            "Head cleaning (alternative mode)",
        )
        AlertDialog(
            onDismissRequest = { showGenericCleaningDialog = false },
            title = { Text("Head cleaning") },
            confirmButton = {
                Button(
                    onClick = {
                        showGenericCleaningDialog = false
                        onGenericClean(genericCleaningGroup, genericPowerClean, genericCleaningGroup == 3)
                    },
                ) {
                    Text("Start Cleaning")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGenericCleaningDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This mode sends a generic Epson ESC/P2 maintenance job. Support may vary by model.")
                    cleaningModes.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, color = Color.Black)
                            Checkbox(
                                checked = genericCleaningGroup == index,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        genericCleaningGroup = index
                                    }
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Power Clean", color = Color.Black)
                        Checkbox(
                            checked = genericPowerClean,
                            onCheckedChange = { genericPowerClean = it },
                        )
                    }
                }
            },
        )
    }

    if (showNozzleCheckDialog) {
        AlertDialog(
            onDismissRequest = { showNozzleCheckDialog = false },
            title = { Text("Nozzle check") },
            confirmButton = {
                Button(
                    onClick = {
                        showNozzleCheckDialog = false
                        onNozzleCheck(selectedNozzleCheckType)
                    },
                ) {
                    Text("Print Test")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNozzleCheckDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Standard nozzle test", "Alternative nozzle test").forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(label, color = Color.Black)
                            Checkbox(
                                checked = selectedNozzleCheckType == index,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedNozzleCheckType = index
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Android Epson Reset")
                    Text("GitHub: tekware-it/android-epson-reset")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAbout = false
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/tekware-it/android-epson-reset"),
                            ),
                        )
                    },
                ) {
                    Text("Open Repo")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAbout = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit app") },
            text = { Text("Do you want to exit and close Android Epson Reset?") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    },
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    uiState.actionSuccessMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissActionSuccess,
            title = { Text("Operation completed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message)
                    Text("Donate now to support development of this and my other free apps, thank you!")
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/tekware-it/android-epson-reset"),
                                ),
                            )
                        },
                    ) {
                        Text("Open GitHub Repo")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismissActionSuccess) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun ProtocolLogPanel(logs: List<String>) {
    if (logs.isEmpty()) {
        Text("No log entries yet.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logs.takeLast(80).asReversed()) { line ->
            Text(line, color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CopyLogButton(logs: List<String>) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val allLogs = logs.joinToString(separator = "\n")

    ClassicButton(
        text = "Copy",
        enabled = true,
        onClick = {
            clipboard.setText(AnnotatedString(allLogs))
            Toast.makeText(context, "Protocol log copied", Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
private fun TitleBar(
    uiState: MainUiState,
    onAbout: () -> Unit,
) {
    val status = uiState.status
    val subtitle = buildString {
        if (status != null) {
            append(status.modelName)
            append("  |  ")
            append(status.state)
            if (status.error != "None" && status.error.isNotBlank()) {
                append(" / ")
                append(status.error)
            }
        } else if (uiState.isBusy) {
            append("Connecting...")
        } else {
            append("Printer not connected")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.widthIn(max = 240.dp)) {
            Text("Android Epson Reset", fontWeight = FontWeight.Bold, color = Color.Black)
            Text(subtitle, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
        }
        ClassicButton(
            text = "About",
            enabled = true,
            onClick = onAbout,
        )
    }
}

@Composable
private fun Panel(
    title: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF8C8C8C))
            .background(Color(0xFFE6E6E6))
            .padding(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.Black)
            headerAction?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun InkLevelsPanel(levels: List<InkLevel>) {
    val displayLevels = if (levels.isEmpty()) {
        listOf(
            InkLevel("Black", 0),
            InkLevel("Yellow", 0),
            InkLevel("Magenta", 0),
            InkLevel("Cyan", 0),
        )
    } else {
        levels.sortedBy { inkSortOrder(it.color) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val itemsPerRow = displayLevels.size.coerceAtLeast(1)
        val compact = displayLevels.size >= 7
        val spacing = if (compact) 4.dp else 8.dp
        val cellWidth = (maxWidth - spacing * (itemsPerRow - 1)) / itemsPerRow
        val gaugeHeight = if (compact) 62.dp else 74.dp
        val gaugeFillWidth = if (compact) 0.86f else 0.72f
        val labelFontSize = if (compact) 10.sp else TextUnit.Unspecified
        val valueFontSize = if (compact) 10.sp else TextUnit.Unspecified

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            displayLevels.forEach { level ->
                InkLevelCell(
                    level = level,
                    modifier = Modifier.width(cellWidth),
                    gaugeHeight = gaugeHeight,
                    gaugeFillWidth = gaugeFillWidth,
                    labelFontSize = labelFontSize,
                    valueFontSize = valueFontSize,
                )
            }
        }
    }
}

@Composable
private fun InkLevelCell(
    level: InkLevel,
    modifier: Modifier = Modifier,
    gaugeHeight: androidx.compose.ui.unit.Dp = 74.dp,
    gaugeFillWidth: Float = 0.72f,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    valueFontSize: TextUnit = TextUnit.Unspecified,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = inkShortLabel(level.color),
            color = Color.Black,
            style = MaterialTheme.typography.bodySmall.withFontSize(labelFontSize),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        VerticalInkGauge(
            percentage = level.percentage,
            fillColor = inkColor(level.color),
            gaugeHeight = gaugeHeight,
            modifier = Modifier.fillMaxWidth(gaugeFillWidth),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${level.percentage}%",
            color = Color.Black,
            style = MaterialTheme.typography.bodySmall.withFontSize(valueFontSize),
            maxLines = 1,
        )
    }
}

@Composable
private fun VerticalInkGauge(
    percentage: Int,
    fillColor: Color,
    gaugeHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(gaugeHeight)
            .border(1.dp, Color(0xFF777777))
            .background(Color(0xFFC9C9C9)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val fillFraction = (percentage.coerceIn(0, 100) / 100f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((gaugeHeight - 2.dp) * fillFraction)
                .background(fillColor),
        )
    }
}

@Composable
private fun WasteLevelsPanel(
    counters: List<WasteCounterStatus>,
    connected: Boolean,
    busy: Boolean,
    onReset: () -> Unit,
) {
    val displayCounters = if (counters.isEmpty()) {
        listOf(
            WasteCounterStatus("Waste ink counter 0", 0, 100),
            WasteCounterStatus("Waste ink counter 1", 0, 100),
        )
    } else {
        counters.mapIndexed { index, counter ->
            val normalizedName = counter.name
                .takeIf { it.isNotBlank() }
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                ?: "Waste ink counter $index"
            counter.copy(name = normalizedName)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        displayCounters.forEach { counter ->
            WasteCounterRow(counter)
        }

        Spacer(Modifier.height(4.dp))
        ClassicButton(
            text = "Write Counters",
            enabled = connected && !busy,
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MaintenancePanel(
    cleaningOptions: List<String>,
    supportsGenericMaintenanceJobs: Boolean,
    connected: Boolean,
    busy: Boolean,
    onClean: () -> Unit,
    onNozzleCheck: () -> Unit,
) {
    val enabled = connected && !busy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClassicButton(
            text = "Head Cleaning",
            enabled = enabled && (cleaningOptions.isNotEmpty() || supportsGenericMaintenanceJobs),
            onClick = onClean,
            modifier = Modifier.fillMaxWidth(),
        )
        ClassicButton(
            text = "Nozzle Check",
            enabled = enabled && supportsGenericMaintenanceJobs,
            onClick = onNozzleCheck,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WasteCounterRow(counter: WasteCounterStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = counter.name,
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = counter.percentage.formatPercent(),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalGauge(counter.percentage)
    }
}

@Composable
private fun HorizontalGauge(percentage: Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .border(1.dp, Color(0xFF999999))
            .background(Color(0xFFD3D3D3)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((percentage / 100.0).coerceIn(0.0, 1.0).toFloat())
                .height(20.dp)
                .background(Color(0xFF2C7DC9)),
        )
    }
}

@Composable
private fun ClassicButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(0.dp),
        modifier = modifier
            .height(34.dp),
    ) {
        Text(text, color = Color.Black)
    }
}

private fun inkSortOrder(color: String): Int = when (color.lowercase(Locale.ROOT)) {
    "black" -> 0
    "light black" -> 1
    "gray", "grey" -> 2
    "light gray", "light grey" -> 3
    "cyan" -> 4
    "light cyan" -> 5
    "blue" -> 6
    "magenta" -> 7
    "light magenta" -> 8
    "red" -> 9
    "yellow" -> 10
    "dark yellow" -> 11
    "orange" -> 12
    "gloss optimizer" -> 13
    else -> 99
}

private fun inkColor(color: String): Color = when (color.lowercase(Locale.ROOT)) {
    "black" -> Color.Black
    "yellow" -> Color.Yellow
    "magenta" -> Color.Magenta
    "cyan" -> Color.Cyan
    "light cyan" -> Color(0xFF6FE8FF)
    "light magenta" -> Color(0xFFFF89E9)
    else -> Color.DarkGray
}

private fun inkShortLabel(color: String): String = when (color.lowercase(Locale.ROOT)) {
    "black" -> "BK"
    "cyan" -> "CY"
    "magenta" -> "MG"
    "yellow" -> "YE"
    "light cyan" -> "LC"
    "light magenta" -> "LM"
    "dark yellow" -> "DY"
    "gray", "grey" -> "GY"
    "light black" -> "LK"
    "red" -> "RD"
    "blue" -> "BL"
    "gloss optimizer" -> "GO"
    "light gray", "light grey" -> "LG"
    "orange" -> "OR"
    else -> color.take(2).uppercase(Locale.ROOT)
}

private fun TextStyle.withFontSize(fontSize: TextUnit): TextStyle {
    return if (fontSize == TextUnit.Unspecified) this else copy(fontSize = fontSize)
}

private fun Double.formatPercent(): String = String.format(Locale.US, "%.2f%%", this)
