# Robot Hardware Hub

End-to-end robotics control, vision compute, and OTA firmware flashing pipeline connecting an **Arduino UNO R4 Minima**, a **Pixel 4a** (Android USB OTG), and a **Mac Host Server** over **Tailscale**.

## Architecture

- **Arduino UNO R4 Minima** (`arduino:renesas_uno:minima`)
  - Runs [`arduino-firmware/nano_controller/nano_controller.ino`](arduino-firmware/nano_controller/nano_controller.ino) over USB CDC-ACM (`115200` baud).
  - Supports `PING`, `INFO`, `LED ON/OFF/TOGGLE`, `BLINK <ms>`, `STREAM ON/OFF`, `PIN <2-13> <HIGH|LOW|READ>`, and `PWM <pin> <0-255>`.
- **Pixel 4a Android App ("Arduino OTG Hub")** (`android-app/`)
  - **Viewfinder (default tab):** Live WebRTC camera preview with aiming reticle (`ViewfinderTab.kt`).
  - **Controls / Firmware / Serial / Host tabs:** Local USB serial & telemetry, USB DFU 1.1 OTA flasher (`UsbDfuFlasher.kt`), and WebSocket + WebRTC client (`HostLinkManager.kt`).
  - **Webhook (`:8766`):** Listens on `http://pixel-4a.tail26777b.ts.net:8766/connect` so the host server can trigger WebSocket + WebRTC connection automatically.
- **Mac Host Server** (`server/host_server.py`)
  - WebSocket hub (`/ws`) for serial commands, telemetry, and OTA firmware push.
  - WebRTC (`aiortc`) receiver that decodes camera frames into `rgb24` NumPy arrays, passes them to [`server/vision.py`](server/vision.py), and streams the annotated feed over WebRTC (`/api/webrtc/viewer`) to the browser dashboard at `http://localhost:8765/`.

## Quick Start

1. Plug the **Arduino UNO R4 Minima** into your **Pixel 4a** via USB-C OTG (with Tailscale running on both devices).
2. Open **Arduino OTG Hub** on the Pixel 4a.
3. On your Mac, run:
   ```bash
   ./start.sh
   ```
   This starts `server/host_server.py`, triggers the Pixel 4a webhook (`:8766/connect`), and opens `http://localhost:8765/` in your browser.
   - You can also click **Connect Pixel 4a (Webhook)** in the web UI anytime to re-trigger the connection.

## Customizing Vision Processing

Edit `VisionProcessor.process(img_rgb)` in [`server/vision.py`](server/vision.py):
- Receives each WebRTC frame as an `(H, W, 3)` `uint8` **RGB** NumPy array.
- Returns `(annotated_rgb, serial_cmd_or_none)`.
- By default, it runs OpenCV Haar cascade face detection and sends `LED ON` when a face is visible and `LED OFF` after 4 consecutive missed frames.

## Updating Arduino Firmware

- **From the Web Dashboard (`http://localhost:8765/`):** Enter a version and blink interval and click **Compile & Flash UNO R4** (`arduino-cli` compiles the `.bin`, pushes it over WebSocket/HTTP, and the Android app flashes it via USB DFU).
- **From the CLI over Wireless ADB:**
  ```bash
  ./scripts/push_revision.sh 1.4.0 250 false
  ```
- **Recovery Mode (UNO R4 Minima):** Double-tap the physical **RESET** button on the board to force it into `Santiago DFU` (`0x2341:0x0369`) bootloader mode.

## Project Layout

```text
├── start.sh                              # One-command server + browser + webhook launcher
├── server/
│   ├── host_server.py                    # aiohttp + aiortc WebSocket/WebRTC/OTA server (:8765)
│   ├── vision.py                         # OpenCV vision pipeline (face detection -> LED)
│   └── .venv/                            # Python 3.12 uv environment
├── arduino-firmware/
│   └── nano_controller/nano_controller.ino
├── android-app/                          # Jetpack Compose Android OTG + WebRTC + DFU app
├── scripts/
│   └── push_revision.sh                  # Compile & push firmware over ADB
└── flake.nix                             # Optional Nix devShell definition
```
