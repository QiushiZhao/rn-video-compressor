package expo.modules.rnvideocompressor

import expo.modules.kotlin.Promise
import expo.modules.kotlin.jni.JavaScriptFunction
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class RnVideoCompressorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("RnVideoCompressor")

    AsyncFunction("probeVideo") { inputUri: String, promise: Promise ->
      try {
        val result = VideoProbe.probe(inputUri)
        promise.resolve(mapOf(
          "hasVideoTrack" to result.hasVideoTrack,
          "width" to result.width,
          "height" to result.height,
          "bitrate" to result.bitrate,
        ))
      } catch (e: Throwable) {
        promise.reject("ERR_UNSUPPORTED_SOURCE", e.message ?: "probe failed", e)
      }
    }

    AsyncFunction("transcode") { inputUri: String,
                                  outputUri: String,
                                  params: TranscodeParams,
                                  onProgress: JavaScriptFunction<Any?>,
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
        onProgress = { p -> runCatching { onProgress(p) } },
        onCompleted = { promise.resolve(null) },
        onFailed = { err ->
          val msg = err.message ?: err.javaClass.simpleName
          val code = if (msg.contains("no video", ignoreCase = true)) "ERR_UNSUPPORTED_SOURCE" else "ERR_ENCODING_FAILED"
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
