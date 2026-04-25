package expo.modules.rnvideocompressor

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * CPU-encoder transcode path for sources that trip Qualcomm HW-encoder bugs
 * (4K + GL surface input → UBWC color-format negotiation failure). We keep
 * HW *decode* (it works fine) and switch to a Google software AVC encoder
 * (`c2.android.avc.encoder` or `OMX.google.h264.encoder`) for output, which
 * doesn't go through UBWC.
 *
 * Pipeline: MediaExtractor → HW decoder → GL surface bridge (scale + YUV→RGB
 * via samplerExternalOES) → SW encoder (input surface, COLOR_FormatSurface)
 * → MediaMuxer. Audio is copied through as-is (no re-encode) to avoid audio
 * codec state races and because the source is already in an MP4-compatible
 * container.
 *
 * Runs on its own thread; the caller's callbacks are invoked from it. The
 * outer Kotlin module already routes progress through `sendEvent` (bridge-
 * dispatched), so the thread boundary is safe on the JS side.
 */
object SoftwareVideoTranscoder {
  private const val TAG = "RnVideoCompressor/SW"
  private const val TIMEOUT_US = 10_000L
  private const val FRAME_AVAILABLE_WAIT_MS = 1000L

  fun transcode(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsKt,
    onProgress: (Double) -> Unit,
    onCompleted: () -> Unit,
    onFailed: (Throwable) -> Unit,
  ) {
    thread(start = true, name = "SoftwareVideoTranscoder") {
      try {
        runPipeline(inputUri, outputUri, params, onProgress)
        onCompleted()
      } catch (t: Throwable) {
        Log.e(TAG, "transcode failed", t)
        onFailed(t)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Main pipeline
  // ---------------------------------------------------------------------------

  private fun runPipeline(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsKt,
    onProgress: (Double) -> Unit,
  ) {
    val inputPath = pathOf(inputUri)
    val outputPath = pathOf(outputUri)

    val extractor = MediaExtractor()
    extractor.setDataSource(inputPath)

    val videoTrackIndex = findVideoTrack(extractor)
      ?: throw IllegalStateException("no decodable video track")
    val srcVideoFormat = extractor.getTrackFormat(videoTrackIndex)
    val srcVideoMime = srcVideoFormat.getString(MediaFormat.KEY_MIME)
      ?: throw IllegalStateException("video track has no MIME")
    val audioTrackIndex = findTrack(extractor, "audio/")
    val srcAudioFormat = audioTrackIndex?.let { extractor.getTrackFormat(it) }

    val durationUs = if (srcVideoFormat.containsKey(MediaFormat.KEY_DURATION)) {
      srcVideoFormat.getLong(MediaFormat.KEY_DURATION)
    } else 0L
    val rotation = if (srcVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
      srcVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
    } else 0

    // Pre-rotate: if the source carries a rotation flag, the HW decoder's
    // buffers are already in landscape orientation and we apply rotation via
    // muxer.setOrientationHint. Don't swap the encoder's w/h.

    val encoderName = pickSoftwareAvcEncoder()
    Log.i(TAG, "using SW encoder: $encoderName (target ${params.width}x${params.height} @${params.fps} ${params.videoBitrate}bps)")

    val encoder = MediaCodec.createByCodecName(encoderName)
    val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, params.width, params.height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, params.videoBitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
      // Keyframe every ~10s (matches deepmedia default 250/fps).
      setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 250f / params.fps.toFloat())
      setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
      setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
    }
    encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val encoderInputSurface = InputSurface(encoder.createInputSurface())
    encoder.start()

    // GL context must be current on this thread before creating the decoder
    // output SurfaceTexture (which lives in the same GL texture namespace).
    encoderInputSurface.makeCurrent()
    val outputSurface = OutputSurface()

    val decoder = MediaCodec.createDecoderByType(srcVideoMime)
    // Strip any HDR/side-data hints from the decoded format we don't need.
    decoder.configure(srcVideoFormat, outputSurface.surface, null, 0)
    decoder.start()
    extractor.selectTrack(videoTrackIndex)

    val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    if (rotation != 0) muxer.setOrientationHint(rotation)

    // Open a separate extractor for audio so reads don't interleave with
    // the video extractor's position. Audio is pass-through: samples go
    // straight from extractor → muxer, no re-encode.
    val audioExtractor: MediaExtractor? = if (srcAudioFormat != null) {
      MediaExtractor().apply {
        setDataSource(inputPath)
        selectTrack(audioTrackIndex!!)
      }
    } else null

    var videoTrackOut = -1
    var audioTrackOut = -1
    var muxerStarted = false

    var sawInputEos = false
    var sawDecoderEos = false
    var sawEncoderEos = false
    val info = MediaCodec.BufferInfo()
    var lastProgressEmit = -1.0

    try {
      while (!sawEncoderEos) {
        // ---- Feed decoder from extractor ----
        if (!sawInputEos) {
          val inId = decoder.dequeueInputBuffer(TIMEOUT_US)
          if (inId >= 0) {
            val inBuf = decoder.getInputBuffer(inId)!!
            val sampleSize = extractor.readSampleData(inBuf, 0)
            if (sampleSize < 0) {
              decoder.queueInputBuffer(inId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              sawInputEos = true
            } else {
              val pts = extractor.sampleTime
              decoder.queueInputBuffer(inId, 0, sampleSize, pts, extractor.sampleFlags)
              extractor.advance()
            }
          }
        }

        // ---- Pull from decoder, render to encoder input surface ----
        if (!sawDecoderEos) {
          val decId = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
          when {
            decId == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* try encoder side */ }
            decId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              Log.i(TAG, "decoder output format: ${decoder.outputFormat}")
            }
            decId >= 0 -> {
              val doRender = info.size > 0
              decoder.releaseOutputBuffer(decId, doRender)
              if (doRender) {
                outputSurface.awaitNewImage()
                outputSurface.drawImage(params.width, params.height)
                encoderInputSurface.setPresentationTime(info.presentationTimeUs * 1000)
                encoderInputSurface.swapBuffers()
                if (durationUs > 0) {
                  val p = info.presentationTimeUs.toDouble() / durationUs.toDouble()
                  val clamped = p.coerceIn(0.0, 0.99)
                  if (clamped - lastProgressEmit >= 0.01) {
                    lastProgressEmit = clamped
                    onProgress(clamped)
                  }
                }
              }
              if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawDecoderEos = true
                encoder.signalEndOfInputStream()
              }
            }
          }
        }

        // ---- Drain encoder, write to muxer ----
        drainEncoder@ while (true) {
          val encId = encoder.dequeueOutputBuffer(info, 0)
          when {
            encId == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drainEncoder
            encId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              if (muxerStarted) throw IllegalStateException("encoder format changed twice")
              val outFormat = encoder.outputFormat
              videoTrackOut = muxer.addTrack(outFormat)
              if (srcAudioFormat != null) {
                audioTrackOut = muxer.addTrack(srcAudioFormat)
              }
              muxer.start()
              muxerStarted = true
            }
            encId >= 0 -> {
              val outBuf = encoder.getOutputBuffer(encId)!!
              // CODEC_CONFIG (CSD) is already embedded in outputFormat → skip
              if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
              if (info.size > 0 && muxerStarted) {
                outBuf.position(info.offset)
                outBuf.limit(info.offset + info.size)
                muxer.writeSampleData(videoTrackOut, outBuf, info)
                // Interleave any audio samples up to this video pts so the
                // muxer doesn't see a big monotonic gap between tracks.
                if (audioExtractor != null) {
                  pumpAudioUpTo(audioExtractor, muxer, audioTrackOut, info.presentationTimeUs)
                }
              }
              encoder.releaseOutputBuffer(encId, false)
              if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawEncoderEos = true
                break@drainEncoder
              }
            }
          }
        }
      }

      // Drain any remaining audio after video EOS.
      if (audioExtractor != null && muxerStarted) {
        pumpAudioUpTo(audioExtractor, muxer, audioTrackOut, Long.MAX_VALUE)
      }

      onProgress(1.0)
    } finally {
      try { decoder.stop() } catch (_: Throwable) {}
      decoder.release()
      try { encoder.stop() } catch (_: Throwable) {}
      encoder.release()
      outputSurface.release()
      encoderInputSurface.release()
      extractor.release()
      audioExtractor?.release()
      if (muxerStarted) {
        try { muxer.stop() } catch (t: Throwable) { Log.w(TAG, "muxer stop failed", t) }
      }
      muxer.release()
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun pumpAudioUpTo(
    audioExtractor: MediaExtractor,
    muxer: MediaMuxer,
    audioTrackOut: Int,
    upToPtsUs: Long,
  ) {
    if (audioTrackOut < 0) return
    val info = MediaCodec.BufferInfo()
    val buf = ByteBuffer.allocate(256 * 1024)
    while (true) {
      val size = audioExtractor.readSampleData(buf, 0)
      if (size < 0) return
      val pts = audioExtractor.sampleTime
      if (pts > upToPtsUs) {
        // Keep extractor positioned on this unread sample for the next pump;
        // readSampleData didn't advance() so we just return.
        return
      }
      info.offset = 0
      info.size = size
      info.presentationTimeUs = pts
      info.flags = audioExtractor.sampleFlags
      muxer.writeSampleData(audioTrackOut, buf, info)
      if (!audioExtractor.advance()) return
    }
  }

  private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
    for (i in 0 until extractor.trackCount) {
      val fmt = extractor.getTrackFormat(i)
      val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith(mimePrefix)) return i
    }
    return null
  }

  /**
   * Pick the best decodable video track. iPhone Dolby Vision `.mov` files
   * expose the DV enhancement layer as a separate `video/dolby-vision` track
   * ahead of the backward-compatible `video/hevc` base layer; picking the
   * first video track lands on DV, which most Qualcomm decoders can't handle.
   *
   * Strategy: rank by codec preference (HEVC > AVC > VP9 > AV1 > other) and
   * require that the device actually has a decoder for it. Falls back to the
   * first video track if nothing ranks — configure will still fail, but the
   * error surfaces the real MIME instead of a misleading AVC mismatch.
   */
  internal fun findVideoTrack(extractor: MediaExtractor): Int? {
    val tracks = mutableListOf<Pair<Int, String>>()
    for (i in 0 until extractor.trackCount) {
      val fmt = extractor.getTrackFormat(i)
      val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("video/")) tracks += i to mime
    }
    if (tracks.isEmpty()) return null
    return pickBestVideoTrack(tracks) { hasDecoderFor(it) }
  }

  internal fun pickBestVideoTrack(
    tracks: List<Pair<Int, String>>,
    hasDecoder: (String) -> Boolean,
  ): Int? {
    if (tracks.isEmpty()) return null
    val decodable = tracks.filter { hasDecoder(it.second) }
    val candidates = if (decodable.isNotEmpty()) decodable else tracks
    return candidates.minByOrNull { mimeRank(it.second) }?.first
  }

  private fun mimeRank(mime: String): Int = when (mime.lowercase()) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> 0
    MediaFormat.MIMETYPE_VIDEO_AVC -> 1
    MediaFormat.MIMETYPE_VIDEO_VP9 -> 2
    MediaFormat.MIMETYPE_VIDEO_AV1 -> 3
    "video/dolby-vision" -> 100
    else -> 50
  }

  private fun hasDecoderFor(mime: String): Boolean = hasDecoderForMime(mime)

  /** Public so [VideoProbe] can reuse the same decoder-availability check. */
  internal fun hasDecoderForMime(mime: String): Boolean {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    for (info in list.codecInfos) {
      if (info.isEncoder) continue
      if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return true
    }
    return false
  }

  private fun pathOf(uri: String): String {
    // Accept `file://path` or plain path.
    if (uri.startsWith("file://")) return uri.removePrefix("file://")
    return uri
  }

  private fun pickSoftwareAvcEncoder(): String {
    val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    for (info in list.codecInfos) {
      if (!info.isEncoder) continue
      if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
      val name = info.name
      // AOSP naming conventions for software codecs: "c2.android.*" (Codec2)
      // and the legacy "OMX.google.*". Everything else (OMX.qcom, OMX.MTK,
      // OMX.Exynos, c2.<vendor>.*) is hardware / vendor.
      if (name.startsWith("c2.android.") || name.startsWith("OMX.google.")) {
        return name
      }
    }
    throw IllegalStateException("No software AVC encoder available on this device")
  }

  // ---------------------------------------------------------------------------
  // GL bridge: encoder input surface wrapper
  // ---------------------------------------------------------------------------

  private class InputSurface(private val surface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay === EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
      val versions = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) {
        throw RuntimeException("eglInitialize failed")
      }
      // EGL_RECORDABLE_ANDROID: tells the driver to back the surface with a
      // buffer layout that MediaCodec encoder can consume efficiently.
      val configAttribs = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGLExt.EGL_RECORDABLE_ANDROID, 1,
        EGL14.EGL_NONE
      )
      val configs = arrayOfNulls<EGLConfig>(1)
      val numConfigs = IntArray(1)
      if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
        throw RuntimeException("eglChooseConfig failed")
      }
      val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
      eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
      if (eglContext === EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
      val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
      eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
      if (eglSurface === EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
    }

    fun makeCurrent() {
      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        throw RuntimeException("eglMakeCurrent failed")
      }
    }

    fun setPresentationTime(nanos: Long) {
      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nanos)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun release() {
      if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
      }
      surface.release()
      eglDisplay = EGL14.EGL_NO_DISPLAY
      eglContext = EGL14.EGL_NO_CONTEXT
      eglSurface = EGL14.EGL_NO_SURFACE
    }
  }

  // ---------------------------------------------------------------------------
  // GL bridge: decoder output surface (SurfaceTexture → GL texture)
  // ---------------------------------------------------------------------------

  private class OutputSurface : SurfaceTexture.OnFrameAvailableListener {
    private val renderer = TextureRender()
    private val surfaceTexture: SurfaceTexture
    val surface: Surface
    private val frameLock = ReentrantLock()
    private val frameAvailable = frameLock.newCondition()
    @Volatile private var hasFrame = false

    init {
      renderer.setup()
      surfaceTexture = SurfaceTexture(renderer.textureId)
      surfaceTexture.setOnFrameAvailableListener(this)
      surface = Surface(surfaceTexture)
    }

    fun awaitNewImage() {
      frameLock.withLock {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(FRAME_AVAILABLE_WAIT_MS)
        while (!hasFrame) {
          if (remainingNs <= 0) throw RuntimeException("Frame wait timed out")
          remainingNs = frameAvailable.awaitNanos(remainingNs)
        }
        hasFrame = false
      }
      surfaceTexture.updateTexImage()
    }

    fun drawImage(outW: Int, outH: Int) {
      renderer.draw(surfaceTexture, outW, outH)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
      frameLock.withLock {
        hasFrame = true
        frameAvailable.signalAll()
      }
    }

    fun release() {
      surface.release()
      surfaceTexture.release()
      renderer.release()
    }
  }

  // ---------------------------------------------------------------------------
  // GL bridge: samplerExternalOES → fullscreen quad on encoder surface
  // ---------------------------------------------------------------------------

  private class TextureRender {
    var textureId: Int = 0
      private set
    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uMvpLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    init {
      // Triangle strip covering the full viewport.
      vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
      }
      texBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        position(0)
      }
      Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun setup() {
      program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
      aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
      aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
      uMvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
      uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      textureId = textures[0]
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
      GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(st: SurfaceTexture, outW: Int, outH: Int) {
      st.getTransformMatrix(texMatrix)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      GLES20.glViewport(0, 0, outW, outH)
      GLES20.glUseProgram(program)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
      GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
      GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
      GLES20.glEnableVertexAttribArray(aPositionLoc)
      GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
      GLES20.glEnableVertexAttribArray(aTexCoordLoc)
      GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GLES20.glDisableVertexAttribArray(aPositionLoc)
      GLES20.glDisableVertexAttribArray(aTexCoordLoc)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
      if (program != 0) {
        GLES20.glDeleteProgram(program)
        program = 0
      }
      if (textureId != 0) {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
      }
    }

    private fun linkProgram(vs: String, fs: String): Int {
      val vsh = compileShader(GLES20.GL_VERTEX_SHADER, vs)
      val fsh = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
      val prog = GLES20.glCreateProgram()
      GLES20.glAttachShader(prog, vsh)
      GLES20.glAttachShader(prog, fsh)
      GLES20.glLinkProgram(prog)
      val linked = IntArray(1)
      GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
      if (linked[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(prog)
        GLES20.glDeleteProgram(prog)
        throw RuntimeException("GL program link failed: $log")
      }
      return prog
    }

    private fun compileShader(type: Int, src: String): Int {
      val s = GLES20.glCreateShader(type)
      GLES20.glShaderSource(s, src)
      GLES20.glCompileShader(s)
      val ok = IntArray(1)
      GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
      if (ok[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(s)
        GLES20.glDeleteShader(s)
        throw RuntimeException("GL shader compile failed: $log")
      }
      return s
    }

    companion object {
      private const val VERTEX_SHADER = """
        uniform mat4 uMvp;
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
          gl_Position = uMvp * aPosition;
          vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
      """
      private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
          gl_FragColor = texture2D(uTexture, vTexCoord);
        }
      """
    }
  }
}
