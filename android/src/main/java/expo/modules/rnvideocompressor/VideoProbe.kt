package expo.modules.rnvideocompressor

import android.media.MediaMetadataRetriever
import android.net.Uri

data class ProbeResult(
  val hasVideoTrack: Boolean,
  val width: Int,
  val height: Int,
  val bitrate: Int,
)

object VideoProbe {
  fun probe(fileUri: String): ProbeResult {
    val retriever = MediaMetadataRetriever()
    try {
      val uri = Uri.parse(fileUri)
      val path = uri.path ?: throw IllegalArgumentException("no path in URI: $fileUri")
      retriever.setDataSource(path)

      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
      if (!hasVideo) return ProbeResult(false, 0, 0, 0)

      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0

      return ProbeResult(true, width, height, bitrate)
    } finally {
      retriever.release()
    }
  }
}
