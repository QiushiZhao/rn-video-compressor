import { requireNativeModule, EventSubscription } from 'expo-modules-core';

type ProgressEvent = { id: string; progress: number };

// NOTE: expo-modules-core's public `EventEmitter` export is a constructor-
// type alias in 0.55, so `interface X extends EventEmitter<...>` doesn't
// inherit the instance methods. Declare `addListener` inline — at runtime
// every requireNativeModule'd module that calls `Events(...)` on the native
// side has it.
interface NativeModule {
  probeVideo(inputUri: string): Promise<{
    hasVideoTrack: boolean;
    width: number;
    height: number;
    bitrate: number;
    duration: number;
    videoMime: string;
    hasDecoder: boolean;
  }>;

  // Per-call `id` lets the JS side correlate event emissions back to the
  // right awaiter when multiple transcodes run concurrently.
  transcode(
    inputUri: string,
    outputUri: string,
    params: {
      width: number;
      height: number;
      videoBitrate: number;
      audioBitrate: number;
      fps: number;
    },
    id: string
  ): Promise<void>;

  addListener(
    eventName: 'onTranscodeProgress',
    listener: (event: ProgressEvent) => void
  ): EventSubscription;
}

export default requireNativeModule<NativeModule>('RnVideoCompressor');
