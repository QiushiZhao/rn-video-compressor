import ExpoModulesCore

public class RnVideoCompressorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("RnVideoCompressor")

    AsyncFunction("probeVideo") { (inputUri: String, promise: Promise) in
      do {
        let result = try VideoProbe.probe(fileUri: inputUri)
        promise.resolve([
          "hasVideoTrack": result.hasVideoTrack,
          "width": result.width,
          "height": result.height,
          "bitrate": result.bitrate,
        ])
      } catch {
        promise.reject("ERR_UNSUPPORTED_SOURCE", error.localizedDescription)
      }
    }

    AsyncFunction("transcode") { (
      inputUri: String,
      outputUri: String,
      params: TranscodeParams,
      onProgress: JavaScriptFunction<Void>,
      promise: Promise
    ) in
      let swiftParams = TranscodeParamsSwift(
        width: params.width,
        height: params.height,
        videoBitrate: params.videoBitrate,
        audioBitrate: params.audioBitrate,
        fps: params.fps
      )

      VideoCompressor.transcode(
        inputUri: inputUri,
        outputUri: outputUri,
        params: swiftParams,
        progress: { p in
          try? onProgress.call(Double(p))
        },
        completion: { result in
          switch result {
          case .success:
            promise.resolve(nil)
          case .failure(let err):
            let msg = err.localizedDescription
            let code = msg.lowercased().contains("no video") ? "ERR_UNSUPPORTED_SOURCE" : "ERR_ENCODING_FAILED"
            promise.reject(code, msg)
          }
        }
      )
    }
  }
}

struct TranscodeParams: Record {
  @Field var width: Int
  @Field var height: Int
  @Field var videoBitrate: Int
  @Field var audioBitrate: Int
  @Field var fps: Int
}
