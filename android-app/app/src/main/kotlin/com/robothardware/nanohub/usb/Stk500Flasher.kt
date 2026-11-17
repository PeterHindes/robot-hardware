package com.robothardware.nanohub.usb

class Stk500Flasher(private val port: UsbSerialPort) {
  companion object {
    private const val STK_OK: Byte = 0x10
    private const val STK_INSYNC: Byte = 0x14
    private const val CRC_EOP: Byte = 0x20
    private const val STK_GET_SYNC: Byte = 0x30
    private const val STK_ENTER_PROGMODE: Byte = 0x50
    private const val STK_LEAVE_PROGMODE: Byte = 0x51
    private const val STK_LOAD_ADDRESS: Byte = 0x55
    private const val STK_PROG_PAGE: Byte = 0x64
    private const val STK_READ_SIGN: Byte = 0x75
  }

  data class FlashResult(
    val success: Boolean,
    val message: String,
    val detectedBaud: Int = 0,
    val signatureHex: String = ""
  )

  fun flash(
    firmwareBytes: ByteArray,
    preferredBaud: Int = 0, // 0 = Auto-detect (115200 then 57600)
    onProgress: (Float, String) -> Unit
  ): FlashResult {
    val baudCandidates = if (preferredBaud > 0) {
      listOf(preferredBaud)
    } else {
      listOf(115200, 57600)
    }

    var syncedBaud = 0
    for (baud in baudCandidates) {
      onProgress(0.05f, "Resetting Arduino Nano & syncing bootloader @ $baud baud...")
      port.setBaudRate(baud)
      port.pulseReset()
      Thread.sleep(220)
      port.drainInput()

      if (syncBootloader()) {
        syncedBaud = baud
        break
      }
    }

    if (syncedBaud == 0) {
      return FlashResult(
        success = false,
        message = "Failed to sync with Arduino Nano bootloader (tried ${baudCandidates.joinToString("/")} baud). Check USB connection or press Reset on Nano right as flashing starts."
      )
    }

    onProgress(0.12f, "Connected to bootloader @ $syncedBaud baud! Reading MCU signature...")
    val sig = readSignature()
    if (sig != null && sig != "1E950F") {
      return FlashResult(
        success = false,
        message = "Unexpected MCU signature $sig (expected ATmega328P 1E950F)",
        detectedBaud = syncedBaud,
        signatureHex = sig
      )
    }

    if (!sendCommandExpectOk(byteArrayOf(STK_ENTER_PROGMODE, CRC_EOP), 400)) {
      return FlashResult(
        success = false,
        message = "Failed to enter STK500 programming mode",
        detectedBaud = syncedBaud
      )
    }

    val pageSize = IntelHexParser.PAGE_SIZE
    val totalPages = (firmwareBytes.size + pageSize - 1) / pageSize

    for (pageIdx in 0 until totalPages) {
      val byteAddress = pageIdx * pageSize
      val wordAddress = byteAddress / 2
      val addrLow = (wordAddress and 0xFF).toByte()
      val addrHigh = ((wordAddress shr 8) and 0xFF).toByte()

      val loadAddrCmd = byteArrayOf(STK_LOAD_ADDRESS, addrLow, addrHigh, CRC_EOP)
      if (!sendCommandExpectOk(loadAddrCmd, 400)) {
        return FlashResult(
          success = false,
          message = "STK_LOAD_ADDRESS failed at page $pageIdx (0x${byteAddress.toString(16)})",
          detectedBaud = syncedBaud
        )
      }

      val pageEnd = (byteAddress + pageSize).coerceAtMost(firmwareBytes.size)
      val pageData = firmwareBytes.copyOfRange(byteAddress, pageEnd)
      val lenLow = (pageData.size and 0xFF).toByte()
      val lenHigh = ((pageData.size shr 8) and 0xFF).toByte()

      val progCmd = ByteArray(5 + pageData.size)
      progCmd[0] = STK_PROG_PAGE
      progCmd[1] = lenHigh
      progCmd[2] = lenLow
      progCmd[3] = 'F'.code.toByte()
      System.arraycopy(pageData, 0, progCmd, 4, pageData.size)
      progCmd[4 + pageData.size] = CRC_EOP

      if (!sendCommandExpectOk(progCmd, 800)) {
        return FlashResult(
          success = false,
          message = "STK_PROG_PAGE failed at page ${pageIdx + 1}/$totalPages",
          detectedBaud = syncedBaud
        )
      }

      val frac = 0.15f + 0.80f * ((pageIdx + 1).toFloat() / totalPages.toFloat())
      onProgress(frac, "Flashing page ${pageIdx + 1} / $totalPages (${byteAddress + pageData.size} / ${firmwareBytes.size} B)...")
    }

    onProgress(0.97f, "Leaving programming mode & booting new firmware...")
    sendCommandExpectOk(byteArrayOf(STK_LEAVE_PROGMODE, CRC_EOP), 400)

    return FlashResult(
      success = true,
      message = "Flashed ${firmwareBytes.size} bytes ($totalPages pages) @ $syncedBaud baud (${if (syncedBaud == 115200) "New Optiboot" else "Old Bootloader"})",
      detectedBaud = syncedBaud,
      signatureHex = sig ?: "1E950F"
    )
  }

  private fun syncBootloader(): Boolean {
    val cmd = byteArrayOf(STK_GET_SYNC, CRC_EOP)
    for (attempt in 1..8) {
      port.drainInput()
      port.write(cmd, 200)
      if (readSyncOk(180)) {
        return true
      }
      Thread.sleep(40)
    }
    return false
  }

  private fun readSignature(): String? {
    port.write(byteArrayOf(STK_READ_SIGN, CRC_EOP), 200)
    val resp = readBytes(5, 400) ?: return null
    if (resp.size >= 5 && resp[0] == STK_INSYNC && resp[4] == STK_OK) {
      return "%02X%02X%02X".format(resp[1], resp[2], resp[3])
    }
    return null
  }

  private fun sendCommandExpectOk(cmd: ByteArray, timeoutMs: Int): Boolean {
    port.write(cmd, timeoutMs)
    return readSyncOk(timeoutMs)
  }

  private fun readSyncOk(timeoutMs: Int): Boolean {
    val resp = readBytes(2, timeoutMs) ?: return false
    return resp.size >= 2 && resp[0] == STK_INSYNC && resp[1] == STK_OK
  }

  private fun readBytes(expectedCount: Int, timeoutMs: Int): ByteArray? {
    val out = ByteArray(expectedCount)
    var offset = 0
    val deadline = System.currentTimeMillis() + timeoutMs
    val scratch = ByteArray(64)

    while (offset < expectedCount && System.currentTimeMillis() < deadline) {
      val n = port.read(scratch, 60)
      if (n > 0) {
        for (i in 0 until n) {
          val b = scratch[i]
          // Skip leading noise until STK_INSYNC (0x14)
          if (offset == 0 && b != STK_INSYNC) continue
          if (offset < expectedCount) {
            out[offset++] = b
          }
        }
      }
    }
    return if (offset == expectedCount) out else null
  }
}
