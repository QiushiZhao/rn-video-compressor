import NativeModule from './RnVideoCompressorModule';
import { validateInputUri, validateOutputUri } from './validate';
import { shouldSkip, computeTargetSize, computeBitrate, roundEven } from './autoRules';
import {
  AutoOptions,
  CompressResult,
  CustomOptions,
  VideoCompressorError,
} from './RnVideoCompressor.types';

export * from './RnVideoCompressor.types';

const DEFAULT_AUDIO_BITRATE = 128_000;
const DEFAULT_FPS = 30;

const noop = () => {};

export async function compressAuto(
  inputUri: string,
  outputUri: string,
  options: AutoOptions = {}
): Promise<CompressResult> {
  validateInputUri(inputUri);
  validateOutputUri(outputUri);
  const onProgress = options.onProgress ?? noop;

  let probe;
  try {
    probe = await NativeModule.probeVideo(inputUri);
  } catch (e: any) {
    throw new VideoCompressorError('ERR_UNSUPPORTED_SOURCE', e?.message ?? 'probe failed');
  }

  if (!probe.hasVideoTrack || probe.width <= 0 || probe.height <= 0) {
    throw new VideoCompressorError('ERR_UNSUPPORTED_SOURCE', 'no video track in source');
  }

  if (shouldSkip(probe.width, probe.height, probe.bitrate)) {
    return { uri: inputUri, skipped: true };
  }

  const { width, height } = computeTargetSize(probe.width, probe.height);
  const videoBitrate = computeBitrate(width, height);

  try {
    await NativeModule.transcode(
      inputUri,
      outputUri,
      { width, height, videoBitrate, audioBitrate: DEFAULT_AUDIO_BITRATE, fps: DEFAULT_FPS },
      onProgress
    );
  } catch (e: any) {
    throw new VideoCompressorError(
      e?.code === 'ERR_UNSUPPORTED_SOURCE' ? 'ERR_UNSUPPORTED_SOURCE' : 'ERR_ENCODING_FAILED',
      e?.message ?? 'transcode failed'
    );
  }

  return { uri: outputUri, skipped: false };
}

export async function compress(
  inputUri: string,
  outputUri: string,
  options: CustomOptions
): Promise<CompressResult> {
  validateInputUri(inputUri);
  validateOutputUri(outputUri);

  const width = roundEven(options.width);
  const height = roundEven(options.height);
  const audioBitrate = options.audioBitrate ?? DEFAULT_AUDIO_BITRATE;
  const fps = options.fps ?? DEFAULT_FPS;
  const onProgress = options.onProgress ?? noop;

  try {
    await NativeModule.transcode(
      inputUri,
      outputUri,
      { width, height, videoBitrate: options.videoBitrate, audioBitrate, fps },
      onProgress
    );
  } catch (e: any) {
    throw new VideoCompressorError(
      e?.code === 'ERR_UNSUPPORTED_SOURCE' ? 'ERR_UNSUPPORTED_SOURCE' : 'ERR_ENCODING_FAILED',
      e?.message ?? 'transcode failed'
    );
  }

  return { uri: outputUri, skipped: false };
}
