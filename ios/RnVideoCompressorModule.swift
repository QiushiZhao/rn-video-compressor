import ExpoModulesCore

public class RnVideoCompressorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("RnVideoCompressor")

    AsyncFunction("probeVideo") { (inputUri: String, promise: Promise) in
      // Implemented in Task 7
      promise.reject("ERR_ENCODING_FAILED", "probeVideo not yet implemented")
    }

    AsyncFunction("transcode") { (
      inputUri: String,
      outputUri: String,
      params: TranscodeParams,
      onProgress: JavaScriptFunction<Void>,
      promise: Promise
    ) in
      // Implemented in Task 9
      promise.reject("ERR_ENCODING_FAILED", "transcode not yet implemented")
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
