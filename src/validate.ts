import { VideoCompressorError } from './RnVideoCompressor.types';

const FILE_SCHEME = 'file://';

function stripScheme(uri: string): string | null {
  if (!uri.startsWith(FILE_SCHEME)) return null;
  return uri.slice(FILE_SCHEME.length);
}

export function validateInputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null || p.length === 0) {
    throw new VideoCompressorError('ERR_INPUT_NOT_FOUND', `inputUri must be file://, got: ${uri}`);
  }
  return p;
}

export function validateOutputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null || p.length === 0) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must be file://, got: ${uri}`);
  }
  if (!/\.mp4$/i.test(p)) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must end with .mp4, got: ${p}`);
  }
  return p;
}
