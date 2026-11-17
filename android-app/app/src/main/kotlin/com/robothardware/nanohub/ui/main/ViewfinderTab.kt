package com.robothardware.nanohub.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.robothardware.nanohub.net.HostLinkManager
import com.robothardware.nanohub.net.HostLinkState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun ViewfinderTab(
  hostState: HostLinkState,
  hostLink: HostLinkManager
) {
  val context = LocalContext.current
  val eglContext = remember { hostLink.getOrCreateEglContext() }
  val renderer = remember {
    SurfaceViewRenderer(context).apply {
      init(eglContext, null)
      setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
      setMirror(false)
      setEnableHardwareScaler(true)
    }
  }

  DisposableEffect(renderer) {
    hostLink.attachPreview(renderer)
    onDispose {
      hostLink.detachPreview(renderer)
      try {
        renderer.release()
      } catch (_: Exception) {
      }
    }
  }

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
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .clip(RoundedCornerShape(12.dp))
        .background(Color.Black),
      contentAlignment = Alignment.Center
    ) {
      AndroidView(
        factory = { renderer },
        modifier = Modifier.fillMaxSize()
      )

      // Aiming crosshair reticle
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = 28.dp.toPx()
        val stroke = 2.dp.toPx()
        val color = Color(0x9900E676)
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = stroke)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = stroke)
        drawCircle(color, radius = 40.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
      }

      if (!hostState.webrtcStreaming) {
        Column(
          modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xAA000000))
            .padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "Camera Viewfinder Idle",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = if (hostState.wsConnected) {
              "Tap Start Camera to stream & preview"
            } else {
              "Connect to Host (${hostState.hostAddress}) or trigger via webhook"
            },
            color = Color(0xFFCCCCCC),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (!hostState.wsConnected) {
        OutlinedButton(
          onClick = { hostLink.connectWebSocket() },
          modifier = Modifier.weight(1f)
        ) {
          Text("Connect Host")
        }
      }
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
        modifier = Modifier.weight(1f)
      ) {
        Text(if (hostState.webrtcStreaming) "Stop Camera" else "Start Camera")
      }
    }
  }
}
