package com.umox.app.stream

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Decode H.264 CarPlay (srcW x srcH) lalu gambar ulang via GL ke input surface encoder cluster (960x360).
 * Decoder -> encoder langsung tidak menskala, jadi wajib lewat SurfaceTexture + EGL (pola grafika/CTS).
 */
class CarplayScaler(
    encoderSurface: Surface,
    private val srcW: Int,
    private val srcH: Int,
    private val dstW: Int,
    private val dstH: Int,
    private val onLog: (String) -> Unit,
) {
    private val thread = HandlerThread("cp-scaler").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<ByteArray>()

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var texture: SurfaceTexture
    private lateinit var decoderSurface: Surface
    private lateinit var decoder: MediaCodec
    private var program = 0
    private val texMatrix = FloatArray(16)
    @Volatile private var released = false

    init {
        val ready = CountDownLatch(1)
        var error: Exception? = null
        handler.post {
            try {
                setupGl(encoderSurface)
                setupDecoder()
            } catch (e: Exception) {
                error = e
            }
            ready.countDown()
        }
        ready.await()
        error?.let { release(); throw it }
        onLog("Scaler CarPlay aktif: ${srcW}x$srcH -> ${dstW}x$dstH (GL).")
    }

    /** Dipanggil dari thread USB untuk tiap frame Annex-B. */
    fun decode(frame: ByteArray) {
        if (released) return
        synchronized(lock) {
            val idx = freeInputs.removeFirstOrNull()
            if (idx != null) {
                queue(idx, frame)
            } else {
                // ponytail: antrean dibatasi; jika decoder tertinggal, frame lama dibuang (pulih di IDR paksa berikutnya).
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(frame)
            }
        }
    }

    private fun queue(idx: Int, frame: ByteArray) {
        val buf = decoder.getInputBuffer(idx) ?: return
        if (frame.size > buf.capacity()) {
            onLog("⚠ Frame ${frame.size}B > buffer decoder ${buf.capacity()}B, dibuang.")
            decoder.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, 0)
            return
        }
        buf.clear()
        buf.put(frame)
        decoder.queueInputBuffer(idx, 0, frame.size, System.nanoTime() / 1000, 0)
    }

    private fun setupDecoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, srcW, srcH).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(lock) {
                    val f = pending.removeFirstOrNull()
                    if (f == null) freeInputs.addLast(index) else queue(index, f)
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                codec.releaseOutputBuffer(index, info.size > 0)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onLog("⚠ Decoder CarPlay error: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                onLog("Decoder CarPlay output: $format")
            }
        }, handler)
        decoder.configure(format, decoderSurface, null, 0)
        decoder.start()
    }

    private fun setupGl(encoderSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize gagal" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig gagal" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent gagal" }

        program = linkProgram()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        texture = SurfaceTexture(tex[0])
        texture.setDefaultBufferSize(srcW, srcH)
        texture.setOnFrameAvailableListener({ draw() }, handler)
        decoderSurface = Surface(texture)
    }

    private fun draw() {
        if (released) return
        texture.updateTexImage()
        texture.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, dstW, dstH)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, UV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, texture.timestamp)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { decoderSurface.release() }
            runCatching { texture.release() }
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            done.countDown()
        }
        done.await()
        thread.quitSafely()
    }

    private fun linkProgram(): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader gagal: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(it)
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_PENDING = 30

        private fun floats(vararg v: Float) =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).apply { position(0) }

        private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val UV = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
