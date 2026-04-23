import { registerWebModule, NativeModule } from 'expo';

import { RnVideoCompressorModuleEvents } from './RnVideoCompressor.types';

class RnVideoCompressorModule extends NativeModule<RnVideoCompressorModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(RnVideoCompressorModule, 'RnVideoCompressorModule');
