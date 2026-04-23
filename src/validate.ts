import * as fs from 'fs';
import * as path from 'path';
import { VideoCompressorError } from './RnVideoCompressor.types';

const FILE_SCHEME = 'file://';

function stripScheme(uri: string): string | null {
  if (!uri.startsWith(FILE_SCHEME)) return null;
  return uri.slice(FILE_SCHEME.length);
}

export function validateInputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null) {
    throw new VideoCompressorError('ERR_INPUT_NOT_FOUND', `inputUri must be file://, got: ${uri}`);
  }
  try {
    fs.accessSync(p, fs.constants.R_OK);
  } catch {
    throw new VideoCompressorError('ERR_INPUT_NOT_FOUND', `input file not readable: ${p}`);
  }
  return p;
}

export function validateOutputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must be file://, got: ${uri}`);
  }
  if (!/\.mp4$/i.test(p)) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must end with .mp4, got: ${p}`);
  }
  const dir = path.dirname(p);
  try {
    fs.accessSync(dir, fs.constants.W_OK);
  } catch {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `output directory not writable: ${dir}`);
  }
  return p;
}
