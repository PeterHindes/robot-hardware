package com.robothardware.nanohub.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import kotlin.math.min

/**
 * USB DFU 1.1 flasher for Arduino UNO R4 Minima (Renesas RA4M1).
 * - Sketch mode: VID 0x2341, PID 0x0069 (exposes CDC-ACM + DFU Runtime interface)
 * - Bootloader mode: VID 0x2341, PID 0x0369 (exposes DFU Mode interface 0)
 */
class UsbDfuFlasher {
  companion object {
    const val ARDUINO_VID = 0x2341
    const val MINIMA_SKETCH_PID = 0x0069
    const val MINIMA_DFU_PID = 0x0369

    private const val USB_CLASS_APP_SPEC = 0xFE
    private const val USB_SUBCLASS_DFU = 0x01
    private const val DFU_PROTO_RUNTIME = 0x01
    private const val DFU_PROTO_MODE = 0x02

    private const val DFU_DETACH = 0x00
    private const val DFU_DNLOAD = 0x01
    private const val DFU_GETSTATUS = 0x03
    private const val DFU_CLRSTATUS = 0x04

    private const val STATE_DFU_IDLE = 2
    private const val STATE_DFU_DNLOAD_SYNC = 3
    private const val STATE_DFU_DNBUSY = 4
    private const val STATE_DFU_DNLOAD_IDLE = 5
    private const val STATE_DFU_MANIFEST_SYNC = 6
    private const val STATE_DFU_MANIFEST = 7
    private const val STATE_DFU_MANIFEST_WAIT_RESET = 8
    private const val STATE_DFU_ERROR = 10

    private const val TRANSFER_SIZE = 64

    fun isDfuBootloader(device: UsbDevice): Boolean {
      if (device.vendorId == ARDUINO_VID && device.productId == MINIMA_DFU_PID) return true
      return findDfuInterface(device, DFU_PROTO_MODE) != null
    }

    fun findDfuInterface(device: UsbDevice, protocolFilter: Int? = null): UsbInterface? {
      for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (intf.interfaceClass == USB_CLASS_APP_SPEC && intf.interfaceSubclass == USB_SUBCLASS_DFU) {
          if (protocolFilter == null || intf.interfaceProtocol == protocolFilter) {
            return intf
          }
        }
      }
      return null
    }
  }

  data class DfuStatus(
    val status: Int,
    val pollTimeoutMs: Int,
    val state: Int
  )

  data class FlashResult(
    val success: Boolean,
    val message: String
  )

  fun triggerBootloaderDetach(device: UsbDevice, connection: UsbDeviceConnection): Boolean {
    val runtimeIntf = findDfuInterface(device, DFU_PROTO_RUNTIME) ?: findDfuInterface(device, null)
    if (runtimeIntf != null) {
      connection.claimInterface(runtimeIntf, true)
      val res = connection.controlTransfer(0x21, DFU_DETACH, 1000, runtimeIntf.id, null, 0, 1000)
      try {
        connection.releaseInterface(runtimeIntf)
      } catch (_: Exception) {
      }
      if (res >= 0) return true
    }
    return false
  }

  fun flash(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    firmwareBin: ByteArray,
    onProgress: (Float, String) -> Unit
  ): FlashResult {
    val dfuIntf = findDfuInterface(device, DFU_PROTO_MODE)
      ?: findDfuInterface(device, null)
      ?: return FlashResult(false, "No USB DFU interface found on device")

    if (!connection.claimInterface(dfuIntf, true)) {
      return FlashResult(false, "Failed to claim USB DFU interface")
    }
    connection.setInterface(dfuIntf)

    try {
      val ifNum = dfuIntf.id
      onProgress(0.05f, "Checking DFU bootloader status...")

      var st = getStatus(connection, ifNum)
      if (st != null && st.state == STATE_DFU_ERROR) {
        clearStatus(connection, ifNum)
        st = getStatus(connection, ifNum)
      }

      val totalBlocks = (firmwareBin.size + TRANSFER_SIZE - 1) / TRANSFER_SIZE
      var offset = 0
      var blockNum = 0

      while (offset < firmwareBin.size) {
        val len = min(TRANSFER_SIZE, firmwareBin.size - offset)
        val chunk = firmwareBin.copyOfRange(offset, offset + len)

        val sent = connection.controlTransfer(
          0x21,
          DFU_DNLOAD,
          blockNum and 0xFFFF,
          ifNum,
          chunk,
          chunk.size,
          3000
        )
        if (sent < 0) {
          return FlashResult(false, "DFU_DNLOAD failed at block $blockNum ($offset bytes)")
        }

        val ok = waitUntilIdle(connection, ifNum)
        if (!ok) {
          return FlashResult(false, "DFU device reported error during block $blockNum")
        }

        offset += len
        blockNum++

        if (blockNum % 8 == 0 || offset >= firmwareBin.size) {
          val frac = 0.08f + 0.85f * (offset.toFloat() / firmwareBin.size.toFloat())
          onProgress(frac, "Flashing UNO R4 Minima: $offset / ${firmwareBin.size} bytes...")
        }
      }

      onProgress(0.95f, "Finalizing DFU manifest & rebooting UNO R4 Minima...")
      // Zero-length DNLOAD signals end of transfer / start manifest
      val manifestRes = connection.controlTransfer(0x21, DFU_DNLOAD, blockNum and 0xFFFF, ifNum, null, 0, 3000)
      android.util.Log.d("NanoHub", "DFU zero-length DNLOAD res=$manifestRes")

      // Poll manifest status
      for (i in 0 until 20) {
        val mSt = getStatus(connection, ifNum) ?: break
        android.util.Log.d("NanoHub", "DFU manifest poll[$i]: state=${mSt.state} status=${mSt.status} timeout=${mSt.pollTimeoutMs}")
        if (mSt.status != 0) {
          return FlashResult(false, "DFU manifest failed with status ${mSt.status}")
        }
        if (mSt.state == STATE_DFU_IDLE || mSt.state == STATE_DFU_MANIFEST_WAIT_RESET) {
          break
        }
        Thread.sleep(mSt.pollTimeoutMs.coerceIn(20, 250).toLong())
      }

      // Send DFU_DETACH to trigger tud_dfu_detach_cb() -> NVIC_SystemReset() on UNO R4 Minima
      val detachRes = connection.controlTransfer(0x21, DFU_DETACH, 1000, ifNum, null, 0, 500)
      android.util.Log.d("NanoHub", "DFU_DETACH exit res=$detachRes")

      return FlashResult(
        success = true,
        message = "Flashed ${firmwareBin.size} bytes ($totalBlocks DFU blocks) & rebooting UNO R4 Minima!"
      )
    } finally {
      try {
        connection.releaseInterface(dfuIntf)
      } catch (_: Exception) {
      }
    }
  }

  private fun waitUntilIdle(connection: UsbDeviceConnection, ifNum: Int): Boolean {
    for (attempt in 0 until 40) {
      val st = getStatus(connection, ifNum) ?: return false
      if (st.status != 0 || st.state == STATE_DFU_ERROR) {
        clearStatus(connection, ifNum)
        return false
      }
      if (st.state == STATE_DFU_DNLOAD_IDLE || st.state == STATE_DFU_IDLE) {
        return true
      }
      val waitMs = st.pollTimeoutMs.coerceIn(2, 100).toLong()
      Thread.sleep(waitMs)
    }
    return false
  }

  private fun getStatus(connection: UsbDeviceConnection, ifNum: Int): DfuStatus? {
    val buf = ByteArray(6)
    val n = connection.controlTransfer(0xA1, DFU_GETSTATUS, 0, ifNum, buf, 6, 1000)
    if (n < 6) return null
    val status = buf[0].toInt() and 0xFF
    val pollTimeout = (buf[1].toInt() and 0xFF) or
      ((buf[2].toInt() and 0xFF) shl 8) or
      ((buf[3].toInt() and 0xFF) shl 16)
    val state = buf[4].toInt() and 0xFF
    return DfuStatus(status, pollTimeout, state)
  }

  private fun clearStatus(connection: UsbDeviceConnection, ifNum: Int) {
    connection.controlTransfer(0x21, DFU_CLRSTATUS, 0, ifNum, null, 0, 500)
  }
}
