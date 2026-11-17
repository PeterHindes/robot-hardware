package com.robothardware.nanohub.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robothardware.nanohub.net.HostLinkManager
import com.robothardware.nanohub.net.HostLinkState
import com.robothardware.nanohub.usb.FirmwareRevision
import com.robothardware.nanohub.usb.NanoHubController
import com.robothardware.nanohub.usb.NanoHubState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NanoHubAppScreen(
  controller: NanoHubController,
  hostLink: HostLinkManager,
  modifier: Modifier = Modifier
) {
  val state by controller.state.collectAsStateWithLifecycle()
  val hostState by hostLink.state.collectAsStateWithLifecycle()
  var selectedTab by remember { mutableIntStateOf(0) }

  val filePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri ->
    if (uri != null) {
      controller.importCustomHexUri(uri)
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "Arduino OTG Hub",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (state.isConnected) {
                "${state.chipLabel ?: "USB Serial"} • ${state.connectedVidPid ?: ""}"
              } else {
                "USB OTG Disconnected (${state.usbDeviceCount} USB dev)"
              },
              style = MaterialTheme.typography.labelSmall,
              color = if (state.isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        actions = {
          OutlinedButton(
            onClick = { controller.scanAndConnectUsb() },
            modifier = Modifier.padding(end = 8.dp)
          ) {
            Text(if (state.isConnected) "Rescan" else "Connect OTG")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .padding(innerPadding)
        .fillMaxSize()
    ) {
      ConnectionBanner(state = state)

      PrimaryTabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          text = { Text("Viewfinder") }
        )
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          text = { Text("Controls") }
        )
        Tab(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          text = { Text("Firmware") }
        )
        Tab(
          selected = selectedTab == 3,
          onClick = { selectedTab = 3 },
          text = { Text("Serial") }
        )
        Tab(
          selected = selectedTab == 4,
          onClick = { selectedTab = 4 },
          text = { Text(if (hostState.wsConnected) "Host •" else "Host") }
        )
      }

      when (selectedTab) {
        0 -> ViewfinderTab(hostState = hostState, hostLink = hostLink)
        1 -> ControlsTab(state = state, controller = controller)
        2 -> FirmwareTab(
          state = state,
          controller = controller,
          onPickHex = { filePicker.launch("*/*") }
        )
        3 -> SerialMonitorTab(state = state, controller = controller)
        4 -> HostLinkTab(hostState = hostState, hostLink = hostLink)
      }
    }
  }
}

@Composable
private fun ConnectionBanner(state: NanoHubState) {
  val bg = if (state.isConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
  val dotColor = if (state.isConnected) Color(0xFF2E7D32) else Color(0xFFEF6C00)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(bg)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(10.dp)
          .clip(CircleShape)
          .background(dotColor)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = if (state.isConnected) {
          if (state.isDfuMode) {
            "DFU BOOTLOADER READY • Flash a .BIN revision"
          } else {
            "ONLINE • ${state.telemetry.board} • FW ${state.telemetry.fwVersion} • ${state.telemetry.mode}"
          }
        } else {
          "Plug Arduino UNO R4 Minima / Nano into phone via USB OTG"
        },
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF1B1B1B),
        fontWeight = FontWeight.Medium
      )
    }
    if (state.isConnected) {
      Text(
        text = "LED: ${if (state.telemetry.ledOn) "ON" else "OFF"}",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF1B1B1B),
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsTab(
  state: NanoHubState,
  controller: NanoHubController
) {
  var pwmPin by remember { mutableIntStateOf(3) }
  var pwmValue by remember { mutableFloatStateOf(128f) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Telemetry Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Live Nano Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.sendCommand("INFO") }) {
              Text("Refresh")
            }
            Button(
              onClick = {
                controller.sendCommand(if (state.telemetry.streaming) "STREAM OFF" else "STREAM ON")
              }
            ) {
              Text(if (state.telemetry.streaming) "Stop Stream" else "Live Stream")
            }
          }
        }
        Text(
          text = "Firmware: ${state.telemetry.fwVersion}   |   Blink: ${state.telemetry.blinkMs} ms   |   Uptime: ${state.telemetry.uptimeMs / 1000}s",
          style = MaterialTheme.typography.bodySmall
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          AnalogBadge("A0", state.telemetry.a0)
          AnalogBadge("A1", state.telemetry.a1)
          AnalogBadge("A2", state.telemetry.a2)
          AnalogBadge("A3", state.telemetry.a3)
        }
      }
    }

    // Built-in LED & Blink Control Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Built-in LED (Pin D13) & Blink Pattern", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(onClick = { controller.sendCommand("LED ON") }) { Text("LED ON") }
          Button(onClick = { controller.sendCommand("LED OFF") }) { Text("LED OFF") }
          OutlinedButton(onClick = { controller.sendCommand("LED TOGGLE") }) { Text("Toggle") }
          OutlinedButton(onClick = { controller.sendCommand("BLINK 80") }) { Text("Strobe (80ms)") }
          OutlinedButton(onClick = { controller.sendCommand("BLINK 250") }) { Text("Fast (250ms)") }
          OutlinedButton(onClick = { controller.sendCommand("BLINK 500") }) { Text("Default (500ms)") }
          OutlinedButton(onClick = { controller.sendCommand("BLINK 1200") }) { Text("Slow (1.2s)") }
        }
      }
    }

    // PWM Output Control Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PWM Output Control (0..255)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(3, 5, 6, 9, 10, 11).forEach { pin ->
            FilterChip(
              selected = pwmPin == pin,
              onClick = { pwmPin = pin },
              label = { Text("D$pin") }
            )
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Slider(
            value = pwmValue,
            onValueChange = { pwmValue = it },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
          )
          Button(
            onClick = { controller.sendCommand("PWM $pwmPin ${pwmValue.toInt()}") }
          ) {
            Text("Set ${pwmValue.toInt()}")
          }
        }
      }
    }

    // Quick Digital Pin Trigger
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Digital Pin Quick Toggle", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(2, 4, 7, 8, 12).forEach { pin ->
            OutlinedButton(onClick = { controller.sendCommand("PIN $pin HIGH") }) {
              Text("D$pin HIGH")
            }
            OutlinedButton(onClick = { controller.sendCommand("PIN $pin LOW") }) {
              Text("D$pin LOW")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AnalogBadge(name: String, value: Int) {
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(name, style = MaterialTheme.typography.labelSmall)
    Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun FirmwareTab(
  state: NanoHubState,
  controller: NanoHubController,
  onPickHex: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Flasher Status & Action Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "USB OTG Flasher (UNO R4 DFU .BIN & Nano STK500 .HEX)",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Bootloader Baud (Nano v3.0 clones often use 57600 or 115200):",
          style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = state.bootloaderBaudMode == 0,
            onClick = { controller.setBootloaderBaudMode(0) },
            label = { Text("Auto (115200→57600)") }
          )
          FilterChip(
            selected = state.bootloaderBaudMode == 115200,
            onClick = { controller.setBootloaderBaudMode(115200) },
            label = { Text("115200 (New)") }
          )
          FilterChip(
            selected = state.bootloaderBaudMode == 57600,
            onClick = { controller.setBootloaderBaudMode(57600) },
            label = { Text("57600 (Old)") }
          )
        }

        if (state.isFlashing) {
          LinearProgressIndicator(
            progress = { state.flashProgress },
            modifier = Modifier.fillMaxWidth()
          )
        }

        Text(
          text = state.flashStatus,
          style = MaterialTheme.typography.bodySmall,
          color = when (state.lastFlashSuccess) {
            true -> Color(0xFF2E7D32)
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
          },
          fontWeight = FontWeight.Medium
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(
            onClick = { controller.flashSelectedRevision() },
            enabled = !state.isFlashing && state.revisions.isNotEmpty(),
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          ) {
            Text(if (state.isFlashing) "Flashing Nano..." else "Flash Selected Revision")
          }
          OutlinedButton(onClick = { controller.refreshFirmwareRevisions() }) {
            Text("Reload")
          }
          OutlinedButton(onClick = onPickHex) {
            Text("+ .BIN/.HEX")
          }
        }
      }
    }

    Text(
      text = "Available Firmware Revisions (OTA ADB Push & Bundled):",
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold
    )

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(state.revisions, key = { it.id }) { rev ->
        RevisionItemCard(
          revision = rev,
          selected = rev.id == state.selectedRevisionId,
          onSelect = { controller.selectRevision(rev.id) }
        )
      }
    }
  }
}



@Composable
private fun RevisionItemCard(
  revision: FirmwareRevision,
  selected: Boolean,
  onSelect: () -> Unit
) {
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .border(2.dp, borderColor, RoundedCornerShape(12.dp))
      .clickable { onSelect() },
    colors = CardDefaults.cardColors(
      containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      }
    )
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      RadioButton(selected = selected, onClick = onSelect)
      Spacer(modifier = Modifier.width(6.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = revision.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = revision.source,
            style = MaterialTheme.typography.labelSmall,
            color = if (revision.source == "OTA Push") Color(0xFF1565C0) else MaterialTheme.colorScheme.secondary
          )
        }
        Text(
          text = revision.subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}



@Composable
private fun SerialMonitorTab(
  state: NanoHubState,
  controller: NanoHubController
) {
  var cmdInput by remember { mutableStateOf("") }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(115200, 57600, 9600).forEach { baud ->
          FilterChip(
            selected = state.serialBaud == baud,
            onClick = { controller.setSerialBaud(baud) },
            label = { Text("$baud") }
          )
        }
      }
      OutlinedButton(onClick = { controller.clearLogs() }) {
        Text("Clear")
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = cmdInput,
        onValueChange = { cmdInput = it },
        placeholder = { Text("Command (e.g. PING, INFO, BLINK 200)") },
        singleLine = true,
        modifier = Modifier.weight(1f)
      )
      Button(
        onClick = {
          controller.sendCommand(cmdInput)
          cmdInput = ""
        }
      ) {
        Text("Send")
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF121212))
        .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      items(state.logs) { line ->
        Text(
          text = line,
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = when {
            line.contains("FLASH SUCCESS") -> Color(0xFF69F0AE)
            line.contains("FLASH FAILED") || line.contains("Error") -> Color(0xFFFF5252)
            line.contains("TX >>") -> Color(0xFF82B1FF)
            line.contains("RX <<") -> Color(0xFFE0E0E0)
            else -> Color(0xFFB0BEC5)
          }
        )
      }
    }
  }
}


