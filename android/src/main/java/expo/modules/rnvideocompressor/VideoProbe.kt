package expo.modules.rnvideocompressor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

data class ProbeResult(
  val hasVideoTrack: Boolean,
  val width: Int,
  val height: Int,
  val bitrate: Int,
  val duration: Double,
  /** MIME of the best video track we'd hand to the decoder; empty if none. */
  val videoMime: String,
  /** True iff the device has a decoder for [videoMime]. Lets callers reject
   *  unsupported sources (e.g. iPhone Dolby Vision `.mov` on non-DV Qualcomm
   *  devices) up front instead of failing mid-transcode. */
  val hasDecoder: Boolean,
)

object VideoProbe {
  fun probe(fileUri: String): ProbeResult {
    val uri = Uri.parse(fileUri)
    val path = uri.path ?: throw IllegalArgumentException("no path in URI: $fileUri")

    val retriever = MediaMetadataRetriever()
    val duration: Double
    val width: Int
    val height: Int
    val bitrate: Int
    val hasVideo: Boolean
    try {
      retriever.setDataSource(path)

      // METADATA_KEY_DURATION is reported in milliseconds; divide to seconds
      // and clamp negative / unreadable values to 0 so the caller never sees
      // a poisoned duration.
      val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      duration = if (durationMs > 0) durationMs / 1000.0 else 0.0

      hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
      if (!hasVideo) {
        return ProbeResult(false, 0, 0, 0, duration, videoMime = "", hasDecoder = false)
      }

      width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
    } finally {
      retriever.release()
    }

    val (videoMime, hasDecoder) = findBestVideoMime(path)
    return ProbeResult(true, width, height, bitrate, duration, videoMime, hasDecoder)
  }

  /**
   * Open the container with [MediaExtractor] and ask the transcoder's track
   * picker which video track it would select. Returns the chosen MIME and
   * whether a decoder for that MIME actually exists on this device.
   *
   * iPhone Dolby Vision `.mov` exposes two video tracks — a DV enhancement
   * layer and an HEVC base layer. `findVideoTrack` filters to tracks the
   * device can decode, so on non-DV devices this picks the HEVC base and
   * reports `hasDecoder = true`. On a hypothetical source whose only video
   * track is DV-only, both tracks in `tracks` fail `hasDecoderFor`, the
   * picker falls back to the ranked best (DV), and we report
   * `hasDecoder = false` so the caller can refuse the source early.
   */
  private fun findBestVideoMime(path: String): Pair<String, Boolean> {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(path)
      val trackIdx = SoftwareVideoTranscoder.findVideoTrack(extractor) ?: return "" to false
      val mime = extractor.getTrackFormat(trackIdx).getString(MediaFormat.KEY_MIME) ?: return "" to false
      return mime to SoftwareVideoTranscoder.hasDecoderForMime(mime)
    } catch (_: Throwable) {
      return "" to false
    } finally {
      extractor.release()
    }
  }
}
