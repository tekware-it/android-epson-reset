package com.tekware.aereset.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tekware.aereset.data.InkLevel
import com.tekware.aereset.data.PrinterStatusSnapshot
import com.tekware.aereset.data.WasteCounterStatus
import java.util.Locale

@Composable
fun PrinterStatusScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
) {
    var showWarning by remember { mutableStateOf(false) }

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
            TitleBar(uiState)
            Spacer(Modifier.height(8.dp))

            Panel("Ink Levels") {
                InkLevelsPanel(uiState.status?.inkLevels.orEmpty())
            }

            Spacer(Modifier.height(8.dp))

            Panel("Waste Levels") {
                WasteLevelsPanel(
                    counters = uiState.status?.wasteCounters.orEmpty(),
                    busy = uiState.isBusy,
                    onReset = { showWarning = true },
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
            Panel("Protocol Log") {
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
            text = {
                Text("Resetting waste ink counters modifies internal printer firmware values. Use at your own risk.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWarning = false
                        onReset()
                    },
                ) {
                    Text("Reset All")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWarning = false }) {
                    Text("Cancel")
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
private fun TitleBar(uiState: MainUiState) {
    val status = uiState.status
    val title = status?.modelName ?: "EPS..."
    val subtitle = buildString {
        if (status != null) {
            append(status.state)
            if (status.error != "None") {
                append(" / ")
                append(status.error)
            }
        } else if (uiState.isBusy) {
            append("Connecting...")
        } else {
            append("Printer not connected")
        }
    }

    Column {
        Text(title, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(subtitle, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF8C8C8C))
            .background(Color(0xFFE6E6E6))
            .padding(6.dp),
    ) {
        Text(title, color = Color.Black)
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

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        displayLevels.forEach { level ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                VerticalInkGauge(
                    percentage = level.percentage,
                    fillColor = inkColor(level.color),
                )
                Spacer(Modifier.height(4.dp))
                Text("${level.percentage}%", color = Color.Black, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VerticalInkGauge(percentage: Int, fillColor: Color) {
    Box(
        modifier = Modifier
            .width(26.dp)
            .height(74.dp)
            .border(1.dp, Color(0xFF777777))
            .background(Color(0xFFC9C9C9)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val fillFraction = (percentage.coerceIn(0, 100) / 100f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp * fillFraction)
                .background(fillColor),
        )
    }
}

@Composable
private fun WasteLevelsPanel(
    counters: List<WasteCounterStatus>,
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
            counter.copy(name = "Waste ink counter $index")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        displayCounters.forEach { counter ->
            Column {
                Text(
                    text = "${counter.name}    ${counter.percentage.formatPercent()}",
                    color = Color.Black,
                )
                Spacer(Modifier.height(2.dp))
                HorizontalGauge(counter.percentage)
            }
        }

        Spacer(Modifier.height(4.dp))
        ClassicButton(
            text = "Reset All",
            enabled = !busy,
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        )
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
    "yellow" -> 1
    "magenta" -> 2
    "cyan" -> 3
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

private fun Double.formatPercent(): String = String.format(Locale.US, "%.2f%%", this)
