import AVFoundation
import Foundation

struct ProbeResult {
  let hasVideoTrack: Bool
  let width: Int
  let height: Int
  let bitrate: Int
  let duration: Double
  /** Four-char codec tag from the track (e.g. "hvc1", "avc1", "dvh1"). Empty
   *  when the track doesn't expose a format description. */
  let videoCodec: String
  /** True iff iOS can decode [videoCodec]. AVFoundation supports the mainline
   *  codecs natively (H.264/HEVC/Dolby Vision), so this is conservatively
   *  true for any tag AVAssetReader will accept; we keep the flag in parity
   *  with Android for cross-platform callers. */
  let hasDecoder: Bool
}

enum VideoProbe {
  static func probe(fileUri: String) throws -> ProbeResult {
    guard let url = URL(string: fileUri) else {
      throw NSError(domain: "RnVideoCompressor", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "invalid URL: \(fileUri)"])
    }
    let asset = AVURLAsset(url: url)

    // Duration is available at the asset level regardless of whether a video
    // track exists; report it first so audio-only sources still surface a
    // length (useful if a caller probes a container it mis-identified as
    // video). Clamp negative / non-finite to 0.
    let rawDuration = CMTimeGetSeconds(asset.duration)
    let duration = rawDuration.isFinite && rawDuration > 0 ? rawDuration : 0

    guard let track = asset.tracks(withMediaType: .video).first else {
      return ProbeResult(
        hasVideoTrack: false, width: 0, height: 0, bitrate: 0, duration: duration,
        videoCodec: "", hasDecoder: false
      )
    }

    // naturalSize × preferredTransform gives display-oriented size so that
    // a portrait clip reports (smaller-width, larger-height) not rotated.
    let size = track.naturalSize.applying(track.preferredTransform)
    let width = abs(Int(size.width))
    let height = abs(Int(size.height))
    let bitrate = Int(track.estimatedDataRate.rounded())

    let videoCodec = codecTag(for: track)
    return ProbeResult(
      hasVideoTrack: true, width: width, height: height, bitrate: bitrate, duration: duration,
      videoCodec: videoCodec, hasDecoder: true
    )
  }

  private static func codecTag(for track: AVAssetTrack) -> String {
    guard let descriptions = track.formatDescriptions as? [CMFormatDescription], let first = descriptions.first else {
      return ""
    }
    let fourcc = CMFormatDescriptionGetMediaSubType(first)
    // Convert big-endian 4CC uint32 to 4-char string. Bytes are ASCII in
    // practice ('h','v','c','1' etc.); non-printable bytes are rare and we
    // just render them as '?' rather than failing.
    let bytes = [
      UInt8((fourcc >> 24) & 0xff),
      UInt8((fourcc >> 16) & 0xff),
      UInt8((fourcc >> 8) & 0xff),
      UInt8(fourcc & 0xff),
    ]
    return String(bytes: bytes.map { (0x20...0x7e).contains($0) ? $0 : UInt8(ascii: "?") }, encoding: .ascii) ?? ""
  }
}
