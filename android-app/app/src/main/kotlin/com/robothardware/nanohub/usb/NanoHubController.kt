package com.robothardware.nanohub.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class FirmwareRevision(
  val id: String,
  val title: String,
  val subtitle: String,
  val source: String, // "OTA Push" or "Bundled"
  val format: String, // "BIN" (UNO R4 Minima) or "HEX" (Nano v3.0)
  val sizeBytes: Int,
  val rawBytes: ByteArray,
  val timestampMs: Long
)

data class NanoTelemetry(
  val fwVersion: String = "—",
  val board: String = "—",
  val mode: String = "—",
  val ledOn: Boolean = false,
  val blinkMs: Int = 500,
  val streaming: Boolean = false,
  val uptimeMs: Long = 0,
  val a0: Int = 0,
  val a1: Int = 0,
  val a2: Int = 0,
  val a3: Int = 0
)

data class NanoHubState(
  val connectedDeviceName: String? = null,
  val connectedVidPid: String? = null,
  val chipLabel: String? = null,
  val isConnected: Boolean = false,
  val isDfuMode: Boolean = false,
  val usbDeviceCount: Int = 0,
  val serialBaud: Int = 115200,
  val bootloaderBaudMode: Int = 0, // 0 = Auto (115200 -> 57600), 115200 = New, 57600 = Old
  val isFlashing: Boolean = false,
  val flashProgress: Float = 0f,
  val flashStatus: String = "Ready to flash",
  val lastFlashSuccess: Boolean? = null,
  val telemetry: NanoTelemetry = NanoTelemetry(),
  val revisions: List<FirmwareRevision> = emptyList(),
  val selectedRevisionId: String = "",
  val logs: List<String> = emptyList()
)

class NanoHubController(private val context: Context) {
  companion object {
    const val ACTION_USB_PERMISSION = "com.robothardware.nanohub.USB_PERMISSION"
    const val ACTION_OTA_FIRMWARE = "com.robothardware.nanohub.OTA_FIRMWARE"
  }

  private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow(NanoHubState())
  val state: StateFlow<NanoHubState> = _state.asStateFlow()

  var onSerialLine: ((String) -> Unit)? = null
  var onFlashProgress: ((Float, String, Boolean, Boolean?) -> Unit)? = null

  private var serialPort: UsbSerialPort? = null
  private var dfuDevice: UsbDevice? = null
  private var dfuConnection: UsbDeviceConnection? = null
  @Volatile private var pendingDfuBytes: ByteArray? = null
  @Volatile private var pendingDfuTitle: String = ""
  private var readJob: Job? = null
  @Volatile private var pauseSerialReader = false

  private val usbReceiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
      when (intent.action) {
        ACTION_USB_PERMISSION -> {
          val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
          }
          val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
          if (granted && device != null) {
            appendLog("USB permission granted for ${formatVidPid(device)}")
            connectDevice(device)
          } else {
            appendLog("USB permission denied for device ${device?.deviceName ?: "unknown"}")
          }
        }
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
          appendLog("USB OTG device attached")
          scanAndConnectUsb()
        }
        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
          appendLog("USB OTG device detached")
          disconnect()
          scanAndConnectUsb()
        }
        ACTION_OTA_FIRMWARE -> {
          val autoFlash = intent.getBooleanExtra("auto_flash", false)
          appendLog("Received new firmware revision push over Wireless ADB!")
          refreshFirmwareRevisions()
          if (autoFlash && _state.value.isConnected && !_state.value.isFlashing) {
            val latestOta = _state.value.revisions.firstOrNull { it.source == "OTA Push" }
            if (latestOta != null) {
              selectRevision(latestOta.id)
              flashSelectedRevision()
            }
          }
        }
      }
    }
  }

  init {
    val filter = IntentFilter().apply {
      addAction(ACTION_USB_PERMISSION)
      addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
      addAction(ACTION_OTA_FIRMWARE)
    }
    ContextCompat.registerReceiver(
      context,
      usbReceiver,
      filter,
      ContextCompat.RECEIVER_EXPORTED
    )
    refreshFirmwareRevisions()
    scanAndConnectUsb()
  }

  fun scanAndConnectUsb() {
    val devices = usbManager.deviceList.values.toList()
    _state.update { it.copy(usbDeviceCount = devices.size) }

    if (devices.isEmpty()) {
      appendLog("USB scan: 0 OTG devices detected. Plug Arduino Nano into phone via OTG adapter.")
      if (_state.value.isConnected) {
        disconnect()
      }
      return
    }

    val target = devices.firstOrNull { UsbSerialPort.isSupportedDevice(it) } ?: devices.first()
    appendLog("Detected USB device: ${target.productName ?: target.deviceName} (${formatVidPid(target)})")

    if (usbManager.hasPermission(target)) {
      connectDevice(target)
    } else {
      appendLog("Requesting USB permission for ${formatVidPid(target)}...")
      val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
      val pi = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        flags
      )
      usbManager.requestPermission(target, pi)
    }
  }

  private fun connectDevice(device: UsbDevice) {
    val st = _state.value
    if (st.isConnected && (serialPort?.device?.deviceName == device.deviceName || dfuDevice?.deviceName == device.deviceName)) {
      appendLog("Already connected to ${device.deviceName}; skipping duplicate attach")
      return
    }
    disconnect()
    val conn = usbManager.openDevice(device)
    if (conn == null) {
      appendLog("Failed to open USB connection for ${formatVidPid(device)}")
      return
    }

    if (UsbDfuFlasher.isDfuBootloader(device)) {
      dfuDevice = device
      dfuConnection = conn
      _state.update {
        it.copy(
          isConnected = true,
          isDfuMode = true,
          connectedDeviceName = device.productName ?: "UNO R4 Minima (DFU)",
          connectedVidPid = formatVidPid(device),
          chipLabel = "UNO R4 Minima DFU Bootloader"
        )
      }
      appendLog("Connected to UNO R4 Minima DFU Bootloader (${formatVidPid(device)})")

      val pending = pendingDfuBytes
      if (pending != null) {
        val title = pendingDfuTitle
        pendingDfuBytes = null
        pendingDfuTitle = ""
        executeDfuFlash(device, conn, pending, title)
      }
      return
    }

    val port = UsbSerialPort(device, conn)
    val baud = _state.value.serialBaud
    if (!port.open(baud)) {
      appendLog("Failed to claim USB bulk endpoints on ${formatVidPid(device)}")
      return
    }

    serialPort = port
    _state.update {
      it.copy(
        isConnected = true,
        isDfuMode = false,
        connectedDeviceName = device.productName ?: "Arduino Board",
        connectedVidPid = formatVidPid(device),
        chipLabel = port.chipType.label
      )
    }
    appendLog("Connected to ${port.chipType.label} (${formatVidPid(device)}) @ $baud baud")
    startSerialReader()

    // Request status shortly after opening port
    scope.launch {
      delay(if (port.chipType == ChipType.UNO_R4_MINIMA) 400 else 1800)
      if (!pauseSerialReader && _state.value.isConnected) {
        sendCommand("INFO")
      }
    }
  }

  private fun startSerialReader() {
    readJob?.cancel()
    readJob = scope.launch {
      val buf = ByteArray(256)
      val lineBuilder = StringBuilder()
      while (isActive) {
        if (pauseSerialReader) {
          delay(50)
          continue
        }
        val port = serialPort ?: break
        val n = try {
          port.read(buf, 120)
        } catch (e: Exception) {
          -1
        }
        if (n > 0) {
          val chunk = String(buf, 0, n, Charsets.UTF_8)
          for (ch in chunk) {
            if (ch == '\n' || ch == '\r') {
              if (lineBuilder.isNotEmpty()) {
                val line = lineBuilder.toString().trim()
                lineBuilder.clear()
                if (line.isNotEmpty()) {
                  handleIncomingLine(line)
                }
              }
            } else if (lineBuilder.length < 512) {
              lineBuilder.append(ch)
            }
          }
        } else {
          delay(15)
        }
      }
    }
  }

  private fun handleIncomingLine(line: String) {
    if (line.startsWith("{") && line.endsWith("}")) {
      try {
        val json = JSONObject(line)
        if (json.optString("type") == "status") {
          val telem = NanoTelemetry(
            fwVersion = json.optString("fw", "—"),
            board = json.optString("board", "—"),
            mode = json.optString("mode", "BLINK"),
            ledOn = json.optInt("led", 0) == 1,
            blinkMs = json.optInt("blinkMs", 500),
            streaming = json.optInt("stream", 0) == 1,
            uptimeMs = json.optLong("uptimeMs", 0L),
            a0 = json.optInt("a0", 0),
            a1 = json.optInt("a1", 0),
            a2 = json.optInt("a2", 0),
            a3 = json.optInt("a3", 0)
          )
          _state.update { it.copy(telemetry = telem) }
        }
      } catch (_: Exception) {
      }
    }
    appendLog("RX << $line")
    onSerialLine?.invoke(line)
  }

  fun sendCommand(cmd: String) {
    val clean = cmd.trim()
    if (clean.isEmpty()) return
    scope.launch {
      val port = serialPort
      if (port == null || pauseSerialReader) {
        appendLog("Cannot send '$clean': Arduino not connected or busy flashing")
        return@launch
      }
      val payload = "$clean\n".toByteArray(Charsets.UTF_8)
      val written = port.write(payload, 300)
      if (written > 0) {
        appendLog("TX >> $clean")
      } else {
        appendLog("TX Error sending '$clean'")
      }
    }
  }

  fun setSerialBaud(baud: Int) {
    _state.update { it.copy(serialBaud = baud) }
    scope.launch {
      serialPort?.setBaudRate(baud)
      appendLog("Serial monitor baud rate set to $baud")
    }
  }

  fun setBootloaderBaudMode(baudMode: Int) {
    _state.update { it.copy(bootloaderBaudMode = baudMode) }
  }

  fun selectRevision(id: String) {
    _state.update { it.copy(selectedRevisionId = id) }
  }

  fun handleDownloadedFirmware(fileName: String, bytes: ByteArray, autoFlash: Boolean) {
    scope.launch {
      val extDir = File(context.getExternalFilesDir(null), "firmware").apply { mkdirs() }
      File(extDir, fileName).writeBytes(bytes)
      appendLog("Downloaded OTA firmware: $fileName (${bytes.size} B)")
      refreshFirmwareRevisions()
      delay(200)
      val targetId = "ota:$fileName"
      selectRevision(targetId)
      if (autoFlash && _state.value.isConnected && !_state.value.isFlashing) {
        flashSelectedRevision()
      }
    }
  }

  fun refreshFirmwareRevisions() {
    scope.launch {
      val list = mutableListOf<FirmwareRevision>()

      // 1. Check external files dir for OTA-pushed revisions via Wireless ADB
      val extDir = File(context.getExternalFilesDir(null), "firmware")
      if (!extDir.exists()) {
        extDir.mkdirs()
      }
      val extFiles = extDir.listFiles { f ->
        f.isFile && (f.name.endsWith(".bin", ignoreCase = true) || f.name.endsWith(".hex", ignoreCase = true))
      }?.sortedByDescending { it.lastModified() } ?: emptyList()

      for (file in extFiles) {
        try {
          val isBin = file.name.endsWith(".bin", ignoreCase = true)
          val bytes = if (isBin) file.readBytes() else IntelHexParser.parse(file.readText())
          val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
          list.add(
            FirmwareRevision(
              id = "ota:${file.name}",
              title = file.nameWithoutExtension,
              subtitle = "Pushed via ADB at ${sdf.format(Date(file.lastModified()))} • ${bytes.size} B",
              source = "OTA Push",
              format = if (isBin) "BIN" else "HEX",
              sizeBytes = bytes.size,
              rawBytes = bytes,
              timestampMs = file.lastModified()
            )
          )
        } catch (e: Exception) {
          appendLog("Skipped invalid firmware ${file.name}: ${e.message}")
        }
      }

      // 2. Load bundled APK asset revisions (UNO R4 Minima .bin first, then Nano v3.0 .hex)
      val bundledMeta = listOf(
        Triple(
          "unor4_v1.0.0_standard_500ms.bin",
          "UNO R4 Minima v1.0.0 — Standard (500ms Blink)",
          "USB CDC Console + 500ms LED Blink + A0-A3 Telemetry"
        ),
        Triple(
          "unor4_v1.1.0_fast_strobe_100ms.bin",
          "UNO R4 Minima v1.1.0 — Fast Strobe (100ms Blink)",
          "High-speed 10Hz LED Strobe + USB CDC Console"
        ),
        Triple(
          "unor4_v1.2.0_heartbeat_1200ms.bin",
          "UNO R4 Minima v1.2.0 — Heartbeat (1200ms Blink)",
          "Calm 1.2s LED Pulse + USB CDC Console"
        ),
        Triple(
          "nano_v1.0.0_standard_500ms.hex",
          "Nano v3.0 v1.0.0 — Standard (500ms Blink)",
          "Serial Console + 500ms LED Blink + A0-A3 Telemetry"
        ),
        Triple(
          "nano_v1.1.0_fast_strobe_100ms.hex",
          "Nano v3.0 v1.1.0 — Fast Strobe (100ms Blink)",
          "High-speed 10Hz LED Strobe + Serial Console"
        ),
        Triple(
          "nano_v1.2.0_heartbeat_1200ms.hex",
          "Nano v3.0 v1.2.0 — Heartbeat (1200ms Blink)",
          "Calm 1.2s LED Pulse + Serial Console"
        )
      )

      for ((fileName, title, desc) in bundledMeta) {
        try {
          val isBin = fileName.endsWith(".bin", ignoreCase = true)
          val bytes = context.assets.open("firmware/$fileName").use { stream ->
            if (isBin) stream.readBytes() else IntelHexParser.parse(stream.bufferedReader().readText())
          }
          list.add(
            FirmwareRevision(
              id = "asset:$fileName",
              title = title,
              subtitle = "$desc (${bytes.size} B)",
              source = "Bundled",
              format = if (isBin) "BIN" else "HEX",
              sizeBytes = bytes.size,
              rawBytes = bytes,
              timestampMs = 0L
            )
          )
        } catch (_: Exception) {
        }
      }

      _state.update { current ->
        val selected = if (current.selectedRevisionId.isNotEmpty() && list.any { it.id == current.selectedRevisionId }) {
          current.selectedRevisionId
        } else {
          list.firstOrNull()?.id ?: ""
        }
        current.copy(revisions = list, selectedRevisionId = selected)
      }
    }
  }

  fun importCustomHexUri(uri: Uri) {
    scope.launch {
      try {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: throw IllegalArgumentException("Could not read file")
        val asText = raw.toString(Charsets.UTF_8)
        val isHex = asText.trimStart().startsWith(":")
        val ext = if (isHex) "hex" else "bin"
        val size = if (isHex) IntelHexParser.parse(asText).size else raw.size
        val extDir = File(context.getExternalFilesDir(null), "firmware").apply { mkdirs() }
        val fileName = "custom_${System.currentTimeMillis() / 1000}.$ext"
        File(extDir, fileName).writeBytes(raw)
        appendLog("Imported custom .$ext ($fileName, $size bytes)")
        refreshFirmwareRevisions()
      } catch (e: Exception) {
        appendLog("Firmware import error: ${e.message}")
      }
    }
  }

  fun flashSelectedRevision() {
    val st = _state.value
    if (st.isFlashing) return
    val rev = st.revisions.firstOrNull { it.id == st.selectedRevisionId }
    if (rev == null) {
      appendLog("No firmware revision selected to flash")
      return
    }
    if (!st.isConnected) {
      appendLog("Cannot flash: Arduino is not connected via USB OTG")
      _state.update {
        it.copy(
          lastFlashSuccess = false,
          flashStatus = "Connect Arduino via USB OTG first"
        )
      }
      return
    }

    // Route 1: Already in UNO R4 Minima DFU Bootloader mode (0x2341:0x0369)
    val dDev = dfuDevice
    val dConn = dfuConnection
    if (st.isDfuMode && dDev != null && dConn != null) {
      if (rev.format != "BIN") {
        appendLog("UNO R4 Minima requires a .BIN firmware revision (selected is .${rev.format})")
        _state.update { it.copy(lastFlashSuccess = false, flashStatus = "Select a UNO R4 Minima (.BIN) revision") }
        return
      }
      executeDfuFlash(dDev, dConn, rev.rawBytes, rev.title)
      return
    }

    val port = serialPort ?: return

    // Route 2: UNO R4 Minima in Sketch mode (0x2341:0x0069) -> DFU_DETACH into bootloader
    if (port.chipType == ChipType.UNO_R4_MINIMA) {
      if (rev.format != "BIN") {
        appendLog("UNO R4 Minima requires a .BIN firmware revision (selected is .${rev.format})")
        _state.update { it.copy(lastFlashSuccess = false, flashStatus = "Select a UNO R4 Minima (.BIN) revision") }
        return
      }
      scope.launch {
        pauseSerialReader = true
        _state.update {
          it.copy(
            isFlashing = true,
            flashProgress = 0.03f,
            flashStatus = "Switching UNO R4 Minima to DFU Bootloader...",
            lastFlashSuccess = null
          )
        }
        appendLog("--- Switching UNO R4 Minima to DFU Bootloader for ${rev.title} ---")
        pendingDfuBytes = rev.rawBytes
        pendingDfuTitle = rev.title

        val detached = UsbDfuFlasher().triggerBootloaderDetach(port.device, port.connection)
        if (!detached) {
          // Fallback: 1200-baud touch
          port.setBaudRate(1200)
          port.setDtrRts(false, false)
          delay(100)
        }
        // Disconnect immediately BEFORE the DFU device re-enumerates so we don't close the new DFU handle
        disconnect()
      }
      return
    }

    // Route 3: Arduino Nano v3.0 (STK500v1 over UART)
    if (rev.format != "HEX") {
      appendLog("Arduino Nano v3.0 requires a .HEX firmware revision (selected is .${rev.format})")
      _state.update { it.copy(lastFlashSuccess = false, flashStatus = "Select a Nano v3.0 (.HEX) revision") }
      return
    }

    scope.launch {
      pauseSerialReader = true
      _state.update {
        it.copy(
          isFlashing = true,
          flashProgress = 0.02f,
          flashStatus = "Starting STK500v1 flash for ${rev.title}...",
          lastFlashSuccess = null
        )
      }
      appendLog("--- Starting STK500v1 Flash: ${rev.title} ---")

      try {
        val flasher = Stk500Flasher(port)
        val result = flasher.flash(
          firmwareBytes = rev.rawBytes,
          preferredBaud = _state.value.bootloaderBaudMode
        ) { progress, status ->
          _state.update {
            it.copy(flashProgress = progress, flashStatus = status)
          }
        }

        port.setBaudRate(_state.value.serialBaud)
        pauseSerialReader = false

        _state.update {
          it.copy(
            isFlashing = false,
            flashProgress = if (result.success) 1f else 0f,
            flashStatus = result.message,
            lastFlashSuccess = result.success
          )
        }
        appendLog(if (result.success) "FLASH SUCCESS: ${result.message}" else "FLASH FAILED: ${result.message}")

        if (result.success) {
          delay(1500)
          sendCommand("INFO")
        }
      } catch (e: Exception) {
        port.setBaudRate(_state.value.serialBaud)
        pauseSerialReader = false
        _state.update {
          it.copy(
            isFlashing = false,
            flashProgress = 0f,
            flashStatus = "Flash error: ${e.message}",
            lastFlashSuccess = false
          )
        }
        appendLog("Flash error: ${e.message}")
      }
    }
  }

  private fun executeDfuFlash(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    firmwareBytes: ByteArray,
    title: String
  ) {
    scope.launch {
      _state.update {
        it.copy(
          isFlashing = true,
          flashProgress = 0.05f,
          flashStatus = "Flashing $title via USB DFU...",
          lastFlashSuccess = null
        )
      }
      appendLog("--- Starting USB DFU Flash: $title (${firmwareBytes.size} B) ---")
      try {
        delay(250) // Allow Santiago DFU to settle after USB enumeration
        val result = UsbDfuFlasher().flash(device, connection, firmwareBytes) { progress, status ->
          _state.update { it.copy(flashProgress = progress, flashStatus = status) }
          onFlashProgress?.invoke(progress, status, false, null)
        }
        if (result.success) {
          disconnect()
        }
        _state.update {
          it.copy(
            isFlashing = false,
            flashProgress = if (result.success) 1f else 0f,
            flashStatus = result.message,
            lastFlashSuccess = result.success
          )
        }
        onFlashProgress?.invoke(if (result.success) 1f else 0f, result.message, true, result.success)
        appendLog(if (result.success) "FLASH SUCCESS: ${result.message}" else "FLASH FAILED: ${result.message}")
      } catch (e: Exception) {
        _state.update {
          it.copy(
            isFlashing = false,
            flashProgress = 0f,
            flashStatus = "DFU error: ${e.message}",
            lastFlashSuccess = false
          )
        }
        appendLog("DFU error: ${e.message}")
      }
    }
  }

  fun clearLogs() {
    _state.update { it.copy(logs = emptyList()) }
  }

  fun disconnect() {
    readJob?.cancel()
    readJob = null
    serialPort?.close()
    serialPort = null
    try {
      dfuConnection?.close()
    } catch (_: Exception) {
    }
    dfuConnection = null
    dfuDevice = null
    _state.update {
      it.copy(
        isConnected = false,
        isDfuMode = false,
        connectedDeviceName = null,
        connectedVidPid = null,
        chipLabel = null
      )
    }
  }

  fun destroy() {
    try {
      context.unregisterReceiver(usbReceiver)
    } catch (_: Exception) {
    }
    disconnect()
  }

  private fun appendLog(msg: String) {
    Log.d("NanoHub", msg)
    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    val entry = "[$ts] $msg"
    _state.update { current ->
      val updated = (current.logs + entry).takeLast(120)
      current.copy(logs = updated)
    }
  }

  private fun formatVidPid(device: UsbDevice): String {
    return "%04X:%04X".format(device.vendorId, device.productId)
  }
}
