import ExpoModulesCore

public class RnVideoCompressorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("RnVideoCompressor")

    // See Kotlin module for rationale — JS callbacks from native worker
    // threads are unsafe; events are bridge-dispatched and safe from any
    // thread. JS side subscribes and filters by the per-call `id`.
    Events("onTranscodeProgress")

    AsyncFunction("probeVideo") { (inputUri: String, promise: Promise) in
      do {
        let result = try VideoProbe.probe(fileUri: inputUri)
        promise.resolve([
          "hasVideoTrack": result.hasVideoTrack,
          "width": result.width,
          "height": result.height,
          "bitrate": result.bitrate,
          "duration": result.duration,
          "videoMime": result.videoCodec,
          "hasDecoder": result.hasDecoder,
        ])
      } catch {
        promise.reject("ERR_UNSUPPORTED_SOURCE", error.localizedDescription)
      }
    }

    AsyncFunction("transcode") { (
      inputUri: String,
      outputUri: String,
      params: TranscodeParams,
      id: String,
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
        progress: { [weak self] p in
          self?.sendEvent("onTranscodeProgress", [
            "id": id,
            "progress": Double(p),
          ])
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
