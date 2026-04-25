package expo.modules.rnvideocompressor

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class RnVideoCompressorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("RnVideoCompressor")

    // Progress is delivered via events, not a JS callback. Invoking a
    // JavaScriptFunction from Transcoder's dispatcher thread SIGSEGVs the
    // app (JSFunction requires the JS thread and posting to the main thread
    // isn't enough — they're different threads). `sendEvent` is bridge-
    // mediated and safe from any thread; JS side subscribes and filters by
    // the per-call `id`. Same pattern react-native-compressor uses.
    Events("onTranscodeProgress")

    AsyncFunction("probeVideo") { inputUri: String, promise: Promise ->
      try {
        val result = VideoProbe.probe(inputUri)
        promise.resolve(mapOf(
          "hasVideoTrack" to result.hasVideoTrack,
          "width" to result.width,
          "height" to result.height,
          "bitrate" to result.bitrate,
          "duration" to result.duration,
          "videoMime" to result.videoMime,
          "hasDecoder" to result.hasDecoder,
        ))
      } catch (e: Throwable) {
        promise.reject("ERR_UNSUPPORTED_SOURCE", e.message ?: "probe failed", e)
      }
    }

    AsyncFunction("transcode") { inputUri: String,
                                  outputUri: String,
                                  params: TranscodeParams,
                                  id: String,
                                  promise: Promise ->
      val ktParams = TranscodeParamsKt(
        width = params.width,
        height = params.height,
        videoBitrate = params.videoBitrate,
        audioBitrate = params.audioBitrate,
        fps = params.fps,
      )

      VideoCompressor.transcode(
        inputUri, outputUri, ktParams,
        onProgress = { p ->
          sendEvent("onTranscodeProgress", mapOf("id" to id, "progress" to p))
        },
        onCompleted = { promise.resolve(null) },
        onFailed = { err ->
          val msg = err.message ?: err.javaClass.simpleName
          val code = if (msg.contains("no video", ignoreCase = true)) "ERR_UNSUPPORTED_SOURCE" else "ERR_ENCODING_FAILED"
          android.util.Log.e("RnVideoCompressor", "transcode failed: $code $msg", err)
          promise.reject(code, msg, err)
        }
      )
    }
  }
}

class TranscodeParams : Record {
  @Field var width: Int = 0
  @Field var height: Int = 0
  @Field var videoBitrate: Int = 0
  @Field var audioBitrate: Int = 0
  @Field var fps: Int = 0
}
