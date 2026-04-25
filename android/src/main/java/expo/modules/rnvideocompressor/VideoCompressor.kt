package expo.modules.rnvideocompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.ExactResizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy

data class TranscodeParamsKt(
  val width: Int,
  val height: Int,
  val videoBitrate: Int,
  val audioBitrate: Int,
  val fps: Int,
)

object VideoCompressor {
  private const val TAG = "RnVideoCompressor"

  fun transcode(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsKt,
    onProgress: (Double) -> Unit,
    onCompleted: () -> Unit,
    onFailed: (Throwable) -> Unit,
  ) {
    val inputPath = Uri.parse(inputUri).path
      ?: return onFailed(IllegalArgumentException("invalid input uri: $inputUri"))
    val outputPath = Uri.parse(outputUri).path
      ?: return onFailed(IllegalArgumentException("invalid output uri: $outputUri"))

    val (srcWidth, srcHeight) = probeSourceDimensions(inputPath)
    Log.i(TAG, "source ${srcWidth}x${srcHeight} → HW (Transcoder), SW fallback on failure")

    val videoStrategy = DefaultVideoStrategy.Builder()
      .addResizer(ExactResizer(params.width, params.height))
      .bitRate(params.videoBitrate.toLong())
      .frameRate(params.fps)
      .keyFrameInterval(250f / params.fps.toFloat())
      .build()

    val audioStrategy = DefaultAudioStrategy.Builder()
      .channels(2)
      .sampleRate(44_100)
      .bitRate(params.audioBitrate.toLong())
      .build()

    var lastEmitMs = 0L

    Transcoder.into(outputPath)
      .addDataSource(inputPath)
      .setVideoTrackStrategy(videoStrategy)
      .setAudioTrackStrategy(audioStrategy)
      .setListener(object : TranscoderListener {
        override fun onTranscodeProgress(progress: Double) {
          val now = System.currentTimeMillis()
          if (now - lastEmitMs >= 100) {
            lastEmitMs = now
            onProgress(progress)
          }
        }
        override fun onTranscodeCompleted(successCode: Int) {
          onProgress(1.0)
          onCompleted()
        }
        override fun onTranscodeCanceled() {
          onFailed(IllegalStateException("canceled"))
        }
        override fun onTranscodeFailed(exception: Throwable) {
          // Common HW failures we expect to recover from on the SW path:
          //   - Qualcomm 4K + GL surface UBWC color-format negotiation crash
          //     (vendor HAL bug; can't be disabled per-app on older platforms)
          //   - iPhone Dolby Vision .mov where the first video track is the DV
          //     enhancement layer that no Qualcomm decoder can configure
          // SW path picks a decodable track itself and uses Google's software
          // AVC encoder, so it sidesteps both. Progress restarts at 0 from the
          // SW path's own emitter — JS side already handles non-monotonic
          // progress (it's clamped/interpolated downstream).
          Log.w(TAG, "HW pipeline failed, falling back to SW", exception)
          SoftwareVideoTranscoder.transcode(inputUri, outputUri, params, onProgress, onCompleted, onFailed)
        }
      })
      .transcode()
  }

  /**
   * Read the source's width/height from its MP4 moov atoms — metadata-only,
   * opens no codec. Returns (0, 0) if the file has no video track or the
   * format doesn't publish dimensions. Used only for log breadcrumbs.
   */
  private fun probeSourceDimensions(path: String): Pair<Int, Int> {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(path)
      for (i in 0 until extractor.trackCount) {
        val fmt = extractor.getTrackFormat(i)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
        if (!mime.startsWith("video/")) continue
        val w = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
        val h = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
        return w to h
      }
    } catch (t: Throwable) {
      Log.w(TAG, "probeSourceDimensions failed", t)
    } finally {
      extractor.release()
    }
    return 0 to 0
  }
}
