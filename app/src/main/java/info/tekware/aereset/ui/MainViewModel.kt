package info.tekware.aereset.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import info.tekware.aereset.data.PrinterStatusSnapshot
import info.tekware.aereset.service.PrinterService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val connectedDeviceName: String? = null,
    val status: PrinterStatusSnapshot? = null,
    val cleaningOptions: List<String> = emptyList(),
    val supportsGenericMaintenanceJobs: Boolean = false,
    val isBusy: Boolean = false,
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val actionSuccessMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val printerService = PrinterService(application, ::appendLog)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var connection: PrinterService.ConnectionState? = null

    fun discoverAndConnect() {
        viewModelScope.launch {
            runJob("Scanning for Epson USB printers") {
                val device = printerService.detectPrinters().firstOrNull() ?: error("No Epson printer detected over USB OTG")
                connect(device)
            }
        }
    }

    fun connect(device: UsbDevice) {
        viewModelScope.launch {
            runJob("Connecting to ${device.productName ?: device.deviceName}") {
                connection?.let(printerService::close)
                connection = printerService.connectPrinter(device)
                appendLog("Connected to ${connection?.deviceId}")
                refreshStatus()
            }
        }
    }

    fun refreshStatus() {
        val current = connection ?: run {
            appendError("Printer is not connected")
            return
        }
        viewModelScope.launch {
            runJob("Reading printer status") {
                val status = printerService.readPrinterStatus(current)
                _uiState.value = _uiState.value.copy(
                    connectedDeviceName = current.deviceId,
                    status = status,
                    cleaningOptions = current.spec.cleaningOptions.map { it.name },
                    supportsGenericMaintenanceJobs = true,
                    error = null,
                )
                appendLog("Status read: ${status.state}, ${status.error}")
            }
        }
    }

    fun resetWasteCounters(selectedCounterNames: Set<String>, targetPercentage: Int) {
        val current = connection ?: run {
            appendError("Printer is not connected")
            return
        }
        viewModelScope.launch {
            runJob("Resetting waste counters") {
                val counters = printerService.writeWasteCounters(current, selectedCounterNames, targetPercentage)
                appendLog("Waste counters written: ${counters.joinToString { "${it.name}=${it.percentage}%" }}")
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "Waste ink counters have been updated. Restart the printer if required by the model.",
                )
                refreshStatus()
            }
        }
    }

    fun runHeadCleaning(cleaningName: String) {
        val current = connection ?: run {
            appendError("Printer is not connected")
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                printerService.runHeadCleaning(current, cleaningName)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "Head cleaning '${cleaningName}' command has been sent. Wait for the printer to start the routine, then refresh status manually.",
                )
            }
        }
    }

    fun runGenericHeadCleaning(
        groupIndex: Int,
        powerClean: Boolean,
        alternativeMode: Boolean,
    ) {
        val current = connection ?: run {
            appendError("Printer is not connected")
            return
        }
        viewModelScope.launch {
            runJob("Starting head cleaning") {
                printerService.runPrintJobHeadCleaning(current, groupIndex, powerClean, alternativeMode)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "Head cleaning job has been sent. Wait for the printer to start the routine, then refresh status manually.",
                )
            }
        }
    }

    fun printNozzleCheck(type: Int) {
        val current = connection ?: run {
            appendError("Printer is not connected")
            return
        }
        viewModelScope.launch {
            runJob("Printing nozzle check") {
                printerService.printNozzleCheck(current, type)
                _uiState.value = _uiState.value.copy(
                    actionSuccessMessage = "Nozzle check print job has been sent. Wait for the page to print.",
                )
            }
        }
    }

    fun dismissActionSuccess() {
        _uiState.value = _uiState.value.copy(actionSuccessMessage = null)
    }

    override fun onCleared() {
        connection?.let(printerService::close)
        super.onCleared()
    }

    private suspend fun runJob(message: String, block: suspend () -> Unit) {
        _uiState.value = _uiState.value.copy(isBusy = true, error = null)
        appendLog(message)
        runCatching { block() }
            .onFailure { appendError(it.message ?: it.javaClass.simpleName) }
        _uiState.value = _uiState.value.copy(isBusy = false)
    }

    private fun appendLog(line: String) {
        _uiState.value = _uiState.value.copy(logs = (_uiState.value.logs + line).takeLast(200))
    }

    private fun appendError(message: String) {
        appendLog("Error: $message")
        _uiState.value = _uiState.value.copy(error = message)
    }
}
