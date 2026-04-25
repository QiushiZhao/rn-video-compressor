export interface AutoOptions {
  onProgress?: (progress: number) => void;
}

export interface CustomOptions {
  width: number;
  height: number;
  videoBitrate: number;
  audioBitrate?: number;
  fps?: number;
  onProgress?: (progress: number) => void;
}

export interface CompressResult {
  uri: string;
  skipped: boolean;
}

export interface ProbeResult {
  /** True if the source has at least one video track. */
  hasVideoTrack: boolean;
  /** Display width in pixels (rotation-corrected on iOS). 0 when unknown. */
  width: number;
  /** Display height in pixels (rotation-corrected on iOS). 0 when unknown. */
  height: number;
  /** Video track estimated bit rate in bps, or the container bit rate as a
   * fallback when the per-track rate is unavailable. 0 when unknown. */
  bitrate: number;
  /** Source duration in seconds. 0 when unknown. */
  duration: number;
  /** MIME (Android) or 4CC codec tag (iOS) of the selected video track.
   *  Empty string when no video track or the format can't be inspected. */
  videoMime: string;
  /** True iff the current device can decode [videoMime]. Lets callers refuse
   *  unsupported sources (e.g. iPhone Dolby Vision `.mov` on non-DV Qualcomm
   *  devices) before kicking off a transcode. On iOS this is conservatively
   *  true for any asset with a readable video track — AVFoundation covers
   *  the common codecs natively. */
  hasDecoder: boolean;
}

export type ErrorCode =
  | 'ERR_INPUT_NOT_FOUND'
  | 'ERR_OUTPUT_PATH_INVALID'
  | 'ERR_UNSUPPORTED_SOURCE'
  | 'ERR_ENCODING_FAILED';

export class VideoCompressorError extends Error {
  code: ErrorCode;
  constructor(code: ErrorCode, message: string) {
    super(message);
    this.name = 'VideoCompressorError';
    this.code = code;
  }
}
