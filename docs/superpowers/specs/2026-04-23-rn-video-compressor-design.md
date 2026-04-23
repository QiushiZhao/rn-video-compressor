# rn-video-compressor — Design

**Date:** 2026-04-23
**Package:** `@qiushizhao/rn-video-compressor`
**Location:** `/Users/jerry/dev/mine/rn-video-compressor/`

## Goal

An Expo Module providing two functions for video compression on React Native (Expo) apps:

- `compressAuto(inputUri, outputUri, options?)` — built-in rules, skips re-encoding when source already meets criteria.
- `compress(inputUri, outputUri, options)` — caller-provided encoding parameters.

iOS backend: [NextLevelSessionExporter](https://github.com/NextLevel/NextLevelSessionExporter).
Android backend: [natario1/Transcoder](https://github.com/natario1/Transcoder) (`com.otaliastudios:transcoder`).

Non-goals: cancellation, URI schemes other than `file://`, output containers other than MP4, codecs other than H.264/AAC, per-call codec overrides.

## Platform targets

- iOS deployment target: **13.0**
- Android `minSdkVersion`: **24**
- Expo SDK: **50+** (uses Expo Modules API)

## Package layout

```
rn-video-compressor/
├── package.json                    # name: @qiushizhao/rn-video-compressor
├── expo-module.config.json
├── tsconfig.json
├── src/
│   ├── index.ts                    # compressAuto, compress, types
│   ├── RnVideoCompressor.types.ts
│   └── RnVideoCompressorModule.ts  # requireNativeModule('RnVideoCompressor')
├── ios/
│   ├── RnVideoCompressor.podspec   # depends on NextLevelSessionExporter
│   ├── RnVideoCompressorModule.swift   # Expo Module definition
│   └── VideoCompressor.swift           # NextLevelSessionExporter wrapper + auto rule
├── android/
│   ├── build.gradle                # depends on com.otaliastudios:transcoder
│   └── src/main/java/expo/modules/rnvideocompressor/
│       ├── RnVideoCompressorModule.kt
│       └── VideoCompressor.kt      # Transcoder wrapper + auto rule
└── example/                         # Expo example app for manual verification
```

Scaffolding: generated via `create-expo-module @qiushizhao/rn-video-compressor`, then customized.

## JS API

```ts
// src/RnVideoCompressor.types.ts

export interface AutoOptions {
  onProgress?: (progress: number) => void;  // 0..1
}

export interface CustomOptions {
  width: number;
  height: number;
  videoBitrate: number;       // bits per second
  audioBitrate?: number;      // bits per second, default 128_000
  fps?: number;               // default 30
  onProgress?: (progress: number) => void;
}

export interface CompressResult {
  uri: string;        // file://... absolute path
  skipped: boolean;   // only compressAuto can return true; if true, uri === inputUri
}

export type ErrorCode =
  | 'ERR_INPUT_NOT_FOUND'
  | 'ERR_OUTPUT_PATH_INVALID'
  | 'ERR_UNSUPPORTED_SOURCE'
  | 'ERR_ENCODING_FAILED';
```

```ts
// src/index.ts

export function compressAuto(
  inputUri: string,            // must be file://...
  outputUri: string,           // must be file://... and end with .mp4
  options?: AutoOptions
): Promise<CompressResult>;

export function compress(
  inputUri: string,
  outputUri: string,
  options: CustomOptions
): Promise<CompressResult>;
```

### URI contract

- `inputUri`: `file://` scheme only. Callers that have `ph://` (iOS Photos) or `content://` (Android MediaStore) URIs are expected to resolve them to a local file path (e.g. via `expo-file-system`) before calling this library.
- `outputUri`: `file://` scheme, must end with `.mp4` (case-insensitive), parent directory must exist and be writable. No automatic mkdir.

### Progress callback

`onProgress` is passed as a JS function prop to the native module (Expo Modules API supports passing JS functions as arguments). Native code throttles progress emissions to at most one per ~100ms to avoid bridge saturation.

## Fixed output format

Both modes always produce:
- Container: **MP4**
- Video codec: **H.264**
- Audio codec: **AAC**, 2 channels, 44.1 kHz

Callers cannot override codec, container, channel count, or sample rate. iOS sets `optimizeForNetworkUse = true`. H.264 profile level: **High 4.0** (iOS `AVVideoProfileLevelH264High40`; Android Transcoder default profile/level is acceptable — no explicit override unless a compatibility issue is discovered during implementation).

## Auto mode (`compressAuto`)

### Step 1 — Probe source metadata

Read source video track info:
- iOS: `AVAsset` / `AVAssetTrack` → `naturalSize`, `estimatedDataRate`.
- Android: `MediaMetadataRetriever` → `METADATA_KEY_VIDEO_WIDTH/HEIGHT/BITRATE`.

Derive:
```
sourceWidth      = video track width
sourceHeight     = video track height
sourceShortEdge  = min(sourceWidth, sourceHeight)
sourceBitrate    = video track bitrate (bps)
```

If metadata reports no video track or probe fails, reject with `ERR_UNSUPPORTED_SOURCE`.

### Step 2 — Skip decision

```
if sourceShortEdge <= 720 AND sourceBitrate <= sourceWidth * sourceHeight * 3:
    resolve({ uri: inputUri, skipped: true })   // no copy, no re-encode
```

Skip returns the original `inputUri` verbatim. The output file at `outputUri` is **not** created.

### Step 3 — Compute target size

```
if sourceShortEdge <= 720:
    targetW, targetH = sourceWidth, sourceHeight
else:
    scale   = 720 / sourceShortEdge
    targetW = roundEven(sourceWidth  * scale)
    targetH = roundEven(sourceHeight * scale)

where roundEven(x) = round(x / 2) * 2   // even-integer rounding; encoders require even dims
```

### Step 4 — Encode

Video:
- Codec: H.264, High 4.0 profile level
- Size: `targetW × targetH`
- Bitrate: `ceil(targetW * targetH * 3)` bps
- FPS: 30
- Keyframe interval: 250 frames (iOS `AVVideoMaxKeyFrameIntervalKey: 250`; Android Transcoder: `250f / 30f ≈ 8.333` seconds, since Transcoder's `keyFrameInterval` is in seconds)

Audio:
- Codec: AAC (`kAudioFormatMPEG4AAC` / Android AAC)
- Channels: 2
- Sample rate: 44_100 Hz
- Bitrate: 128_000 bps

iOS — exact NextLevelSessionExporter config:
```swift
exportSession.outputFileType = .mp4
exportSession.outputURL = outputURL
exportSession.optimizeForNetworkUse = true
exportSession.videoOutputConfiguration = [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: targetW,
    AVVideoHeightKey: targetH,
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: ceil(Double(targetW) * Double(targetH) * 3),
        AVVideoProfileLevelKey: AVVideoProfileLevelH264High40,
        AVVideoExpectedSourceFrameRateKey: 30,
        AVVideoMaxKeyFrameIntervalKey: 250,
    ],
]
exportSession.audioOutputConfiguration = [
    AVFormatIDKey: kAudioFormatMPEG4AAC,
    AVNumberOfChannelsKey: 2,
    AVSampleRateKey: 44_100,
    AVEncoderBitRateKey: 128_000,
]
```

Android — Transcoder config outline:
```kotlin
val videoStrategy = DefaultVideoStrategy.Builder()
    .addResizer(ExactResizer(targetW, targetH))   // or equivalent resizer chain that yields exact target
    .bitRate((targetW.toLong() * targetH.toLong() * 3L))
    .frameRate(30)
    .keyFrameInterval(250f / 30f)
    .build()

val audioStrategy = DefaultAudioStrategy.Builder()
    .channels(2)
    .sampleRate(44_100)
    .bitRate(128_000)
    .build()

Transcoder.into(outputFilePath)
    .addDataSource(inputFilePath)
    .setVideoTrackStrategy(videoStrategy)
    .setAudioTrackStrategy(audioStrategy)
    .setListener(/* progress + completion */)
    .transcode()
```

On success: `resolve({ uri: outputUri, skipped: false })`.

## Custom mode (`compress`)

Pass caller-provided parameters straight through. **No probe, no skip, no auto-resize.**

- `width`, `height`: applied as-is. If either is odd, native rounds down to the nearest even integer before handing to the encoder (encoders reject odd dimensions). No error raised for odd input.
- `videoBitrate`: applied as-is.
- `audioBitrate`: default `128_000`.
- `fps`: default `30`.
- Codec, container, audio channels, audio sample rate, H.264 profile level, keyframe interval: identical to auto mode. Not caller-configurable.

On success: `resolve({ uri: outputUri, skipped: false })`.

## Error handling

All errors are Promise rejections. Error objects carry `code` (one of the `ErrorCode` literals) and `message`.

| Code | Trigger |
|---|---|
| `ERR_INPUT_NOT_FOUND` | `inputUri` is not `file://`, does not exist, or is not readable. |
| `ERR_OUTPUT_PATH_INVALID` | `outputUri` is not `file://`, does not end with `.mp4` (case-insensitive), parent directory does not exist, or target is not writable. |
| `ERR_UNSUPPORTED_SOURCE` | Source file opens but contains no video track or is otherwise unreadable by `AVAsset` / `MediaMetadataRetriever`. |
| `ERR_ENCODING_FAILED` | Underlying exporter/transcoder reported a failure. `message` contains the raw native error description. |

Validation order: all precondition checks (`ERR_INPUT_NOT_FOUND`, `ERR_OUTPUT_PATH_INVALID`, `ERR_UNSUPPORTED_SOURCE`) happen synchronously before starting the exporter, so they fail fast.

## Testing

No automated unit tests. Video compression requires a real device/simulator with real media files — the cost of fixtures and CI setup outweighs the value for a library this thin.

Verification is done via the `example/` Expo app, which exposes buttons to exercise:

- `compressAuto` on ≤720p short-edge source (expect `skipped: true`, `uri === inputUri`, no file written to `outputUri`)
- `compressAuto` on 1080p landscape source (expect 1280×720 output)
- `compressAuto` on 1080p portrait source (expect 720×1280 output)
- `compressAuto` on 4K source (expect short-edge-720 output)
- `compress` with explicit params (e.g., 640×360 @ 800 kbps)
- Progress callback reaches 1.0 on completion
- `ERR_INPUT_NOT_FOUND`: non-existent input path
- `ERR_OUTPUT_PATH_INVALID`: output path without `.mp4` suffix, output path under non-existent directory
- `ERR_UNSUPPORTED_SOURCE`: audio-only file (e.g., `.m4a`)

The example app displays source/output file sizes and decoded dimensions for manual comparison.

## Open items

None at spec time. Any implementation-time surprises (e.g., Android Transcoder's resizer API not exposing an `ExactResizer`, or profile/level compatibility issues) are flagged during the implementation plan, not in this spec.
