package expo.modules.rnvideocompressor

import android.net.Uri
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
          onFailed(exception)
        }
      })
      .transcode()
  }
}
