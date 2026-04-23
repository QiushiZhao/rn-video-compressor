import * as React from 'react';

import { RnVideoCompressorViewProps } from './RnVideoCompressor.types';

export default function RnVideoCompressorView(props: RnVideoCompressorViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
