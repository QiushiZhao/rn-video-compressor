import { NativeModule, requireNativeModule } from 'expo';

import { RnVideoCompressorModuleEvents } from './RnVideoCompressor.types';

declare class RnVideoCompressorModule extends NativeModule<RnVideoCompressorModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<RnVideoCompressorModule>('RnVideoCompressor');
