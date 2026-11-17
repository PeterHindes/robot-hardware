#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.3.0}"
BLINK_MS="${2:-250}"
AUTO_FLASH="${3:-false}"
BOARD="${BOARD:-unor4}" # "unor4" (default) or "nano"
ADB_DEVICE="${ADB_DEVICE:-pixel-4a.tail26777b.ts.net:5555}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARDUINO_CLI="/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli"
SKETCH_DIR="$ROOT_DIR/arduino-firmware/nano_controller"

if [[ "$BOARD" == "unor4" ]]; then
  FQBN="arduino:renesas_uno:minima"
  OUT_DIR="$ROOT_DIR/arduino-firmware/dist/unor4_v${VERSION}"
  SRC_EXT="bin"
  FW_NAME="unor4_v${VERSION}_blink_${BLINK_MS}ms.bin"
else
  FQBN="arduino:avr:nano:cpu=atmega328"
  OUT_DIR="$ROOT_DIR/arduino-firmware/dist/v${VERSION}"
  SRC_EXT="hex"
  FW_NAME="nano_v${VERSION}_blink_${BLINK_MS}ms.hex"
fi

echo "==> [1/3] Compiling $FQBN firmware v${VERSION} (default blink: ${BLINK_MS}ms)..."
mkdir -p "$OUT_DIR"
"$ARDUINO_CLI" compile \
  --fqbn "$FQBN" \
  --build-property "compiler.cpp.extra_flags=-DFW_VERSION=\"${VERSION}\" -DDEFAULT_BLINK_MS=${BLINK_MS}" \
  --output-dir "$OUT_DIR" \
  "$SKETCH_DIR"

FW_FILE="$OUT_DIR/nano_controller.ino.${SRC_EXT}"
cp "$FW_FILE" "$ROOT_DIR/android-app/app/src/main/assets/firmware/$FW_NAME"

echo "==> [2/3] Pushing $FW_NAME to Pixel 4a ($ADB_DEVICE)..."
adb -s "$ADB_DEVICE" shell mkdir -p /sdcard/Android/data/com.robothardware.nanohub/files/firmware
adb -s "$ADB_DEVICE" push "$FW_FILE" "/sdcard/Android/data/com.robothardware.nanohub/files/firmware/$FW_NAME"

echo "==> [3/3] Notifying Nano Hub app on Pixel 4a (auto_flash=${AUTO_FLASH})..."
adb -s "$ADB_DEVICE" shell am broadcast \
  -a com.robothardware.nanohub.OTA_FIRMWARE \
  -p com.robothardware.nanohub \
  --ez auto_flash "$AUTO_FLASH"

echo "✅ Done! Revision $FW_NAME is now live on the Pixel 4a."
