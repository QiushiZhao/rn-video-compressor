import AVFoundation
import NextLevelSessionExporter
import Foundation

struct TranscodeParamsSwift {
  let width: Int
  let height: Int
  let videoBitrate: Int
  let audioBitrate: Int
  let fps: Int
}

enum VideoCompressor {
  // Retain the exporter for the lifetime of the export so it isn't deallocated
  // mid-flight while its internal writer/reader are still running.
  private static var activeExporter: NextLevelSessionExporter?

  static func transcode(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsSwift,
    progress: @escaping (Float) -> Void,
    completion: @escaping (Result<Void, Error>) -> Void
  ) {
    guard let inputURL = URL(string: inputUri),
          let outputURL = URL(string: outputUri) else {
      completion(.failure(makeError("invalid URL")))
      return
    }

    // Delete any existing file at outputURL (exporter fails otherwise).
    try? FileManager.default.removeItem(at: outputURL)

    let asset = AVAsset(url: inputURL)

    // Fail fast if the source has no video track.
    guard asset.tracks(withMediaType: .video).first != nil else {
      completion(.failure(makeError("no video track in source")))
      return
    }

    let exporter = NextLevelSessionExporter(withAsset: asset)
    exporter.outputFileType = .mp4
    exporter.outputURL = outputURL
    exporter.optimizeForNetworkUse = true
    exporter.videoOutputConfiguration = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: params.width,
      AVVideoHeightKey: params.height,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: params.videoBitrate,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264High40,
        AVVideoExpectedSourceFrameRateKey: params.fps,
        AVVideoMaxKeyFrameIntervalKey: 250,
      ] as [String: Any],
    ]
    exporter.audioOutputConfiguration = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVNumberOfChannelsKey: 2,
      AVSampleRateKey: 44_100,
      AVEncoderBitRateKey: params.audioBitrate,
    ]

    activeExporter = exporter

    var lastEmit = Date.distantPast

    exporter.export(progressHandler: { (p: Float) in
      let now = Date()
      if now.timeIntervalSince(lastEmit) >= 0.1 {
        lastEmit = now
        progress(p)
      }
    }, completionHandler: { (result: Swift.Result<AVAssetExportSession.Status, Error>) in
      // Release the retained exporter now that export has finished.
      activeExporter = nil

      switch result {
      case .success(.completed):
        progress(1.0)
        completion(.success(()))
      case .success(let status):
        completion(.failure(makeError("export finished with status: \(status.rawValue)")))
      case .failure(let err):
        completion(.failure(err))
      }
    })
  }

  private static func makeError(_ message: String) -> NSError {
    NSError(domain: "RnVideoCompressor", code: 1,
            userInfo: [NSLocalizedDescriptionKey: message])
  }
}
