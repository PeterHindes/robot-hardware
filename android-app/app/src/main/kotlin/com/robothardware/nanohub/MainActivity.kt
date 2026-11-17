package com.robothardware.nanohub

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.robothardware.nanohub.net.HostLinkManager
import com.robothardware.nanohub.ui.main.NanoHubAppScreen
import com.robothardware.nanohub.ui.theme.NanoHubTheme
import com.robothardware.nanohub.usb.NanoHubController

class MainActivity : ComponentActivity() {
  private lateinit var controller: NanoHubController
  private lateinit var hostLink: HostLinkManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    controller = NanoHubController(applicationContext)
    hostLink = HostLinkManager(applicationContext, controller)

    enableEdgeToEdge()
    setContent {
      NanoHubTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          NanoHubAppScreen(controller = controller, hostLink = hostLink)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // USB_DEVICE_ATTACHED is already handled by NanoHubController's BroadcastReceiver
  }

  override fun onDestroy() {
    super.onDestroy()
    if (::hostLink.isInitialized) {
      hostLink.destroy()
    }
    if (::controller.isInitialized) {
      controller.destroy()
    }
  }
}
