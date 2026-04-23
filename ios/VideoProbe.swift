import AVFoundation
import Foundation

struct ProbeResult {
  let hasVideoTrack: Bool
  let width: Int
  let height: Int
  let bitrate: Int
}

enum VideoProbe {
  static func probe(fileUri: String) throws -> ProbeResult {
    guard let url = URL(string: fileUri) else {
      throw NSError(domain: "RnVideoCompressor", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "invalid URL: \(fileUri)"])
    }
    let asset = AVURLAsset(url: url)

    guard let track = asset.tracks(withMediaType: .video).first else {
      return ProbeResult(hasVideoTrack: false, width: 0, height: 0, bitrate: 0)
    }

    // naturalSize × preferredTransform gives display-oriented size so that
    // a portrait clip reports (smaller-width, larger-height) not rotated.
    let size = track.naturalSize.applying(track.preferredTransform)
    let width = abs(Int(size.width))
    let height = abs(Int(size.height))
    let bitrate = Int(track.estimatedDataRate.rounded())

    return ProbeResult(hasVideoTrack: true, width: width, height: height, bitrate: bitrate)
  }
}
