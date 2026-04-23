import { requireNativeView } from 'expo';
import * as React from 'react';

import { RnVideoCompressorViewProps } from './RnVideoCompressor.types';

const NativeView: React.ComponentType<RnVideoCompressorViewProps> =
  requireNativeView('RnVideoCompressor');

export default function RnVideoCompressorView(props: RnVideoCompressorViewProps) {
  return <NativeView {...props} />;
}
