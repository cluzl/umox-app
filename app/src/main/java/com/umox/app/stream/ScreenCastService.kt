package com.umox.app.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.umox.app.MainActivity
import com.umox.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class ScreenCastService : Service() {

    companion object {
        const val ACTION_START_SCREEN = "com.umox.app.stream.START_SCREEN"
        const val ACTION_START_PATTERN = "com.umox.app.stream.START_PATTERN"
        const val ACTION_START_CARPLAY = "com.umox.app.stream.START_CARPLAY"
        const val ACTION_STOP = "com.umox.app.stream.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_IP = "extra_target_ip"
        const val EXTRA_VIDEO_PORT = "extra_video_port"
        const val EXTRA_CTRL_PORT = "extra_ctrl_port"
        const val EXTRA_CARPLAY_SIZE = "extra_carplay_size"

        private const val NOTIFICATION_CHANNEL_ID = "umox_cast_channel"
        private const val NOTIFICATION_ID = 1001

        var isStreamingActive = false
            private set

        var isPatternMode = false
            private set

        var onLogCallback: ((String) -> Unit)? = null

        var currentHudRenderer: com.umox.app.ui.HudDashboardRenderer? = null

        /** Dongle CarPlay aktif; MainActivity meneruskan tombol remote Bluetooth ke sini. */
        @Volatile var activeDongle: CarplayDongle? = null
            private set

        /** Rekaman stream dongle untuk demo/uji tanpa hardware (dipakai bila dongle tidak terpasang). */
        const val REPLAY_FILE = "carplay_replay.bin"
        private const val CLUSTER_W = 960
        private const val CLUSTER_H = 360
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaEncoder: MediaEncoder? = null
    private var socketStreamer: SocketStreamer? = null
    private var controlChannelClient: ControlChannelClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isVideoStreamRunning = false
    private var isCarplayMode = false
    private var carplaySize = Triple(CLUSTER_W, CLUSTER_H, 160)
    @Volatile private var carplayScaler: CarplayScaler? = null
    @Volatile private var carplayVideoLive = false

    /** 960x360 diteruskan langsung (tanpa transcode); resolusi lain didecode+diskalakan+encode ulang. */
    private val carplayPassthrough get() = carplaySize.first == CLUSTER_W && carplaySize.second == CLUSTER_H

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: AppConfig.defaultClusterIp
        val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, AppConfig.defaultVideoPort)
        val ctrlPort = intent.getIntExtra(EXTRA_CTRL_PORT, AppConfig.defaultControlPort)

        when (action) {
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultData != null && !isStreamingActive) {
                    val notif = buildNotification("Streaming Layar HP Aktif")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startScreenProjection(resultCode, resultData, targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_START_PATTERN -> {
                if (!isStreamingActive) {
                    val notif = buildNotification("Test Pattern Speedometer Aktif")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startPatternProjection(targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_START_CARPLAY -> {
                if (!isStreamingActive) {
                    val notif = buildNotification("Apple CarPlay (Dongle) Aktif")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    if (!startCarplayProjection(targetIp, videoPort, ctrlPort, intent.getStringExtra(EXTRA_CARPLAY_SIZE))) {
                        stopProjection()
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                stopProjection()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startPatternProjection(targetIp: String, videoPort: Int, ctrlPort: Int) {
        log("Memulai Test Pattern HUD (960x360) ke $targetIp (Video: $videoPort, Ctrl: $ctrlPort)...")
        isStreamingActive = true
        isPatternMode = true
        isVideoStreamRunning = false

        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = {
                handleVideoReconnect(videoPort)
            }
        }

        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = {
                log("★ Saluran kontrol siap / Speedometer meminta SHOW.")
                startVideoStream()
            }
            onHideRequested = {
                log("Speedometer meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
            }
            onKeyFrameRequested = {
                mediaEncoder?.requestKeyFrame()
            }
        }

        serviceScope.launch {
            // 1. Hubungkan saluran kontrol port 9527 terlebih dahulu
            controlChannelClient?.connect()

            // 2. Beri jeda hingga 2 detik agar negosiasi keamanan ECDH (Msg 49) selesai
            kotlinx.coroutines.delay(2000)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Batas waktu handshake terlewati, memicu sinyal SHOW manual...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
    }

    private fun startScreenProjection(
        resultCode: Int,
        data: Intent,
        targetIp: String,
        videoPort: Int,
        ctrlPort: Int
    ) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            log("Gagal mendapatkan MediaProjection.")
            stopSelf()
            return
        }

        log("Memulai streaming layar HP ke speedometer $targetIp:$videoPort...")
        isStreamingActive = true
        isPatternMode = false
        isVideoStreamRunning = false

        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = {
                handleVideoReconnect(videoPort)
            }
        }

        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = {
                log("★ Saluran kontrol siap / Speedometer meminta SHOW.")
                startVideoStream()
            }
            onHideRequested = {
                log("Speedometer meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
            }
            onKeyFrameRequested = {
                mediaEncoder?.requestKeyFrame()
            }
            onClusterModeChanged = { modeStr ->
                val isNight = modeStr.contains("night", ignoreCase = true)
                currentHudRenderer?.state?.isNightMode = isNight
                currentHudRenderer?.state?.clusterMode = if (isNight) "NIGHT" else "DAY"
                log("🌓 Menyesuaikan Tema HUD Cockpit ke mode: ${if (isNight) "MALAM" else "SIANG"}")
            }
        }

        serviceScope.launch {
            // 1. Hubungkan saluran kontrol port 9527 terlebih dahulu
            controlChannelClient?.connect()

            // 2. Beri jeda hingga 2 detik agar negosiasi keamanan ECDH (Msg 49) selesai
            kotlinx.coroutines.delay(2000)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Batas waktu handshake terlewati, memicu sinyal SHOW manual...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
    }

    private fun startCarplayProjection(targetIp: String, videoPort: Int, ctrlPort: Int, size: String?): Boolean {
        carplaySize = CarplayDongle.parseSize(size)
        val (w, h, dpi) = carplaySize
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = CarplayDongle.find(usb)
        val replay = File(getExternalFilesDir(null), REPLAY_FILE)
        val dongle = when {
            device != null && usb.hasPermission(device) ->
                CarplayDongle(usb, device, w, h, dpi, ::onCarplayFrame, ::log)
            device != null -> {
                log("⚠ Izin USB dongle belum diberikan.")
                return false
            }
            replay.isFile -> CarplayDongle(null, null, w, h, dpi, ::onCarplayFrame, ::log, replay)
            else -> {
                log("⚠ Dongle Carlinkit CPC200-CCPA tidak terdeteksi di USB (OTG).")
                return false
            }
        }

        log("Memulai Apple CarPlay ${w}x$h (${if (carplayPassthrough) "passthrough" else "skala -> ${CLUSTER_W}x$CLUSTER_H"}) ke $targetIp...")
        isStreamingActive = true
        isPatternMode = false
        isCarplayMode = true
        isVideoStreamRunning = false
        if (!dongle.start()) return false
        activeDongle = dongle

        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = { handleVideoReconnect(videoPort) }
        }
        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = { startVideoStream() }
            onHideRequested = {
                log("Speedometer meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
            }
            onKeyFrameRequested = { requestKeyFrame() }
            onClusterModeChanged = { dongle.setNightMode(it.contains("night", ignoreCase = true)) }
        }

        serviceScope.launch {
            controlChannelClient?.connect()
            kotlinx.coroutines.delay(2000)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Batas waktu handshake terlewati, memicu sinyal SHOW manual...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
        return true
    }

    /** Thread USB dongle: tiap frame H.264 CarPlay. */
    private fun onCarplayFrame(h264: ByteArray) {
        if (!carplayVideoLive) return
        if (carplayPassthrough) socketStreamer?.sendH264Packet(h264) else carplayScaler?.decode(h264)
    }

    private fun requestKeyFrame() {
        if (isCarplayMode && carplayPassthrough) activeDongle?.requestKeyFrame() else mediaEncoder?.requestKeyFrame()
    }

    private fun startVideoStream() {
        if (!isStreamingActive || isVideoStreamRunning) {
            requestKeyFrame()
            return
        }
        isVideoStreamRunning = true

        serviceScope.launch {
            // Buka soket video Port 9528 SETELAH handshake port 9527 divalidasi oleh motor
            socketStreamer?.connect()

            mediaEncoder = if (isCarplayMode && carplayPassthrough) null else MediaEncoder(
                width = 960,
                height = 360,
                dpi = 160,
                bitrate = 2_000_000,
                fps = 30,
                onPacketEncoded = { packet, _ ->
                    socketStreamer?.sendH264Packet(packet)
                },
                onLog = { log(it) }
            )

            if (isCarplayMode) {
                if (!carplayPassthrough) {
                    val surface = mediaEncoder?.startSurfaceInput()
                    carplayScaler = surface?.let {
                        runCatching { CarplayScaler(it, carplaySize.first, carplaySize.second, CLUSTER_W, CLUSTER_H, ::log) }
                            .onFailure { e -> log("⚠ Scaler CarPlay gagal: ${e.message}") }
                            .getOrNull()
                    }
                }
                carplayVideoLive = true
                activeDongle?.requestKeyFrame()
                log("★ APPLE CARPLAY TAMPIL DI SPEEDOMETER.")
            } else if (isPatternMode) {
                mediaEncoder?.startPatternTest()
                currentHudRenderer = mediaEncoder?.hudRenderer
                log("★ PURE ANDROID AUTO HUD AKTIF! Layar speedometer menampilkan Cockpit 960x360.")
            } else {
                mediaProjection?.let { mp ->
                    mediaEncoder?.start(mp)
                    log("★ STREAMING LAYAR HP AKTIF (960x360). Silakan buka Google Maps di HP.")
                }
            }

            // Segera picu Sync Frame (I-Frame) pertama
            mediaEncoder?.requestKeyFrame()
        }
    }

    private fun pauseVideoStream() {
        isVideoStreamRunning = false
        carplayVideoLive = false
        currentHudRenderer = null
        carplayScaler?.release()
        carplayScaler = null
        mediaEncoder?.stop()
        mediaEncoder = null
        socketStreamer?.disconnect()
    }

    private fun handleVideoReconnect(videoPort: Int) {
        if (!isStreamingActive || !isVideoStreamRunning) return
        serviceScope.launch {
            log("Mencoba menyambungkan kembali soket video ($videoPort) dalam 1 detik...")
            kotlinx.coroutines.delay(1000)
            if (isStreamingActive && isVideoStreamRunning) {
                socketStreamer?.connect()
                requestKeyFrame()
            }
        }
    }

    private fun stopProjection() {
        isStreamingActive = false
        isPatternMode = false
        isVideoStreamRunning = false
        isCarplayMode = false
        carplayVideoLive = false

        activeDongle?.stop()
        activeDongle = null
        carplayScaler?.release()
        carplayScaler = null

        mediaEncoder?.stop()
        mediaEncoder = null

        socketStreamer?.disconnect()
        socketStreamer = null

        controlChannelClient?.disconnect()
        controlChannelClient = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        log("Streaming video & kontrol dihentikan.")
    }

    private fun log(message: String) {
        onLogCallback?.invoke(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UMOX Screen Cast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan proyeksi layar ke speedometer"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("UMOX Desay SV Stream")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        serviceScope.cancel()
    }
}
