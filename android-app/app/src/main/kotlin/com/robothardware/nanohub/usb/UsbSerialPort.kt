package com.robothardware.nanohub.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlin.math.min

enum class ChipType(val label: String) {
  UNO_R4_MINIMA("Arduino UNO R4 Minima (RA4M1)"),
  RP2040("Raspberry Pi RP2040 (USB CDC)"),
  CH340("WCH CH340/CH341"),
  FTDI("FTDI FT232R"),
  CP210X("Silicon Labs CP210x"),
  CDC_ACM("USB CDC-ACM"),
  UNKNOWN("Generic USB Serial")
}

class UsbSerialPort(
  val device: UsbDevice,
  val connection: UsbDeviceConnection
) {
  val chipType: ChipType = detectChipType(device)
  private var claimedInterfaces = mutableListOf<UsbInterface>()
  private var readEndpoint: UsbEndpoint? = null
  private var writeEndpoint: UsbEndpoint? = null
  private var dtrState = false
  private var rtsState = false
  private var currentBaudRate = 115200

  companion object {
    fun detectChipType(device: UsbDevice): ChipType {
      return when (device.vendorId) {
        0x1A86 -> ChipType.CH340
        0x0403 -> ChipType.FTDI
        0x10C4 -> ChipType.CP210X
        0x2341 -> if (device.productId == UsbDfuFlasher.MINIMA_SKETCH_PID ||
          device.productId == UsbDfuFlasher.MINIMA_DFU_PID
        ) {
          ChipType.UNO_R4_MINIMA
        } else {
          ChipType.CDC_ACM
        }
        0x2E8A -> ChipType.RP2040
        else -> {
          for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
              return ChipType.CDC_ACM
            }
          }
          ChipType.UNKNOWN
        }
      }
    }

    fun isSupportedDevice(device: UsbDevice): Boolean {
      if (device.vendorId in setOf(0x2341, 0x2E8A, 0x1A86, 0x0403, 0x10C4, 0x067B)) return true
      for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
          intf.interfaceClass == UsbConstants.USB_CLASS_COMM ||
          intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
        ) {
          return true
        }
      }
      return false
    }
  }

  fun open(baudRate: Int = 115200): Boolean {
    for (i in 0 until device.interfaceCount) {
      val intf = device.getInterface(i)
      if (connection.claimInterface(intf, true)) {
        claimedInterfaces.add(intf)
      }
      for (j in 0 until intf.endpointCount) {
        val ep = intf.getEndpoint(j)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
          if (ep.direction == UsbConstants.USB_DIR_IN) {
            readEndpoint = ep
          } else {
            writeEndpoint = ep
          }
        }
      }
    }

    if (readEndpoint == null || writeEndpoint == null) {
      close()
      return false
    }

    initChip()
    setBaudRate(baudRate)
    setDtrRts(true, true)
    return true
  }

  private fun initChip() {
    when (chipType) {
      ChipType.CH340 -> {
        // CH340 standard initialization sequence
        connection.controlTransfer(0x40, 0xA1, 0, 0, null, 0, 500)
        connection.controlTransfer(0x40, 0x9A, 0x2518, 0x00C3, null, 0, 500)
        connection.controlTransfer(0x40, 0xA1, 0x501F, 0xD90A, null, 0, 500)
      }
      ChipType.FTDI -> {
        // FTDI Reset + 8N1
        connection.controlTransfer(0x40, 0x00, 0x00, 0, null, 0, 500)
        connection.controlTransfer(0x40, 0x00, 0x01, 0, null, 0, 500)
        connection.controlTransfer(0x40, 0x00, 0x02, 0, null, 0, 500)
        connection.controlTransfer(0x40, 0x04, 0x0008, 0, null, 0, 500)
      }
      ChipType.CP210X -> {
        // Enable UART + 8N1
        connection.controlTransfer(0x41, 0x00, 0x01, 0, null, 0, 500)
        connection.controlTransfer(0x41, 0x03, 0x0800, 0, null, 0, 500)
      }
      ChipType.CDC_ACM, ChipType.UNO_R4_MINIMA, ChipType.RP2040, ChipType.UNKNOWN -> {
        // Handled in setBaudRate / setDtrRts
      }
    }
  }

  fun setBaudRate(baudRate: Int) {
    currentBaudRate = baudRate
    when (chipType) {
      ChipType.CH340 -> {
        // CH340 baud rate registers (0x1312 and 0x0F2C)
        val (a, b) = when (baudRate) {
          9600 -> Pair(0xB202, 0x0038)
          19200 -> Pair(0xD902, 0x001C)
          38400 -> Pair(0x6403, 0x000E)
          57600 -> Pair(0x9803, 0x0010)
          115200 -> Pair(0xCC03, 0x0008)
          230400 -> Pair(0xE603, 0x0004)
          else -> Pair(0xCC03, 0x0008)
        }
        connection.controlTransfer(0x40, 0x9A, 0x1312, a, null, 0, 500)
        connection.controlTransfer(0x40, 0x9A, 0x0F2C, b, null, 0, 500)
      }
      ChipType.FTDI -> {
        // FT232R 3MHz base clock divisor
        val value = when (baudRate) {
          9600 -> 0x4138
          19200 -> 0x809C
          38400 -> 0xC04E
          57600 -> 0x0034
          115200 -> 0x001A
          else -> 3000000 / baudRate
        }
        connection.controlTransfer(0x40, 0x03, value, 0, null, 0, 500)
      }
      ChipType.CP210X -> {
        val data = byteArrayOf(
          (baudRate and 0xFF).toByte(),
          ((baudRate shr 8) and 0xFF).toByte(),
          ((baudRate shr 16) and 0xFF).toByte(),
          ((baudRate shr 24) and 0xFF).toByte()
        )
        connection.controlTransfer(0x41, 0x1E, 0, 0, data, data.size, 500)
      }
      ChipType.CDC_ACM, ChipType.UNO_R4_MINIMA, ChipType.RP2040, ChipType.UNKNOWN -> {
        val data = byteArrayOf(
          (baudRate and 0xFF).toByte(),
          ((baudRate shr 8) and 0xFF).toByte(),
          ((baudRate shr 16) and 0xFF).toByte(),
          ((baudRate shr 24) and 0xFF).toByte(),
          0, // 1 stop bit
          0, // No parity
          8  // 8 data bits
        )
        connection.controlTransfer(0x21, 0x20, 0, 0, data, data.size, 500)
      }
    }
  }

  fun setDtrRts(dtr: Boolean, rts: Boolean) {
    dtrState = dtr
    rtsState = rts
    when (chipType) {
      ChipType.CH340 -> {
        val flags = (if (dtr) (1 shl 5) else 0) or (if (rts) (1 shl 6) else 0)
        connection.controlTransfer(0x40, 0xA4, flags.inv() and 0xFFFF, 0, null, 0, 500)
      }
      ChipType.FTDI -> {
        val value = (if (dtr) 1 else 0) or 0x0100 or (if (rts) 2 else 0) or 0x0200
        connection.controlTransfer(0x40, 0x01, value, 0, null, 0, 500)
      }
      ChipType.CP210X -> {
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0) or 0x0300
        connection.controlTransfer(0x41, 0x07, value, 0, null, 0, 500)
      }
      ChipType.CDC_ACM, ChipType.UNO_R4_MINIMA, ChipType.RP2040, ChipType.UNKNOWN -> {
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        connection.controlTransfer(0x21, 0x22, value, 0, null, 0, 500)
      }
    }
  }

  fun pulseReset() {
    // Pulse DTR/RTS to trigger Arduino Nano 100nF reset capacitor into Optiboot/ATmegaBOOT
    setDtrRts(false, false)
    Thread.sleep(50)
    setDtrRts(true, true)
    Thread.sleep(50)
    setDtrRts(false, false)
    Thread.sleep(50)
    setDtrRts(true, true)
  }

  fun drainInput() {
    val buf = ByteArray(256)
    for (i in 0 until 5) {
      val n = read(buf, 20)
      if (n <= 0) break
    }
  }

  fun write(data: ByteArray, timeoutMs: Int = 500): Int {
    val ep = writeEndpoint ?: return -1
    var offset = 0
    while (offset < data.size) {
      val chunkLen = min(data.size - offset, ep.maxPacketSize.coerceAtLeast(64))
      val chunk = data.copyOfRange(offset, offset + chunkLen)
      val written = connection.bulkTransfer(ep, chunk, chunk.size, timeoutMs)
      if (written < 0) return if (offset > 0) offset else written
      offset += written
    }
    return offset
  }

  fun read(dest: ByteArray, timeoutMs: Int = 150): Int {
    val ep = readEndpoint ?: return -1
    val packetSize = ep.maxPacketSize.coerceAtLeast(64)
    val raw = ByteArray(dest.size.coerceAtLeast(packetSize))
    val n = connection.bulkTransfer(ep, raw, raw.size, timeoutMs)
    if (n <= 0) return 0

    if (chipType == ChipType.FTDI) {
      // FTDI prepends 2 modem status bytes to every maxPacketSize (64-byte) USB packet
      var outIdx = 0
      var inIdx = 0
      while (inIdx < n) {
        val pktEnd = min(inIdx + packetSize, n)
        val payloadStart = inIdx + 2
        if (payloadStart < pktEnd) {
          val copyLen = min(pktEnd - payloadStart, dest.size - outIdx)
          System.arraycopy(raw, payloadStart, dest, outIdx, copyLen)
          outIdx += copyLen
        }
        inIdx += packetSize
      }
      return outIdx
    } else {
      val copyLen = min(n, dest.size)
      System.arraycopy(raw, 0, dest, 0, copyLen)
      return copyLen
    }
  }

  fun close() {
    for (intf in claimedInterfaces) {
      try {
        connection.releaseInterface(intf)
      } catch (_: Exception) {
      }
    }
    claimedInterfaces.clear()
    try {
      connection.close()
    } catch (_: Exception) {
    }
  }
}
