import NativeModule from './RnVideoCompressorModule';
import { validateInputUri, validateOutputUri } from './validate';
import { shouldSkip, computeTargetSize, computeBitrate, roundEven } from './autoRules';
import {
  AutoOptions,
  CompressResult,
  CustomOptions,
  ProbeResult,
  VideoCompressorError,
} from './RnVideoCompressor.types';

export * from './RnVideoCompressor.types';

const DEFAULT_AUDIO_BITRATE = 128_000;
const DEFAULT_FPS = 30;

let _idCounter = 0;
function nextCallId(): string {
  // Monotonic within a millisecond and unique across concurrent calls.
  _idCounter = (_idCounter + 1) & 0x7fffffff;
  return `${Date.now()}-${_idCounter}`;
}

// Wire a caller-supplied onProgress callback to native progress events.
// Returns a disposer that MUST be called (typically in a `finally` block) to
// remove the event listener regardless of whether the transcode resolved or
// threw — otherwise the listener hangs around past the call.
function subscribeProgress(
  id: string,
  onProgress: ((progress: number) => void) | undefined,
): () => void {
  if (!onProgress) return () => {};
  const sub = NativeModule.addListener('onTranscodeProgress', (event) => {
    if (event.id === id) onProgress(event.progress);
  });
  return () => sub.remove();
}

export async function probe(inputUri: string) {
  validateInputUri(inputUri);
  try {
    return await NativeModule.probeVideo(inputUri);
  } catch (e: any) {
    throw new VideoCompressorError(
      'ERR_UNSUPPORTED_SOURCE',
      e?.message ?? 'probe failed'
    );
  }
}

/**
 * Check up-front whether this device can decode [inputUri]. Resolves cleanly
 * on supported sources; throws `ERR_UNSUPPORTED_SOURCE` otherwise — e.g.
 * iPhone Dolby Vision `.mov` on a non-DV Android device, which would
 * otherwise fail mid-transcode and waste the user's upload attempt.
 */
export async function ensureDecodable(inputUri: string): Promise<ProbeResult> {
  const probeResult = await probe(inputUri);
  if (!probeResult.hasVideoTrack || probeResult.width <= 0 || probeResult.height <= 0) {
    throw new VideoCompressorError('ERR_UNSUPPORTED_SOURCE', 'no video track in source');
  }
  if (!probeResult.hasDecoder) {
    throw new VideoCompressorError(
      'ERR_UNSUPPORTED_SOURCE',
      probeResult.videoMime
        ? `no decoder on this device for ${probeResult.videoMime}`
        : 'no decoder on this device for source video track'
    );
  }
  return probeResult;
}

export async function compressAuto(
  inputUri: string,
  outputUri: string,
  options: AutoOptions = {}
): Promise<CompressResult> {
  validateInputUri(inputUri);
  validateOutputUri(outputUri);

  const probeResult = await ensureDecodable(inputUri);

  if (shouldSkip(probeResult.width, probeResult.height, probeResult.bitrate)) {
    return { uri: inputUri, skipped: true };
  }

  const { width, height } = computeTargetSize(probeResult.width, probeResult.height);
  const videoBitrate = computeBitrate(width, height);

  const id = nextCallId();
  const unsubscribe = subscribeProgress(id, options.onProgress);
  try {
    await NativeModule.transcode(
      inputUri,
      outputUri,
      { width, height, videoBitrate, audioBitrate: DEFAULT_AUDIO_BITRATE, fps: DEFAULT_FPS },
      id
    );
  } catch (e: any) {
    throw new VideoCompressorError(
      e?.code === 'ERR_UNSUPPORTED_SOURCE' ? 'ERR_UNSUPPORTED_SOURCE' : 'ERR_ENCODING_FAILED',
      e?.message ?? 'transcode failed'
    );
  } finally {
    unsubscribe();
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

  await ensureDecodable(inputUri);

  const width = roundEven(options.width);
  const height = roundEven(options.height);
  const audioBitrate = options.audioBitrate ?? DEFAULT_AUDIO_BITRATE;
  const fps = options.fps ?? DEFAULT_FPS;

  const id = nextCallId();
  const unsubscribe = subscribeProgress(id, options.onProgress);
  try {
    await NativeModule.transcode(
      inputUri,
      outputUri,
      { width, height, videoBitrate: options.videoBitrate, audioBitrate, fps },
      id
    );
  } catch (e: any) {
    throw new VideoCompressorError(
      e?.code === 'ERR_UNSUPPORTED_SOURCE' ? 'ERR_UNSUPPORTED_SOURCE' : 'ERR_ENCODING_FAILED',
      e?.message ?? 'transcode failed'
    );
  } finally {
    unsubscribe();
  }

  return { uri: outputUri, skipped: false };
}
