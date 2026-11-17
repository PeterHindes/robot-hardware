package com.robothardware.nanohub.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.robothardware.nanohub.net.HostLinkManager
import com.robothardware.nanohub.net.HostLinkState

@Composable
fun HostLinkTab(
  hostState: HostLinkState,
  hostLink: HostLinkManager
) {
  val context = LocalContext.current
  val camPermLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      hostLink.startWebRtcStream()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Host WebSocket Server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
          value = hostState.hostAddress,
          onValueChange = { hostLink.setHostAddress(it) },
          label = { Text("Host IP:Port (Tailscale)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = "Status: ${hostState.wsStatus}   |   Last msg: ${hostState.lastHostMessage}",
          style = MaterialTheme.typography.bodySmall,
          color = if (hostState.wsConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              if (hostState.wsConnected) hostLink.disconnectWebSocket() else hostLink.connectWebSocket()
            },
            modifier = Modifier.weight(1f)
          ) {
            Text(if (hostState.wsConnected) "Disconnect WS" else "Connect WS")
          }
        }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WebRTC Camera Feed (Host Compute)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
          text = "WebRTC State: ${hostState.webrtcState}",
          style = MaterialTheme.typography.bodySmall
        )
        Button(
          onClick = {
            if (hostState.webrtcStreaming) {
              hostLink.stopWebRtcStream()
            } else {
              val hasCam = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
              ) == PackageManager.PERMISSION_GRANTED
              if (hasCam) {
                hostLink.startWebRtcStream()
              } else {
                camPermLauncher.launch(Manifest.permission.CAMERA)
              }
            }
          },
          enabled = hostState.wsConnected,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(if (hostState.webrtcStreaming) "Stop Camera Stream" else "Start WebRTC Camera Stream")
        }
      }
    }
  }
}
