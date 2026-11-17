#!/usr/bin/env python3
"""
Robot Hardware Host Server
- WebSocket hub (/ws) for Android OTG Hub <-> Host Compute & Dashboard
- WebRTC receiver (aiortc) for live camera frames from Pixel 4a
- Firmware compiler & OTA push over WebSocket + HTTP (/firmware/<name>)
- Web Dashboard (/) & REST API (/api/...)
"""

import asyncio
import json
import logging
import os
import subprocess
import time
from pathlib import Path
from typing import Any, Optional

from aiohttp import web, WSMsgType, ClientSession, ClientTimeout
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCIceCandidate, RTCConfiguration, RTCIceServer, VideoStreamTrack
from aiortc.sdp import candidate_from_sdp
from av import VideoFrame
import numpy as np
from PIL import Image
import io

from vision import VisionProcessor

logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s")
log = logging.getLogger("host_server")

ROOT_DIR = Path(__file__).resolve().parent.parent
SERVER_DIR = Path(__file__).resolve().parent
FIRMWARE_CACHE = SERVER_DIR / "firmware_cache"
FIRMWARE_CACHE.mkdir(parents=True, exist_ok=True)

ARDUINO_CLI = "/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli"
SKETCH_DIR = ROOT_DIR / "arduino-firmware" / "nano_controller"

HOST_PORT = int(os.environ.get("HOST_PORT", "8765"))
PUBLIC_HOST = os.environ.get("PUBLIC_HOST", "peters-macbook-pro-2.tail26777b.ts.net")
ANDROID_WEBHOOK_URL = os.environ.get(
    "ANDROID_WEBHOOK_URL", "http://pixel-4a.tail26777b.ts.net:8766/connect"
)


class HostHub:
    def __init__(self) -> None:
        self.android_ws: Optional[web.WebSocketResponse] = None
        self.dashboards: set[web.WebSocketResponse] = set()
        self.pcs: set[RTCPeerConnection] = set()
        self.android_pc: Optional[RTCPeerConnection] = None
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.vision = VisionProcessor()

        self.latest_annotated_rgb: Optional[np.ndarray] = None
        self.frame_count: int = 0
        self.fps: float = 0.0
        self.last_fps_ts: float = time.monotonic()
        self.last_fps_frames: int = 0
        self.video_width: int = 0
        self.video_height: int = 0
        self.mean_luma: float = 0.0
        self.last_android_status: dict[str, Any] = {}

    async def broadcast_dashboards(self, payload: dict[str, Any]) -> None:
        if not self.dashboards:
            return
        msg = json.dumps(payload)
        dead = []
        for ws in list(self.dashboards):
            try:
                await ws.send_str(msg)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.dashboards.discard(ws)

    async def send_to_android(self, payload: dict[str, Any]) -> bool:
        if self.android_ws is None or self.android_ws.closed:
            return False
        await self.android_ws.send_str(json.dumps(payload))
        return True

    def on_video_frame(self, img_rgb: np.ndarray) -> None:
        """
        Compute hook called for every decoded WebRTC video frame from the Pixel 4a.
        Delegates to VisionProcessor in server/vision.py.
        """
        self.frame_count += 1
        h, w, _ = img_rgb.shape
        self.video_height = h
        self.video_width = w
        self.mean_luma = float(img_rgb.mean())

        annotated_rgb, serial_cmd = self.vision.process(img_rgb)
        self.latest_annotated_rgb = annotated_rgb
        if serial_cmd and self.loop is not None:
            asyncio.run_coroutine_threadsafe(
                self.send_to_android({"type": "serial_cmd", "cmd": serial_cmd}),
                self.loop,
            )

        now = time.monotonic()
        dt = now - self.last_fps_ts
        if dt >= 1.0:
            self.fps = (self.frame_count - self.last_fps_frames) / dt
            self.last_fps_ts = now
            self.last_fps_frames = self.frame_count


class AnnotatedVideoTrack(VideoStreamTrack):
    """Emits the latest OpenCV-annotated RGB frame over WebRTC to the browser dashboard."""
    kind = "video"

    async def recv(self) -> VideoFrame:
        pts, time_base = await self.next_timestamp()
        rgb = hub.latest_annotated_rgb
        if rgb is None:
            rgb = np.zeros((480, 360, 3), dtype=np.uint8)
        frame = VideoFrame.from_ndarray(rgb, format="rgb24")
        frame.pts = pts
        frame.time_base = time_base
        return frame


hub = HostHub()


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(max_msg_size=16 * 1024 * 1024)
    await ws.prepare(request)
    role = "unknown"
    log.info("WebSocket client connected from %s", request.remote)

    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                data = json.loads(msg.data)
            except json.JSONDecodeError:
                continue

            mtype = data.get("type", "")

            if mtype == "hello":
                role = data.get("role", "dashboard")
                if role == "android":
                    hub.android_ws = ws
                    log.info("Android client registered: %s", data.get("device", "unknown"))
                    await hub.broadcast_dashboards({"type": "android_connected", "connected": True})
                else:
                    hub.dashboards.add(ws)
                    await ws.send_str(json.dumps({
                        "type": "android_connected",
                        "connected": hub.android_ws is not None and not hub.android_ws.closed,
                        "status": hub.last_android_status,
                    }))

            elif mtype == "serial_cmd":
                ok = await hub.send_to_android(data)
                if not ok and role != "android":
                    await ws.send_str(json.dumps({"type": "error", "msg": "Android client not connected"}))

            elif mtype in ("serial_rx", "status", "flash_progress", "log"):
                if mtype == "status":
                    hub.last_android_status = data
                await hub.broadcast_dashboards(data)

            elif mtype == "webrtc_offer":
                await handle_webrtc_offer(ws, data)

            elif mtype == "webrtc_ice":
                await handle_webrtc_ice(data)

    finally:
        if role == "android" and hub.android_ws is ws:
            hub.android_ws = None
            log.info("Android client disconnected")
            await hub.broadcast_dashboards({"type": "android_connected", "connected": False})
            if hub.android_pc is not None:
                await hub.android_pc.close()
                hub.pcs.discard(hub.android_pc)
                hub.android_pc = None
        else:
            hub.dashboards.discard(ws)

    return ws


async def handle_webrtc_offer(ws: web.WebSocketResponse, data: dict[str, Any]) -> None:
    if hub.android_pc is not None:
        await hub.android_pc.close()
        hub.pcs.discard(hub.android_pc)
        hub.android_pc = None

    config = RTCConfiguration(iceServers=[RTCIceServer(urls=["stun:stun.l.google.com:19302"])])
    pc = RTCPeerConnection(configuration=config)
    hub.pcs.add(pc)
    hub.android_pc = pc

    @pc.on("connectionstatechange")
    async def on_state_change() -> None:
        log.info("WebRTC connection state: %s", pc.connectionState)
        await hub.broadcast_dashboards({"type": "webrtc_state", "state": pc.connectionState})
        if pc.connectionState in ("failed", "closed"):
            await pc.close()
            hub.pcs.discard(pc)

    @pc.on("track")
    def on_track(track) -> None:
        log.info("WebRTC track received: kind=%s", track.kind)
        if track.kind == "video":
            asyncio.create_task(consume_video_track(track))

    offer = RTCSessionDescription(sdp=data["sdp"], type=data.get("sdpType", "offer"))
    await pc.setRemoteDescription(offer)
    answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)

    await ws.send_str(json.dumps({
        "type": "webrtc_answer",
        "sdp": pc.localDescription.sdp,
        "sdpType": pc.localDescription.type,
    }))
    log.info("Sent WebRTC answer to Android")


async def handle_webrtc_ice(data: dict[str, Any]) -> None:
    pc = hub.android_pc
    if pc is None:
        return
    cand_str = data.get("candidate")
    if not cand_str:
        return
    try:
        if cand_str.startswith("candidate:"):
            cand_str = cand_str[len("candidate:"):]
        cand = candidate_from_sdp(cand_str)
        cand.sdpMid = data.get("sdpMid")
        cand.sdpMLineIndex = data.get("sdpMLineIndex")
        await pc.addIceCandidate(cand)
    except Exception as e:
        log.warning("Failed to add ICE candidate: %s", e)


async def consume_video_track(track) -> None:
    log.info("Starting WebRTC video frame compute loop")
    try:
        while True:
            frame = await track.recv()
            img_rgb = frame.to_ndarray(format="rgb24")
            hub.on_video_frame(img_rgb)
    except Exception as e:
        log.info("Video track ended: %s", e)


async def handle_api_serial(request: web.Request) -> web.Response:
    body = await request.json()
    cmd = str(body.get("cmd", "")).strip()
    if not cmd:
        return web.json_response({"ok": False, "error": "Missing cmd"}, status=400)
    ok = await hub.send_to_android({"type": "serial_cmd", "cmd": cmd})
    return web.json_response({"ok": ok, "cmd": cmd})


async def handle_api_compile_push(request: web.Request) -> web.Response:
    body = await request.json()
    version = str(body.get("version", "1.4.0"))
    blink_ms = int(body.get("blink_ms", 250))
    auto_flash = bool(body.get("auto_flash", True))

    out_dir = ROOT_DIR / "arduino-firmware" / "dist" / f"unor4_v{version}"
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        ARDUINO_CLI, "compile",
        "--fqbn", "arduino:renesas_uno:minima",
        "--build-property", f'compiler.cpp.extra_flags=-DFW_VERSION="{version}" -DDEFAULT_BLINK_MS={blink_ms}',
        "--output-dir", str(out_dir),
        str(SKETCH_DIR),
    ]
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        return web.json_response({
            "ok": False,
            "error": stderr.decode(errors="replace") or stdout.decode(errors="replace")
        }, status=500)

    bin_path = out_dir / "nano_controller.ino.bin"
    fw_name = f"unor4_v{version}_blink_{blink_ms}ms.bin"
    cached = FIRMWARE_CACHE / fw_name
    cached.write_bytes(bin_path.read_bytes())

    fw_url = f"http://{PUBLIC_HOST}:{HOST_PORT}/firmware/{fw_name}"
    sent = await hub.send_to_android({
        "type": "flash_firmware",
        "name": fw_name,
        "url": fw_url,
        "autoFlash": auto_flash,
    })
    return web.json_response({
        "ok": sent,
        "name": fw_name,
        "size": cached.stat().st_size,
        "url": fw_url,
        "auto_flash": auto_flash,
    })


async def handle_api_upload_firmware(request: web.Request) -> web.Response:
    reader = await request.multipart()
    field = await reader.next()
    if field is None or field.name != "file":
        return web.json_response({"ok": False, "error": "Expected multipart field 'file'"}, status=400)
    filename = Path(field.filename or f"upload_{int(time.time())}.bin").name
    data = await field.read()
    cached = FIRMWARE_CACHE / filename
    cached.write_bytes(data)

    auto_flash = request.query.get("auto_flash", "true").lower() == "true"
    fw_url = f"http://{PUBLIC_HOST}:{HOST_PORT}/firmware/{filename}"
    sent = await hub.send_to_android({
        "type": "flash_firmware",
        "name": filename,
        "url": fw_url,
        "autoFlash": auto_flash,
    })
    return web.json_response({"ok": sent, "name": filename, "size": len(data), "url": fw_url})


async def handle_firmware_download(request: web.Request) -> web.StreamResponse:
    name = Path(request.match_info["name"]).name
    path = FIRMWARE_CACHE / name
    if not path.is_file():
        raise web.HTTPNotFound(text="Firmware not found")
    return web.FileResponse(path)


async def handle_frame_jpg(request: web.Request) -> web.Response:
    rgb = hub.latest_annotated_rgb
    if rgb is None:
        return web.Response(status=404, text="No frame yet")
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, format="JPEG", quality=80)
    return web.Response(body=buf.getvalue(), content_type="image/jpeg")


async def handle_webrtc_viewer(request: web.Request) -> web.Response:
    body = await request.json()
    offer = RTCSessionDescription(sdp=body["sdp"], type=body["type"])
    pc = RTCPeerConnection()
    hub.pcs.add(pc)

    @pc.on("connectionstatechange")
    async def on_state_change() -> None:
        if pc.connectionState in ("failed", "closed"):
            await pc.close()
            hub.pcs.discard(pc)

    pc.addTrack(AnnotatedVideoTrack())
    await pc.setRemoteDescription(offer)
    answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)

    while pc.iceGatheringState != "complete":
        await asyncio.sleep(0.02)

    return web.json_response({
        "sdp": pc.localDescription.sdp,
        "type": pc.localDescription.type,
    })


async def handle_metrics(request: web.Request) -> web.Response:
    return web.json_response({
        "android_connected": hub.android_ws is not None and not hub.android_ws.closed,
        "frame_count": hub.frame_count,
        "fps": round(hub.fps, 1),
        "width": hub.video_width,
        "height": hub.video_height,
        "mean_luma": round(hub.mean_luma, 1),
        "android_status": hub.last_android_status,
    })


DASHBOARD_HTML = """<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <title>Robot Hardware Host Hub</title>
  <style>
    body { font-family: system-ui, sans-serif; background: #111; color: #eee; margin: 20px; }
    .grid { display: grid; grid-template-columns: minmax(320px, 1fr) minmax(360px, 1.3fr); gap: 16px; max-width: 1280px; }
    @media (max-width: 780px) { .grid { grid-template-columns: 1fr; } }
    .card { background: #1c1c1e; border-radius: 12px; padding: 16px; border: 1px solid #333; min-width: 0; }
    video { width: 100%; border-radius: 8px; background: #000; min-height: 280px; max-height: 480px; object-fit: contain; display: block; }
    button { background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 8px 12px; cursor: pointer; margin: 2px; }
    input { background: #2c2c2e; color: #fff; border: 1px solid #444; border-radius: 6px; padding: 7px 10px; }
    pre { background: #000; padding: 10px; border-radius: 8px; height: 240px; overflow-y: auto; overflow-x: auto; white-space: pre-wrap; word-break: break-word; font-size: 12px; margin-top: 12px; }
  </style>
</head>
<body>
  <h2>Robot Hardware Host Hub (WebRTC + WebSocket + OTA DFU)</h2>
  <div style="display:flex; align-items:center; gap:12px; margin-bottom:12px;">
    <span id="conn">Connecting...</span>
    <button onclick="triggerWebhook()">Connect Pixel 4a (Webhook)</button>
  </div>
  <div class="grid">
    <div class="card">
      <h3>Live WebRTC Camera Feed (OpenCV Annotated)</h3>
      <video id="cam" autoplay playsinline muted></video>
      <p id="stats">FPS: 0 | Resolution: - | Mean Luma: -</p>
    </div>
    <div class="card">
      <h3>Arduino UNO R4 Minima Control & Firmware</h3>
      <div>
        <button onclick="sendCmd('PING')">PING</button>
        <button onclick="sendCmd('INFO')">INFO</button>
        <button onclick="sendCmd('LED ON')">LED ON</button>
        <button onclick="sendCmd('LED OFF')">LED OFF</button>
        <button onclick="sendCmd('BLINK 100')">BLINK 100ms</button>
        <button onclick="sendCmd('BLINK 500')">BLINK 500ms</button>
        <button onclick="sendCmd('STREAM ON')">STREAM ON</button>
        <button onclick="sendCmd('STREAM OFF')">STREAM OFF</button>
      </div>
      <div style="margin-top:10px;">
        <input id="cmdInput" placeholder="Custom command..." style="width:70%" />
        <button onclick="sendCmd(document.getElementById('cmdInput').value)">Send</button>
      </div>
      <div style="margin-top:12px;">
        <strong>Compile & Flash OTA:</strong>
        <input id="ver" value="1.4.0" style="width:70px" />
        <input id="bms" value="200" style="width:70px" /> ms
        <button onclick="compileAndPush()">Compile & Flash UNO R4</button>
      </div>
      <pre id="log"></pre>
    </div>
  </div>
  <script>
    const logEl = document.getElementById('log');
    function addLog(t) { logEl.textContent += t + '\\n'; logEl.scrollTop = logEl.scrollHeight; }
    const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
    ws.onopen = () => ws.send(JSON.stringify({type:'hello', role:'dashboard'}));
    ws.onmessage = (e) => {
      const d = JSON.parse(e.data);
      if (d.type === 'android_connected') {
        document.getElementById('conn').textContent = d.connected ? 'Pixel 4a: CONNECTED' : 'Pixel 4a: DISCONNECTED';
      } else if (d.type === 'serial_rx') {
        addLog('RX << ' + d.line);
      } else if (d.type === 'flash_progress') {
        addLog('FLASH: ' + d.status);
      }
    };
    async function triggerWebhook() {
      addLog('Sending webhook to Pixel 4a...');
      const r = await fetch('/api/webhook/connect', {method:'POST'});
      addLog('Webhook: ' + JSON.stringify(await r.json()));
    }
    async function sendCmd(cmd) {
      if (!cmd) return;
      addLog('TX >> ' + cmd);
      await fetch('/api/serial', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({cmd})});
    }
    async function compileAndPush() {
      const version = document.getElementById('ver').value;
      const blink_ms = parseInt(document.getElementById('bms').value, 10);
      addLog('Compiling v' + version + ' (' + blink_ms + 'ms)...');
      const r = await fetch('/api/firmware/compile', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({version, blink_ms, auto_flash:true})});
      addLog('Compile/Push: ' + JSON.stringify(await r.json()));
    }
    async function startViewerWebRtc() {
      const pc = new RTCPeerConnection();
      pc.addTransceiver('video', { direction: 'recvonly' });
      pc.ontrack = (ev) => {
        const video = document.getElementById('cam');
        if (video.srcObject !== ev.streams[0]) {
          video.srcObject = ev.streams[0];
        }
      };
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      await new Promise((resolve) => {
        if (pc.iceGatheringState === 'complete') return resolve();
        pc.onicegatheringstatechange = () => {
          if (pc.iceGatheringState === 'complete') resolve();
        };
      });
      const resp = await fetch('/api/webrtc/viewer', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ sdp: pc.localDescription.sdp, type: pc.localDescription.type })
      });
      const answer = await resp.json();
      await pc.setRemoteDescription(answer);
    }
    startViewerWebRtc().catch((e) => addLog('Viewer WebRTC error: ' + e));

    setInterval(async () => {
      const m = await (await fetch('/api/metrics')).json();
      document.getElementById('stats').textContent = `Frames: ${m.frame_count} | FPS: ${m.fps} | ${m.width}x${m.height} | Mean Luma: ${m.mean_luma}`;
    }, 500);
  </script>
</body>
</html>
"""


async def trigger_android_webhook(start_webrtc: bool = True) -> dict[str, Any]:
    payload = {"host": f"{PUBLIC_HOST}:{HOST_PORT}", "startWebRtc": start_webrtc}
    try:
        timeout = ClientTimeout(total=4)
        async with ClientSession(timeout=timeout) as session:
            async with session.post(ANDROID_WEBHOOK_URL, json=payload) as resp:
                text = await resp.text()
                log.info("Android webhook (%s) -> %s %s", ANDROID_WEBHOOK_URL, resp.status, text)
                return {"ok": resp.status == 200, "status": resp.status, "response": text}
    except Exception as e:
        log.warning("Android webhook failed (%s): %s", ANDROID_WEBHOOK_URL, e)
        return {"ok": False, "error": str(e)}


async def handle_api_webhook_connect(request: web.Request) -> web.Response:
    res = await trigger_android_webhook(start_webrtc=True)
    return web.json_response(res)


async def handle_index(request: web.Request) -> web.Response:
    return web.Response(text=DASHBOARD_HTML, content_type="text/html")


async def on_startup(app: web.Application) -> None:
    hub.loop = asyncio.get_running_loop()
    async def _delayed_webhook() -> None:
        await asyncio.sleep(0.5)
        await trigger_android_webhook(start_webrtc=True)
    asyncio.create_task(_delayed_webhook())


async def on_shutdown(app: web.Application) -> None:
    coros = [pc.close() for pc in hub.pcs]
    await asyncio.gather(*coros, return_exceptions=True)
    hub.pcs.clear()


def main() -> None:
    app = web.Application()
    app.on_startup.append(on_startup)
    app.on_shutdown.append(on_shutdown)
    app.router.add_get("/", handle_index)
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/api/metrics", handle_metrics)
    app.router.add_get("/api/frame.jpg", handle_frame_jpg)
    app.router.add_post("/api/webrtc/viewer", handle_webrtc_viewer)
    app.router.add_post("/api/serial", handle_api_serial)
    app.router.add_post("/api/webhook/connect", handle_api_webhook_connect)
    app.router.add_post("/api/firmware/compile", handle_api_compile_push)
    app.router.add_post("/api/firmware/upload", handle_api_upload_firmware)
    app.router.add_get("/firmware/{name}", handle_firmware_download)

    log.info("Starting Robot Hardware Host Server on http://0.0.0.0:%d", HOST_PORT)
    web.run_app(app, host="0.0.0.0", port=HOST_PORT)


if __name__ == "__main__":
    main()
