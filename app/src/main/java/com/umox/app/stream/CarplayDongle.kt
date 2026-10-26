package com.umox.app.stream

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.view.KeyEvent
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread
import kotlin.concurrent.timer

/**
 * Driver USB dongle Carlinkit CPC200-CCPA (varian "Autokit" untuk head unit Android).
 *
 * iPhone --(CarPlay wireless)--> dongle (chip MFi asli) --USB--> HP ini --> speedometer (9528).
 * Dongle meneruskan H.264 Annex-B dari iPhone apa adanya, hanya menambah header 16+20 byte.
 * Protokol: rhysmorgan134/node-CarPlay & lvalen91/CPC200-CCPA_resources.
 *
 * [replay] != null: baca rekaman stream dongle (pesan 0x55AA55AA berurutan) dari file, untuk uji tanpa hardware.
 */
class CarplayDongle(
    private val usb: UsbManager?,
    private val device: UsbDevice?,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val onVideo: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
    private val replay: File? = null,
) {
    @Volatile private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    @Volatile private var running = false
    @Volatile private var plugged = false
    @Volatile private var waitIdr = true
    private var readThread: Thread? = null
    private val timers = mutableListOf<Timer>()
    private val tracks = HashMap<Int, Pair<Int, AudioTrack>>() // audioType -> (decodeType, track)

    fun start(): Boolean {
        if (replay != null) {
            running = true
            readThread = thread(name = "cp-replay", isDaemon = true) {
                onLog("▶ Replay stream dongle: ${replay.name}")
                // Diulang terus: IDR hanya di awal file, jadi tiap putaran = "IDR baru" untuk gerbang waitIdr.
                while (running) replay.inputStream().buffered().use { readLoop(DataInputStream(it), paced = true) }
                onLog("■ Replay selesai.")
            }
            return true
        }
        if (!openUsb()) return false

        running = true
        // Watchdog firmware: heartbeat wajib dalam ~10 dtk sejak USB dibuka; detak pertama di t=2s.
        timers += timer("cp-heartbeat", true, 2000, 2000) { send(message(HEARTBEAT)) }
        // ponytail: IDR paksa periodik juga jadi keepalive saat layar CarPlay diam; naikkan interval jika iPhone panas.
        timers += timer("cp-keyframe", true, KEYFRAME_INTERVAL_MS, KEYFRAME_INTERVAL_MS) { if (plugged) sendCommand(CMD_FRAME) }
        timers += Timer("cp-pair", true).apply { schedule(15_000) { if (!plugged) sendCommand(CMD_WIFI_PAIR) } }

        readThread = thread(name = "cp-usb-read", isDaemon = true) {
            for (m in initMessages(width, height, dpi, System.currentTimeMillis() / 1000)) {
                send(m)
                Thread.sleep(120)
            }
            Thread.sleep(600)
            sendCommand(CMD_WIFI_CONNECT)
            onLog("✔ Dongle CarPlay siap (${width}x$height @${dpi}dpi). Sambungkan iPhone ke Wi-Fi/Bluetooth dongle.")
            readLoop(DataInputStream(UsbInput()), paced = false)
        }
        return true
    }

    private fun openUsb(): Boolean {
        val dev = device ?: return false
        val c = usb?.openDevice(dev) ?: return false.also { onLog("⚠ Gagal membuka USB dongle (izin USB?).") }
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            val bulk = (0 until iface.endpointCount).map(iface::getEndpoint)
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val inEp = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val outEp = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (inEp != null && outEp != null && c.claimInterface(iface, true)) {
                epIn = inEp
                epOut = outEp
                conn = c
                return true
            }
        }
        c.close()
        onLog("⚠ Interface bulk dongle tidak ditemukan.")
        return false
    }

    fun stop() {
        if (!running) return
        running = false
        timers.forEach { it.cancel() }
        timers.clear()
        send(message(CLOSE_DONGLE))
        synchronized(this) {
            conn?.close()
            conn = null
        }
        readThread?.join(1500)
        tracks.values.forEach { it.second.release() }
        tracks.clear()
        onLog("Dongle CarPlay dihentikan.")
    }

    /** Minta IDR baru dan buang P-frame sampai IDR tiba (mis. setelah soket video cluster reconnect). */
    fun requestKeyFrame() {
        waitIdr = true
        sendCommand(CMD_FRAME)
    }

    fun setNightMode(night: Boolean) = sendCommand(if (night) CMD_NIGHT_ON else CMD_NIGHT_OFF)

    fun sendCommand(id: Int) = send(command(id))

    @Synchronized
    private fun send(data: ByteArray): Boolean {
        val c = conn ?: return false
        return c.bulkTransfer(epOut, data, data.size, 1000) == data.size
    }

    /** USB bulk IN sebagai InputStream: tiap read() = satu bulkTransfer dengan panjang tepat yang diminta. */
    private inner class UsbInput : InputStream() {
        private var fastFails = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (running) {
                val c = conn ?: return -1
                val t0 = SystemClock.uptimeMillis()
                val r = c.bulkTransfer(epIn, b, off, minOf(len, 16384), 1000)
                if (r > 0) {
                    fastFails = 0
                    return r
                }
                // Timeout normal butuh ~1 dtk; gagal instan berulang = dongle dicabut.
                if (SystemClock.uptimeMillis() - t0 < 50 && ++fastFails > 20) {
                    onLog("⚠ Dongle CarPlay terlepas dari USB.")
                    return -1
                }
            }
            return -1
        }
    }

    private fun readLoop(input: DataInputStream, paced: Boolean) {
        val hdr = ByteArray(16)
        var lastPts = -1
        try {
            while (running) {
                input.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                val len = bb.int
                val type = bb.int
                if (magic != MAGIC || len !in 0..MAX_PAYLOAD) {
                    // ponytail: tanpa resync scan-for-magic; dongle mengirim 1 pesan per transfer, cukup di praktik.
                    onLog("⚠ Header USB rusak (magic=${"%08X".format(magic)}, len=$len), dilewati.")
                    continue
                }
                val payload = ByteArray(len)
                input.readFully(payload)
                if (paced && type == VIDEO && len >= 20) {
                    val pts = u32(payload, 12)
                    if (lastPts >= 0) Thread.sleep((pts - lastPts).toLong().coerceIn(0, 1000))
                    lastPts = pts
                }
                try {
                    handle(type, payload)
                } catch (e: Exception) {
                    onLog("⚠ Pesan dongle type=0x${type.toString(16)} gagal diproses: ${e.message}")
                }
            }
        } catch (_: EOFException) {
            // Dongle dicabut / file replay habis.
        } catch (e: Exception) {
            if (running) onLog("⚠ Loop baca dongle berhenti: ${e.message}")
        }
    }

    private fun handle(type: Int, p: ByteArray) {
        when (type) {
            VIDEO -> if (p.size > 20) {
                val h264 = p.copyOfRange(20, p.size)
                if (waitIdr) {
                    if (!hasKeyFrame(h264)) return
                    waitIdr = false
                }
                onVideo(h264)
            }
            AUDIO -> playAudio(p)
            PLUGGED -> {
                plugged = true
                onLog("★ iPhone tersambung ke dongle (phoneType=${u32(p, 0)}).")
                requestKeyFrame()
            }
            UNPLUGGED -> {
                plugged = false
                onLog("iPhone terputus dari dongle.")
            }
            PHASE -> onLog("Dongle phase ${u32(p, 0)}")
            SOFTWARE_VERSION, BOX_SETTINGS -> onLog("Dongle: ${String(p, Charsets.UTF_8).trim('\u0000', ' ')}")
        }
    }

    private fun playAudio(p: ByteArray) {
        if (p.size <= 16) return // header 12 byte + perintah (1) / durasi volume (4), bukan PCM
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val decodeType = bb.int
        bb.float // volume: tidak dipakai di stream data
        val audioType = bb.int
        val (rate, channels) = AUDIO_FORMATS[decodeType] ?: return
        val track = tracks[audioType]?.takeIf { it.first == decodeType }?.second
            ?: newTrack(rate, channels).also { tracks.put(audioType, decodeType to it)?.second?.release() }
        // Non-blocking: lebih baik audio terpotong daripada menahan thread USB (video ikut macet).
        track.write(p, 12, p.size - 12, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun newTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(mask).build()
            )
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .build().apply { play() }
    }

    companion object {
        const val MAGIC = 0x55AA55AA
        private const val MAX_PAYLOAD = 4 * 1024 * 1024
        // CarPlay hanya kirim 1 IDR di awal sesi; cluster butuh IDR rutin agar pulih dari drop.
        // Riset CPC200: FRAME tiap 2 dtk dipakai lama tanpa efek terlihat (encoder iPhone restart ~3 ms).
        private const val KEYFRAME_INTERVAL_MS = 2000L

        const val OPEN = 0x01
        const val PLUGGED = 0x02
        const val PHASE = 0x03
        const val UNPLUGGED = 0x04
        const val VIDEO = 0x06
        const val AUDIO = 0x07
        const val COMMAND = 0x08
        const val CLOSE_DONGLE = 0x15
        const val BOX_SETTINGS = 0x19
        const val SEND_FILE = 0x99
        const val HEARTBEAT = 0xAA
        const val SOFTWARE_VERSION = 0xCC

        const val CMD_SIRI = 5
        const val CMD_SIRI_UP = 6
        const val CMD_FRAME = 12
        const val CMD_NIGHT_ON = 16
        const val CMD_NIGHT_OFF = 17
        const val CMD_USE_PHONE_MIC = 21
        const val CMD_AUDIO_VIA_DONGLE = 23
        const val CMD_WIFI_5G = 25
        const val CMD_LEFT = 100
        const val CMD_RIGHT = 101
        const val CMD_ENTER = 104
        const val CMD_ENTER_UP = 105
        const val CMD_BACK = 106
        const val CMD_UP = 113
        const val CMD_DOWN = 114
        const val CMD_HOME = 200
        const val CMD_PLAY_PAUSE = 203
        const val CMD_NEXT = 204
        const val CMD_PREV = 205
        const val CMD_WIFI_ENABLE = 1000
        const val CMD_WIFI_CONNECT = 1002
        const val CMD_WIFI_PAIR = 1012

        private val USB_IDS = setOf(0x1314 to 0x1520, 0x1314 to 0x1521, 0x08E4 to 0x01C0)

        /** decodeType -> (sampleRate, channels), dari node-carplay decodeTypeMap. */
        private val AUDIO_FORMATS = mapOf(
            1 to (44100 to 2), 2 to (44100 to 2), 3 to (8000 to 1), 4 to (48000 to 2),
            5 to (16000 to 1), 6 to (24000 to 1), 7 to (16000 to 2),
        )

        fun find(usb: UsbManager): UsbDevice? =
            usb.deviceList.values.firstOrNull { (it.vendorId to it.productId) in USB_IDS }

        /** "1280x480" atau "1920x720@200" -> (w, h, dpi). Default 1280x480: 8:3 seperti cluster, tinggi 480 = minimum CarPlay. */
        fun parseSize(s: String?): Triple<Int, Int, Int> {
            val m = Regex("""\s*(\d{3,4})\s*x\s*(\d{3,4})\s*(?:@\s*(\d{2,3}))?\s*""").matchEntire(s ?: "")
                ?: return Triple(1280, 480, 160)
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 160)
        }

        private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        private fun u32(b: ByteArray, off: Int) =
            if (b.size >= off + 4) ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int else -1

        fun message(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
            le(16 + payload.size).putInt(MAGIC).putInt(payload.size).putInt(type).putInt(type.inv()).put(payload).array()

        fun command(id: Int) = message(COMMAND, le(4).putInt(id).array())

        fun file(path: String, content: ByteArray): ByteArray {
            val name = (path + "\u0000").toByteArray(Charsets.US_ASCII)
            return message(
                SEND_FILE,
                le(8 + name.size + content.size).putInt(name.size).put(name).putInt(content.size).put(content).array(),
            )
        }

        private fun fileInt(path: String, v: Int) = file(path, le(4).putInt(v).array())

        /** Urutan init = node-carplay DongleDriver.start(), kecuali mic HP & audio lewat dongle. */
        fun initMessages(w: Int, h: Int, dpi: Int, nowSec: Long): List<ByteArray> = listOf(
            fileInt("/tmp/screen_dpi", dpi),
            message(OPEN, le(28).putInt(w).putInt(h).putInt(30).putInt(5).putInt(49152).putInt(2).putInt(2).array()),
            fileInt("/tmp/night_mode", 0),
            fileInt("/tmp/hand_drive_mode", 0),
            fileInt("/tmp/charge_mode", 1),
            file("/etc/box_name", "UMOX".toByteArray(Charsets.US_ASCII)),
            message(
                BOX_SETTINGS,
                """{"mediaDelay":300,"syncTime":$nowSec,"androidAutoSizeW":$w,"androidAutoSizeH":$h}"""
                    .toByteArray(Charsets.US_ASCII),
            ),
            command(CMD_WIFI_ENABLE),
            command(CMD_WIFI_5G),
            command(CMD_USE_PHONE_MIC),
            command(CMD_AUDIO_VIA_DONGLE),
        )

        /** True jika buffer Annex-B memuat NAL SPS (7) atau IDR (5). */
        fun hasKeyFrame(b: ByteArray): Boolean {
            for (i in 0 until b.size - 3) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                    val t = b[i + 3].toInt() and 0x1F
                    if (t == 7 || t == 5) return true
                }
            }
            return false
        }

        /** Tombol remote BT / keyboard -> perintah CarPlay (pola key binding react-carplay). */
        fun keyDownCommand(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> CMD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CMD_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> CMD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CMD_DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> CMD_ENTER
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> CMD_BACK
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_MOVE_HOME -> CMD_HOME
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> CMD_SIRI
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> CMD_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> CMD_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CMD_PREV
            else -> null
        }

        fun keyUpCommand(keyCode: Int): Int? = when (keyDownCommand(keyCode)) {
            CMD_ENTER -> CMD_ENTER_UP
            CMD_SIRI -> CMD_SIRI_UP
            else -> null
        }
    }
}
