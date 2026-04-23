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
