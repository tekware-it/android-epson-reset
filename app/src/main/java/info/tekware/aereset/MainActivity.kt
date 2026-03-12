package info.tekware.aereset

import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.tekware.aereset.ui.MainViewModel
import info.tekware.aereset.ui.PrinterStatusScreen
import info.tekware.aereset.ui.theme.AeResetTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
        }

        usbDevice?.let { device ->
            viewModel.connect(device)
        }

        setContent {
            AeResetTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PrinterStatusScreen(
                    uiState = uiState,
                    onConnect = viewModel::discoverAndConnect,
                    onRefresh = viewModel::refreshStatus,
                    onReset = viewModel::resetWasteCounters,
                    onDismissResetSuccess = viewModel::dismissResetSuccess,
                )
            }
        }
    }
}
