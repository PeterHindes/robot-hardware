package com.robothardware.nanohub.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import com.robothardware.nanohub.usb.NanoHubController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

data class HostLinkState(
  val hostAddress: String = "peters-macbook-pro-2.tail26777b.ts.net:8765",
  val wsConnected: Boolean = false,
  val wsStatus: String = "Disconnected",
  val webrtcStreaming: Boolean = false,
  val webrtcState: String = "Idle",
  val lastHostMessage: String = "—",
  val webhookPort: Int = 8766
)

class HostLinkManager(
  private val context: Context,
  private val nanoController: NanoHubController
) {
  companion object {
    private const val TAG = "HostLink"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val httpClient = OkHttpClient.Builder()
    .pingInterval(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

  private val _state = MutableStateFlow(HostLinkState())
  val state: StateFlow<HostLinkState> = _state.asStateFlow()

  private var webSocket: WebSocket? = null
  private var webhookServerSocket: ServerSocket? = null
  @Volatile private var autoStartWebRtcOnConnect = false

  // WebRTC objects
  private var eglBase: EglBase? = null
  private var peerConnectionFactory: PeerConnectionFactory? = null
  private var peerConnection: PeerConnection? = null
  private var videoCapturer: CameraVideoCapturer? = null
  private var surfaceTextureHelper: SurfaceTextureHelper? = null
  private var videoSource: VideoSource? = null
  private var videoTrack: VideoTrack? = null
  private var previewRenderer: SurfaceViewRenderer? = null

  fun getOrCreateEglContext(): EglBase.Context {
    val existing = eglBase
    if (existing != null) return existing.eglBaseContext
    val created = EglBase.create()
    eglBase = created
    return created.eglBaseContext
  }

  fun attachPreview(renderer: SurfaceViewRenderer) {
    previewRenderer = renderer
    videoTrack?.addSink(renderer)
  }

  fun detachPreview(renderer: SurfaceViewRenderer) {
    if (previewRenderer === renderer) {
      previewRenderer = null
    }
    try {
      videoTrack?.removeSink(renderer)
    } catch (_: Exception) {
    }
  }

  init {
    nanoController.onSerialLine = { line ->
      sendWsJson(JSONObject().apply {
        put("type", "serial_rx")
        put("line", line)
      })
    }
    nanoController.onFlashProgress = { progress, status, done, success ->
      sendWsJson(JSONObject().apply {
        put("type", "flash_progress")
        put("progress", progress.toDouble())
        put("status", status)
        put("done", done)
        if (success != null) put("success", success)
      })
    }
    startWebhookServer()
  }

  private fun startWebhookServer() {
    scope.launch {
      try {
        val srv = ServerSocket(8766)
        webhookServerSocket = srv
        Log.d(TAG, "Webhook HTTP server listening on port 8766")
        while (!srv.isClosed) {
          val client = srv.accept()
          scope.launch { handleWebhookClient(client) }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Webhook server stopped: ${e.message}")
      }
    }
  }

  private fun handleWebhookClient(client: Socket) {
    client.use { sock ->
      sock.soTimeout = 3000
      val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
      val requestLine = reader.readLine() ?: return
      val parts = requestLine.split(" ")
      val method = parts.getOrNull(0) ?: "GET"
      val path = parts.getOrNull(1) ?: "/"

      var contentLength = 0
      while (true) {
        val hdr = reader.readLine() ?: break
        if (hdr.isEmpty()) break
        if (hdr.startsWith("Content-Length:", ignoreCase = true)) {
          contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: 0
        }
      }

      val bodyStr = if (contentLength > 0) {
        val buf = CharArray(contentLength.coerceAtMost(65536))
        val read = reader.read(buf, 0, buf.size)
        if (read > 0) String(buf, 0, read) else ""
      } else ""

      val respJson = JSONObject()
      when {
        method == "POST" && path == "/connect" -> {
          val json = if (bodyStr.isNotEmpty()) JSONObject(bodyStr) else JSONObject()
          val host = json.optString("host", "").trim()
          val startWebRtc = json.optBoolean("startWebRtc", false)
          if (host.isNotEmpty()) {
            setHostAddress(host)
          }
          autoStartWebRtcOnConnect = startWebRtc
          connectWebSocket()
          respJson.put("ok", true)
          respJson.put("host", _state.value.hostAddress)
          respJson.put("startWebRtc", startWebRtc)
        }
        method == "POST" && path == "/disconnect" -> {
          autoStartWebRtcOnConnect = false
          disconnectWebSocket()
          respJson.put("ok", true)
        }
        else -> {
          val st = _state.value
          val nano = nanoController.state.value
          respJson.put("ok", true)
          respJson.put("wsConnected", st.wsConnected)
          respJson.put("hostAddress", st.hostAddress)
          respJson.put("webrtcState", st.webrtcState)
          respJson.put("arduinoConnected", nano.isConnected)
          respJson.put("fw", nano.telemetry.fwVersion)
        }
      }

      val bodyBytes = respJson.toString().toByteArray(Charsets.UTF_8)
      val out = sock.getOutputStream()
      out.write(
        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n")
          .toByteArray(Charsets.UTF_8)
      )
      out.write(bodyBytes)
      out.flush()
    }
  }

  fun setHostAddress(addr: String) {
    _state.update { it.copy(hostAddress = addr.trim()) }
  }

  fun connectWebSocket() {
    disconnectWebSocket()
    val host = _state.value.hostAddress.trim()
    val url = if (host.startsWith("ws://") || host.startsWith("wss://")) {
      host
    } else {
      "ws://$host/ws"
    }

    _state.update { it.copy(wsStatus = "Connecting to $url...") }
    val req = Request.Builder().url(url).build()
    webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) {
        Log.d(TAG, "WebSocket connected to $url")
        _state.update { it.copy(wsConnected = true, wsStatus = "Connected ($url)") }
        sendWsJson(JSONObject().apply {
          put("type", "hello")
          put("role", "android")
          put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        })
        val st = nanoController.state.value
        sendWsJson(JSONObject().apply {
          put("type", "status")
          put("connected", st.isConnected)
          put("dfuMode", st.isDfuMode)
          put("board", st.chipLabel ?: "Disconnected")
          put("fw", st.telemetry.fwVersion)
        })
        if (autoStartWebRtcOnConnect) {
          autoStartWebRtcOnConnect = false
          val hasCam = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
          ) == PackageManager.PERMISSION_GRANTED
          if (hasCam) {
            startWebRtcStream()
          }
        }
      }

      override fun onMessage(ws: WebSocket, text: String) {
        handleIncomingWsMessage(text)
      }

      override fun onClosing(ws: WebSocket, code: Int, reason: String) {
        ws.close(1000, null)
        _state.update { it.copy(wsConnected = false, wsStatus = "Closed: $reason") }
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "WebSocket failure: ${t.message}")
        _state.update { it.copy(wsConnected = false, wsStatus = "Error: ${t.message}") }
      }
    })
  }

  fun disconnectWebSocket() {
    stopWebRtcStream()
    try {
      webSocket?.close(1000, "User disconnect")
    } catch (_: Exception) {
    }
    webSocket = null
    _state.update { it.copy(wsConnected = false, wsStatus = "Disconnected") }
  }

  private fun handleIncomingWsMessage(text: String) {
    try {
      val json = JSONObject(text)
      val type = json.optString("type")
      _state.update { it.copy(lastHostMessage = type) }

      when (type) {
        "serial_cmd" -> {
          val cmd = json.optString("cmd", "")
          if (cmd.isNotEmpty()) {
            nanoController.sendCommand(cmd)
          }
        }
        "flash_firmware" -> {
          val url = json.optString("url")
          val name = json.optString("name", "ota_firmware.bin")
          val autoFlash = json.optBoolean("autoFlash", true)
          if (url.isNotEmpty()) {
            downloadAndFlashFirmware(url, name, autoFlash)
          }
        }
        "webrtc_answer" -> {
          val sdp = json.optString("sdp")
          val sdpType = json.optString("sdpType", "answer")
          val typeEnum = SessionDescription.Type.fromCanonicalForm(sdpType)
          peerConnection?.setRemoteDescription(object : SimpleSdpObserver("setRemoteDesc") {}, SessionDescription(typeEnum, sdp))
        }
        "webrtc_ice" -> {
          val cand = json.optString("candidate")
          val sdpMid = json.optString("sdpMid")
          val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
          if (cand.isNotEmpty()) {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, cand))
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "WS message parse error: ${e.message}")
    }
  }

  private fun downloadAndFlashFirmware(rawUrl: String, fileName: String, autoFlash: Boolean) {
    scope.launch {
      try {
        val url = when {
          rawUrl.startsWith("/") -> "http://${_state.value.hostAddress}$rawUrl"
          rawUrl.startsWith("http://localhost:") || rawUrl.startsWith("http://127.0.0.1:") -> {
            val path = rawUrl.substringAfter(":8765", "")
            "http://${_state.value.hostAddress}$path"
          }
          else -> rawUrl
        }
        _state.update { it.copy(lastHostMessage = "Downloading $fileName...") }
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
          val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty body")
          nanoController.handleDownloadedFirmware(fileName, bytes, autoFlash)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Firmware download failed: ${e.message}")
        sendWsJson(JSONObject().apply {
          put("type", "flash_progress")
          put("progress", 0.0)
          put("status", "Download failed: ${e.message}")
          put("done", true)
          put("success", false)
        })
      }
    }
  }

  fun startWebRtcStream() {
    if (_state.value.webrtcStreaming) return
    if (!_state.value.wsConnected) {
      _state.update { it.copy(webrtcState = "Connect WebSocket first") }
      return
    }

    scope.launch {
      try {
        _state.update { it.copy(webrtcStreaming = true, webrtcState = "Initializing camera & WebRTC...") }

        if (peerConnectionFactory == null) {
          PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
              .createInitializationOptions()
          )
          val egl = EglBase.create()
          eglBase = egl
          peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        }

        val factory = peerConnectionFactory!!
        val egl = eglBase!!

        val enumerator = Camera2Enumerator(context)
        val camName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
          ?: enumerator.deviceNames.firstOrNull()
          ?: throw IllegalStateException("No camera found")

        val camEvents = object : CameraVideoCapturer.CameraEventsHandler {
          override fun onCameraError(err: String?) {
            Log.e(TAG, "Camera error: $err")
            _state.update { it.copy(webrtcState = "Camera error: $err") }
          }
          override fun onCameraDisconnected() {
            Log.w(TAG, "Camera disconnected")
            _state.update { it.copy(webrtcState = "Camera disconnected") }
          }
          override fun onCameraFreezed(err: String?) {
            Log.w(TAG, "Camera frozen: $err")
          }
          override fun onCameraOpening(cam: String?) {
            Log.d(TAG, "Opening camera: $cam")
          }
          override fun onFirstFrameAvailable() {
            Log.d(TAG, "First camera frame captured!")
            _state.update { it.copy(webrtcState = "Streaming camera frames") }
          }
          override fun onCameraClosed() {
            Log.d(TAG, "Camera closed")
          }
        }

        val capturer = enumerator.createCapturer(camName, camEvents)
          ?: throw IllegalStateException("Failed to create Camera2 capturer")
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("WebRtcCaptureThread", egl.eglBaseContext)
        surfaceTextureHelper = helper

        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(640, 480, 30)

        val track = factory.createVideoTrack("ARDAMSv0", source)
        track.setEnabled(true)
        videoTrack = track
        previewRenderer?.let { track.addSink(it) }

        val iceServers = listOf(
          PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
          sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
          override fun onIceCandidate(candidate: IceCandidate) {
            sendWsJson(JSONObject().apply {
              put("type", "webrtc_ice")
              put("candidate", candidate.sdp)
              put("sdpMid", candidate.sdpMid)
              put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
          }

          override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            _state.update { it.copy(webrtcState = newState.name) }
          }

          override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
          override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
          override fun onIceConnectionReceivingChange(p0: Boolean) {}
          override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
          override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
          override fun onAddStream(p0: MediaStream?) {}
          override fun onRemoveStream(p0: MediaStream?) {}
          override fun onDataChannel(p0: DataChannel?) {}
          override fun onRenegotiationNeeded() {}
          override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: throw IllegalStateException("Failed to create PeerConnection")

        peerConnection = pc
        pc.addTrack(track, listOf("robot-stream"))

        pc.createOffer(object : SimpleSdpObserver("createOffer") {
          override fun onCreateSuccess(desc: SessionDescription) {
            pc.setLocalDescription(SimpleSdpObserver("setLocalDesc"), desc)
            sendWsJson(JSONObject().apply {
              put("type", "webrtc_offer")
              put("sdp", desc.description)
              put("sdpType", desc.type.canonicalForm())
            })
            _state.update { it.copy(webrtcState = "Offer sent (awaiting answer)...") }
          }
        }, MediaConstraints())
      } catch (e: Exception) {
        Log.e(TAG, "WebRTC start failed: ${e.message}", e)
        stopWebRtcStream()
        _state.update { it.copy(webrtcState = "Error: ${e.message}") }
      }
    }
  }

  fun stopWebRtcStream() {
    try {
      videoCapturer?.stopCapture()
    } catch (_: Exception) {
    }
    try {
      videoCapturer?.dispose()
    } catch (_: Exception) {
    }
    videoCapturer = null

    try {
      previewRenderer?.let { videoTrack?.removeSink(it) }
      videoTrack?.dispose()
      videoSource?.dispose()
      surfaceTextureHelper?.dispose()
      peerConnection?.close()
      peerConnection?.dispose()
    } catch (_: Exception) {
    }
    videoTrack = null
    videoSource = null
    surfaceTextureHelper = null
    peerConnection = null

    _state.update { it.copy(webrtcStreaming = false, webrtcState = "Stopped") }
  }

  private fun sendWsJson(json: JSONObject) {
    val ws = webSocket ?: return
    try {
      ws.send(json.toString())
    } catch (_: Exception) {
    }
  }

  fun destroy() {
    disconnectWebSocket()
    try {
      webhookServerSocket?.close()
    } catch (_: Exception) {
    }
    try {
      peerConnectionFactory?.dispose()
      eglBase?.release()
    } catch (_: Exception) {
    }
  }

  private open class SimpleSdpObserver(private val label: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(err: String?) {
      Log.w(TAG, "$label createFailure: $err")
    }
    override fun onSetFailure(err: String?) {
      Log.w(TAG, "$label setFailure: $err")
    }
  }
}
