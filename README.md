# @qiushizhao/rn-video-compressor

Video compression for React Native (Expo). iOS uses NextLevelSessionExporter, Android uses [deepmedia/Transcoder](https://github.com/deepmedia/Transcoder).

Output is always MP4 / H.264 / AAC.

## Install

```bash
npm install @qiushizhao/rn-video-compressor
cd ios && pod install
```

Min targets: iOS 13, Android 24, Expo SDK 50+.

## Usage

```ts
import { compressAuto, compress } from '@qiushizhao/rn-video-compressor';

// Auto mode: short edge capped at 720p, bitrate = w*h*3 bps.
// If the source already fits (short edge ≤ 720 AND bitrate ≤ w*h*3),
// compressAuto skips re-encoding and returns the original URI.
const { uri, skipped } = await compressAuto(
  'file:///path/to/input.mp4',
  'file:///path/to/output.mp4',
  { onProgress: (p) => console.log(p) }
);

// Custom mode: you choose width / height / bitrates / fps.
await compress(
  'file:///path/to/input.mp4',
  'file:///path/to/output.mp4',
  {
    width: 640,
    height: 360,
    videoBitrate: 800_000,
    audioBitrate: 128_000, // optional, default 128000
    fps: 30,               // optional, default 30
    onProgress: (p) => console.log(p),
  }
);
```

## URI contract

- `inputUri` and `outputUri` must use `file://` scheme.
- `outputUri` must end with `.mp4` (case-insensitive).
- Output directory must exist — nothing is auto-created.
- When `compressAuto` returns `skipped: true`, `uri === inputUri` and no output file is written.

## Errors

Rejections throw `VideoCompressorError` with a `code`:

- `ERR_INPUT_NOT_FOUND` — input URI is not `file://`, missing, or unreadable.
- `ERR_OUTPUT_PATH_INVALID` — output URI is not `file://`, not `.mp4`, parent dir missing, or not writable.
- `ERR_UNSUPPORTED_SOURCE` — file has no video track.
- `ERR_ENCODING_FAILED` — underlying exporter/transcoder reported an error. `message` contains the native error text.

## Example

There is a working example app under `example/` demonstrating all three modes (auto, custom, intentional-error). Run it with:

```bash
cd example
npx expo prebuild --clean
npx expo run:ios     # or run:android
```
