import { requireNativeModule } from 'expo-modules-core';

interface NativeModule {
  probeVideo(inputUri: string): Promise<{
    hasVideoTrack: boolean;
    width: number;
    height: number;
    bitrate: number;
  }>;

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
    onProgress: (progress: number) => void
  ): Promise<void>;
}

export default requireNativeModule<NativeModule>('RnVideoCompressor');
