package com.robothardware.nanohub.usb

object IntelHexParser {
  private const val MAX_FLASH_BYTES = 32768
  const val PAGE_SIZE = 128 // ATmega328P flash page size in bytes (64 words)

  fun parse(hexContent: String): ByteArray {
    val memory = ByteArray(MAX_FLASH_BYTES) { 0xFF.toByte() }
    var maxAddress = 0
    var baseAddress = 0

    for (rawLine in hexContent.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || !line.startsWith(":")) continue

      val byteCount = line.substring(1, 3).toInt(16)
      val address = line.substring(3, 7).toInt(16)
      val recordType = line.substring(7, 9).toInt(16)

      when (recordType) {
        0x00 -> { // Data record
          for (i in 0 until byteCount) {
            val b = line.substring(9 + i * 2, 11 + i * 2).toInt(16).toByte()
            val targetAddr = baseAddress + address + i
            if (targetAddr in 0 until MAX_FLASH_BYTES) {
              memory[targetAddr] = b
              if (targetAddr + 1 > maxAddress) {
                maxAddress = targetAddr + 1
              }
            }
          }
        }
        0x01 -> break // EOF
        0x02 -> { // Extended Segment Address
          val seg = line.substring(9, 13).toInt(16)
          baseAddress = seg shl 4
        }
        0x04 -> { // Extended Linear Address
          val upper = line.substring(9, 13).toInt(16)
          baseAddress = upper shl 16
        }
      }
    }

    if (maxAddress == 0) {
      throw IllegalArgumentException("HEX file contains no flash data records.")
    }

    val paddedSize = ((maxAddress + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE
    return memory.copyOfRange(0, paddedSize.coerceAtMost(MAX_FLASH_BYTES))
  }
}
