# rn-video-compressor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `@qiushizhao/rn-video-compressor`, an Expo Module that compresses video files on iOS (NextLevelSessionExporter) and Android (deepmedia/Transcoder), with a built-in "auto" rule set and a pass-through "custom" mode.

**Architecture:** Native modules expose two thin primitives — `probeVideo` (metadata only) and `transcode` (H.264/AAC MP4 with explicit target params). All auto-mode logic (skip decision, target-size computation) lives in TypeScript so it has a single source of truth and is unit-testable. The JS `compressAuto` / `compress` functions orchestrate validation → probe → compute → transcode.

**Tech Stack:** Expo Modules API, Swift (iOS 13+), Kotlin (Android minSdk 24), TypeScript, Jest (for pure TS helpers only), NextLevelSessionExporter 1.0.1+, `io.deepmedia.community:transcoder-android:0.11.2`.

**Spec:** `docs/superpowers/specs/2026-04-23-rn-video-compressor-design.md`.

---

## File structure

```
rn-video-compressor/
├── package.json                          # @qiushizhao/rn-video-compressor, SDK 50+
├── expo-module.config.json
├── tsconfig.json
├── jest.config.js                         # pure TS tests only
├── README.md
├── .gitignore
├── src/
│   ├── index.ts                           # compressAuto, compress, re-exports types
│   ├── RnVideoCompressor.types.ts         # AutoOptions, CustomOptions, CompressResult, ErrorCode
│   ├── RnVideoCompressorModule.ts         # requireNativeModule wrapper (probeVideo, transcode)
│   ├── validate.ts                        # input/output URI validation helpers
│   ├── autoRules.ts                       # shouldSkip, computeTargetSize, roundEven, computeBitrate
│   ├── validate.test.ts
│   └── autoRules.test.ts
├── ios/
│   ├── RnVideoCompressor.podspec          # depends on NextLevelSessionExporter
│   ├── RnVideoCompressorModule.swift      # Expo Module definition + argument routing
│   ├── VideoProbe.swift                   # AVAsset probing
│   └── VideoCompressor.swift              # NextLevelSessionExporter wrapper
├── android/
│   ├── build.gradle                       # deepmedia transcoder dep
│   └── src/main/java/expo/modules/rnvideocompressor/
│       ├── RnVideoCompressorModule.kt     # Expo Module definition
│       ├── VideoProbe.kt                  # MediaMetadataRetriever probing
│       └── VideoCompressor.kt             # Transcoder wrapper
└── example/                                # expo example app for manual verification
    └── App.tsx                             # pick-video + run-auto/custom UI
```

**File responsibility boundaries:**
- `validate.ts` — nothing but URI shape/scheme/suffix checks. Throws typed errors.
- `autoRules.ts` — pure numeric helpers. No I/O, no platform code.
- `RnVideoCompressorModule.ts` — one-liner `requireNativeModule<NativeType>('RnVideoCompressor')`; type for native calls.
- `index.ts` — only orchestration + public API.
- iOS `VideoProbe.swift`, `VideoCompressor.swift` — one class each, one responsibility.
- Same split on Android.

---

## Native interface (crosses JS/native boundary)

```ts
// Native module shape — what TS sees via requireNativeModule
interface NativeModule {
  probeVideo(inputUri: string): Promise<{
    hasVideoTrack: boolean;
    width: number;         // 0 if !hasVideoTrack
    height: number;        // 0 if !hasVideoTrack
    bitrate: number;       // bps, 0 if unknown
  }>;

  transcode(
    inputUri: string,
    outputUri: string,
    params: {
      width: number;         // must be even
      height: number;        // must be even
      videoBitrate: number;  // bps
      audioBitrate: number;  // bps
      fps: number;
    },
    onProgress: (progress: number) => void  // 0..1, throttled ~100ms by native
  ): Promise<void>;  // rejects with code ERR_UNSUPPORTED_SOURCE or ERR_ENCODING_FAILED
}
```

Both native platforms implement this exact shape. `ERR_INPUT_NOT_FOUND` and `ERR_OUTPUT_PATH_INVALID` are thrown in TS before the native calls.

---

## Task 1: Scaffold project via create-expo-module

**Files:**
- Create: entire repo structure under `rn-video-compressor/`

**Note on working dir:** All tasks run from `/Users/jerry/dev/mine/rn-video-compressor/`. The directory is already a git repo with only `docs/` inside.

- [ ] **Step 1: Run create-expo-module (local mode, not-to-publish layout)**

```bash
cd /Users/jerry/dev/mine
npx create-expo-module@latest rn-video-compressor-tmp --no-example
```

The CLI will prompt for name/author/etc. Use:
- Package slug: `rn-video-compressor`
- NPM package name: `@qiushizhao/rn-video-compressor`
- Description: `Video compression for React Native (Expo) on iOS + Android`
- GitHub username/profile: your preference
- Module name (native): `RnVideoCompressor`

Expected: new dir `rn-video-compressor-tmp/` created alongside our target.

- [ ] **Step 2: Merge generated files into existing rn-video-compressor/**

```bash
cd /Users/jerry/dev/mine
rsync -a --exclude='.git' rn-video-compressor-tmp/ rn-video-compressor/
rm -rf rn-video-compressor-tmp
cd rn-video-compressor
ls
```

Expected: `rn-video-compressor/` now has `package.json`, `expo-module.config.json`, `src/`, `ios/`, `android/`, `tsconfig.json`, plus the preexisting `docs/`.

- [ ] **Step 3: Add example app**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
npx create-expo-app@latest example --template blank-typescript
```

Expected: `example/` created with its own `package.json`.

- [ ] **Step 4: Configure example to use the parent module**

Edit `example/package.json` — add a local dependency via file: path (npm workspaces not needed at this scale):

```json
{
  "dependencies": {
    "@qiushizhao/rn-video-compressor": "file:.."
  }
}
```

Then:
```bash
cd example && npm install
```

- [ ] **Step 5: Initial commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add .
git commit -m "chore: scaffold expo module with example app"
```

---

## Task 2: Configure native dependencies and min targets

**Files:**
- Modify: `ios/RnVideoCompressor.podspec`
- Modify: `android/build.gradle`
- Modify: `expo-module.config.json` (if platforms need explicit listing)

- [ ] **Step 1: Add NextLevelSessionExporter to podspec**

Edit `ios/RnVideoCompressor.podspec`. Find the `Pod::Spec.new do |s|` block and ensure these lines are present/updated:

```ruby
s.platform       = :ios, '13.0'
s.dependency 'ExpoModulesCore'
s.dependency 'NextLevelSessionExporter', '~> 1.0.1'
```

- [ ] **Step 2: Add Transcoder to Android build.gradle**

Edit `android/build.gradle`. Ensure the `android` block contains:

```groovy
android {
    compileSdkVersion safeExtGet("compileSdkVersion", 34)
    defaultConfig {
        minSdkVersion safeExtGet("minSdkVersion", 24)
        targetSdkVersion safeExtGet("targetSdkVersion", 34)
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
```

And in `dependencies`:

```groovy
dependencies {
    implementation project(':expo-modules-core')
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"
    implementation 'io.deepmedia.community:transcoder-android:0.11.2'
}
```

If the template uses `mavenCentral()` only, Transcoder is on Maven Central so no extra repos needed.

- [ ] **Step 3: Verify Android build resolves**

```bash
cd example
npx expo prebuild --platform android --clean
cd android && ./gradlew :expo-modules-core:help --quiet
```

Expected: no unresolved dependency errors. (Full build not required yet.)

- [ ] **Step 4: Verify iOS pod install resolves**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor/example
npx expo prebuild --platform ios --clean
cd ios && pod install
```

Expected: `Installing NextLevelSessionExporter (1.0.1)` appears in output.

- [ ] **Step 5: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add ios/RnVideoCompressor.podspec android/build.gradle
git commit -m "chore: pin native deps (NextLevelSessionExporter, Transcoder) and min targets"
```

---

## Task 3: Define TypeScript types

**Files:**
- Create/Replace: `src/RnVideoCompressor.types.ts`

- [ ] **Step 1: Write the types file**

Replace `src/RnVideoCompressor.types.ts` with:

```ts
export interface AutoOptions {
  onProgress?: (progress: number) => void;
}

export interface CustomOptions {
  width: number;
  height: number;
  videoBitrate: number;
  audioBitrate?: number;
  fps?: number;
  onProgress?: (progress: number) => void;
}

export interface CompressResult {
  uri: string;
  skipped: boolean;
}

export type ErrorCode =
  | 'ERR_INPUT_NOT_FOUND'
  | 'ERR_OUTPUT_PATH_INVALID'
  | 'ERR_UNSUPPORTED_SOURCE'
  | 'ERR_ENCODING_FAILED';

export class VideoCompressorError extends Error {
  code: ErrorCode;
  constructor(code: ErrorCode, message: string) {
    super(message);
    this.name = 'VideoCompressorError';
    this.code = code;
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/RnVideoCompressor.types.ts
git commit -m "feat: define public types and error class"
```

---

## Task 4: Pure helpers (validation) with tests

**Files:**
- Create: `src/validate.ts`
- Create: `src/validate.test.ts`
- Modify: `package.json` (add jest devDependencies + test script)
- Create: `jest.config.js`

- [ ] **Step 1: Add jest**

Modify `package.json`:

```json
{
  "scripts": {
    "test": "jest"
  },
  "devDependencies": {
    "jest": "^29.7.0",
    "ts-jest": "^29.1.2",
    "@types/jest": "^29.5.12"
  }
}
```

Create `jest.config.js`:

```js
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/src/**/*.test.ts'],
};
```

Run: `npm install`.

- [ ] **Step 2: Write failing tests for validate.ts**

Create `src/validate.test.ts`:

```ts
import { validateInputUri, validateOutputUri } from './validate';
import { VideoCompressorError } from './RnVideoCompressor.types';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

describe('validateInputUri', () => {
  let existing: string;
  beforeAll(() => {
    existing = path.join(os.tmpdir(), `rnvc-in-${Date.now()}.mp4`);
    fs.writeFileSync(existing, 'x');
  });
  afterAll(() => fs.unlinkSync(existing));

  it('accepts existing file:// uri', () => {
    expect(() => validateInputUri('file://' + existing)).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateInputUri('content://media/foo')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });

  it('rejects missing file', () => {
    expect(() => validateInputUri('file:///definitely/not/here.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_INPUT_NOT_FOUND' })
    );
  });
});

describe('validateOutputUri', () => {
  const tmp = os.tmpdir();

  it('accepts writable path ending in .mp4', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.mp4'))).not.toThrow();
  });

  it('accepts .MP4 (case-insensitive)', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.MP4'))).not.toThrow();
  });

  it('rejects non-file:// scheme', () => {
    expect(() => validateOutputUri('/no/scheme/out.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });

  it('rejects missing .mp4 suffix', () => {
    expect(() => validateOutputUri('file://' + path.join(tmp, 'out.mov'))).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });

  it('rejects parent dir that does not exist', () => {
    expect(() => validateOutputUri('file:///no/such/dir/out.mp4')).toThrow(
      expect.objectContaining({ code: 'ERR_OUTPUT_PATH_INVALID' })
    );
  });
});
```

- [ ] **Step 3: Run tests, verify they fail**

Run: `npm test`
Expected: FAIL — cannot find module `./validate`.

- [ ] **Step 4: Implement validate.ts**

Create `src/validate.ts`:

```ts
import * as fs from 'fs';
import * as path from 'path';
import { VideoCompressorError } from './RnVideoCompressor.types';

const FILE_SCHEME = 'file://';

function stripScheme(uri: string): string | null {
  if (!uri.startsWith(FILE_SCHEME)) return null;
  return uri.slice(FILE_SCHEME.length);
}

export function validateInputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null) {
    throw new VideoCompressorError('ERR_INPUT_NOT_FOUND', `inputUri must be file://, got: ${uri}`);
  }
  try {
    fs.accessSync(p, fs.constants.R_OK);
  } catch {
    throw new VideoCompressorError('ERR_INPUT_NOT_FOUND', `input file not readable: ${p}`);
  }
  return p;
}

export function validateOutputUri(uri: string): string {
  const p = stripScheme(uri);
  if (p === null) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must be file://, got: ${uri}`);
  }
  if (!/\.mp4$/i.test(p)) {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `outputUri must end with .mp4, got: ${p}`);
  }
  const dir = path.dirname(p);
  try {
    fs.accessSync(dir, fs.constants.W_OK);
  } catch {
    throw new VideoCompressorError('ERR_OUTPUT_PATH_INVALID', `output directory not writable: ${dir}`);
  }
  return p;
}
```

- [ ] **Step 5: Run tests, verify they pass**

Run: `npm test`
Expected: all 8 tests pass.

- [ ] **Step 6: Commit**

```bash
git add package.json package-lock.json jest.config.js src/validate.ts src/validate.test.ts
git commit -m "feat: add URI validation helpers with tests"
```

---

## Task 5: Pure helpers (auto rules) with tests

**Files:**
- Create: `src/autoRules.ts`
- Create: `src/autoRules.test.ts`

- [ ] **Step 1: Write failing tests**

Create `src/autoRules.test.ts`:

```ts
import { roundEven, computeTargetSize, shouldSkip, computeBitrate } from './autoRules';

describe('roundEven', () => {
  it('keeps even numbers', () => {
    expect(roundEven(720)).toBe(720);
  });
  it('rounds odd numbers to nearest even', () => {
    expect(roundEven(721)).toBe(722);
    expect(roundEven(719)).toBe(720);
  });
  it('handles non-integer input', () => {
    expect(roundEven(405.5)).toBe(406);
  });
});

describe('computeTargetSize (short edge cap 720)', () => {
  it('keeps size when short edge <= 720', () => {
    expect(computeTargetSize(1280, 720)).toEqual({ width: 1280, height: 720 });
    expect(computeTargetSize(640, 480)).toEqual({ width: 640, height: 480 });
  });
  it('scales 1920x1080 (landscape) to 1280x720', () => {
    expect(computeTargetSize(1920, 1080)).toEqual({ width: 1280, height: 720 });
  });
  it('scales 1080x1920 (portrait) to 720x1280', () => {
    expect(computeTargetSize(1080, 1920)).toEqual({ width: 720, height: 1280 });
  });
  it('scales 3840x2160 (4K landscape) to 1280x720', () => {
    expect(computeTargetSize(3840, 2160)).toEqual({ width: 1280, height: 720 });
  });
  it('produces even dimensions', () => {
    const { width, height } = computeTargetSize(1281, 1921);
    expect(width % 2).toBe(0);
    expect(height % 2).toBe(0);
  });
});

describe('shouldSkip', () => {
  it('true when short edge <= 720 AND bitrate <= w*h*3', () => {
    expect(shouldSkip(1280, 720, 1280 * 720 * 3)).toBe(true);
    expect(shouldSkip(640, 480, 100_000)).toBe(true);
  });
  it('false when short edge > 720', () => {
    expect(shouldSkip(1920, 1080, 1_000_000)).toBe(false);
  });
  it('false when bitrate exceeds w*h*3', () => {
    expect(shouldSkip(1280, 720, 1280 * 720 * 3 + 1)).toBe(false);
  });
  it('treats unknown bitrate (0) as skippable only if dims fit', () => {
    expect(shouldSkip(1280, 720, 0)).toBe(true);
    expect(shouldSkip(1920, 1080, 0)).toBe(false);
  });
});

describe('computeBitrate', () => {
  it('equals ceil(w * h * 3)', () => {
    expect(computeBitrate(1280, 720)).toBe(1280 * 720 * 3);
    expect(computeBitrate(720, 1280)).toBe(720 * 1280 * 3);
  });
});
```

- [ ] **Step 2: Run tests, verify failure**

Run: `npm test -- autoRules`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement autoRules.ts**

Create `src/autoRules.ts`:

```ts
export function roundEven(x: number): number {
  return Math.round(x / 2) * 2;
}

const SHORT_EDGE_CAP = 720;
const BITRATE_FACTOR = 3;

export function computeTargetSize(
  sourceWidth: number,
  sourceHeight: number
): { width: number; height: number } {
  const shortEdge = Math.min(sourceWidth, sourceHeight);
  if (shortEdge <= SHORT_EDGE_CAP) {
    return { width: sourceWidth, height: sourceHeight };
  }
  const scale = SHORT_EDGE_CAP / shortEdge;
  return {
    width: roundEven(sourceWidth * scale),
    height: roundEven(sourceHeight * scale),
  };
}

export function shouldSkip(
  sourceWidth: number,
  sourceHeight: number,
  sourceBitrate: number
): boolean {
  const shortEdge = Math.min(sourceWidth, sourceHeight);
  if (shortEdge > SHORT_EDGE_CAP) return false;
  const threshold = sourceWidth * sourceHeight * BITRATE_FACTOR;
  return sourceBitrate <= threshold;
}

export function computeBitrate(width: number, height: number): number {
  return Math.ceil(width * height * BITRATE_FACTOR);
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `npm test`
Expected: all tests pass (validate.test.ts + autoRules.test.ts).

- [ ] **Step 5: Commit**

```bash
git add src/autoRules.ts src/autoRules.test.ts
git commit -m "feat: add auto-mode pure helpers (skip decision, target size, bitrate)"
```

---

## Task 6: Native module skeletons (probe + transcode stubs)

**Files:**
- Modify: `ios/RnVideoCompressorModule.swift`
- Modify: `android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt`
- Create/Replace: `src/RnVideoCompressorModule.ts`

- [ ] **Step 1: iOS module skeleton**

Replace `ios/RnVideoCompressorModule.swift`:

```swift
import ExpoModulesCore

public class RnVideoCompressorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("RnVideoCompressor")

    AsyncFunction("probeVideo") { (inputUri: String, promise: Promise) in
      // Implemented in Task 7
      promise.reject("ERR_ENCODING_FAILED", "probeVideo not yet implemented")
    }

    AsyncFunction("transcode") { (
      inputUri: String,
      outputUri: String,
      params: TranscodeParams,
      onProgress: JavaScriptFunction<Void>,
      promise: Promise
    ) in
      // Implemented in Task 9
      promise.reject("ERR_ENCODING_FAILED", "transcode not yet implemented")
    }
  }
}

struct TranscodeParams: Record {
  @Field var width: Int
  @Field var height: Int
  @Field var videoBitrate: Int
  @Field var audioBitrate: Int
  @Field var fps: Int
}
```

- [ ] **Step 2: Android module skeleton**

Replace `android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt`:

```kotlin
package expo.modules.rnvideocompressor

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.types.JavaScriptFunction

class RnVideoCompressorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("RnVideoCompressor")

    AsyncFunction("probeVideo") { inputUri: String, promise: Promise ->
      // Implemented in Task 8
      promise.reject("ERR_ENCODING_FAILED", "probeVideo not yet implemented", null)
    }

    AsyncFunction("transcode") { inputUri: String,
                                  outputUri: String,
                                  params: TranscodeParams,
                                  onProgress: JavaScriptFunction<Unit>,
                                  promise: Promise ->
      // Implemented in Task 10
      promise.reject("ERR_ENCODING_FAILED", "transcode not yet implemented", null)
    }
  }
}

class TranscodeParams : Record {
  @Field var width: Int = 0
  @Field var height: Int = 0
  @Field var videoBitrate: Int = 0
  @Field var audioBitrate: Int = 0
  @Field var fps: Int = 0
}
```

- [ ] **Step 3: TS wrapper**

Replace `src/RnVideoCompressorModule.ts`:

```ts
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
```

- [ ] **Step 4: Build example to confirm both natives compile**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor/example
npx expo prebuild --clean
# iOS
cd ios && pod install && cd ..
npx expo run:ios --no-install
```

Expected: app builds and launches in simulator. (Calls to probe/transcode will reject with "not yet implemented" but we don't wire them up yet.)

```bash
npx expo run:android --no-install
```

Expected: android build succeeds.

- [ ] **Step 5: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add ios/ android/ src/RnVideoCompressorModule.ts
git commit -m "feat: add native module skeletons with probeVideo/transcode stubs"
```

---

## Task 7: iOS — implement probeVideo

**Files:**
- Create: `ios/VideoProbe.swift`
- Modify: `ios/RnVideoCompressorModule.swift`

- [ ] **Step 1: Implement VideoProbe.swift**

Create `ios/VideoProbe.swift`:

```swift
import AVFoundation
import Foundation

struct ProbeResult {
  let hasVideoTrack: Bool
  let width: Int
  let height: Int
  let bitrate: Int
}

enum VideoProbe {
  static func probe(fileUri: String) throws -> ProbeResult {
    guard let url = URL(string: fileUri) else {
      throw NSError(domain: "RnVideoCompressor", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "invalid URL: \(fileUri)"])
    }
    let asset = AVURLAsset(url: url)

    guard let track = asset.tracks(withMediaType: .video).first else {
      return ProbeResult(hasVideoTrack: false, width: 0, height: 0, bitrate: 0)
    }

    // naturalSize gives raw pixel size; preferredTransform-aware "display" size
    // is NOT needed here since we track width/height as-is (encoder sees rotation metadata).
    let size = track.naturalSize.applying(track.preferredTransform)
    let width = abs(Int(size.width))
    let height = abs(Int(size.height))
    let bitrate = Int(track.estimatedDataRate.rounded())

    return ProbeResult(hasVideoTrack: true, width: width, height: height, bitrate: bitrate)
  }
}
```

- [ ] **Step 2: Wire it into the module**

In `ios/RnVideoCompressorModule.swift`, replace the `probeVideo` stub body:

```swift
AsyncFunction("probeVideo") { (inputUri: String, promise: Promise) in
  do {
    let result = try VideoProbe.probe(fileUri: inputUri)
    promise.resolve([
      "hasVideoTrack": result.hasVideoTrack,
      "width": result.width,
      "height": result.height,
      "bitrate": result.bitrate,
    ])
  } catch {
    promise.reject("ERR_UNSUPPORTED_SOURCE", error.localizedDescription)
  }
}
```

- [ ] **Step 3: Manual smoke test via example app**

Temporarily add to `example/App.tsx` (we'll replace this in Task 12):

```tsx
import { useEffect } from 'react';
import NativeModule from '@qiushizhao/rn-video-compressor/src/RnVideoCompressorModule';

useEffect(() => {
  // Use any file:// URI to a video known to exist on the simulator.
  NativeModule.probeVideo('file:///path/to/test.mp4').then(console.log).catch(console.warn);
}, []);
```

Run:
```bash
cd example && npx expo run:ios
```

Expected: Xcode simulator console shows `{ hasVideoTrack: true, width: N, height: N, bitrate: N }`.

- [ ] **Step 4: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add ios/VideoProbe.swift ios/RnVideoCompressorModule.swift
git commit -m "feat(ios): implement probeVideo via AVAsset"
```

---

## Task 8: Android — implement probeVideo

**Files:**
- Create: `android/src/main/java/expo/modules/rnvideocompressor/VideoProbe.kt`
- Modify: `android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt`

- [ ] **Step 1: Implement VideoProbe.kt**

Create `android/src/main/java/expo/modules/rnvideocompressor/VideoProbe.kt`:

```kotlin
package expo.modules.rnvideocompressor

import android.media.MediaMetadataRetriever
import android.net.Uri

data class ProbeResult(
  val hasVideoTrack: Boolean,
  val width: Int,
  val height: Int,
  val bitrate: Int,
)

object VideoProbe {
  fun probe(fileUri: String): ProbeResult {
    val retriever = MediaMetadataRetriever()
    try {
      val uri = Uri.parse(fileUri)
      val path = uri.path ?: throw IllegalArgumentException("no path in URI: $fileUri")
      retriever.setDataSource(path)

      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
      if (!hasVideo) return ProbeResult(false, 0, 0, 0)

      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0

      return ProbeResult(true, width, height, bitrate)
    } finally {
      retriever.release()
    }
  }
}
```

- [ ] **Step 2: Wire it into the module**

In `RnVideoCompressorModule.kt`, replace the `probeVideo` stub body:

```kotlin
AsyncFunction("probeVideo") { inputUri: String, promise: Promise ->
  try {
    val result = VideoProbe.probe(inputUri)
    promise.resolve(mapOf(
      "hasVideoTrack" to result.hasVideoTrack,
      "width" to result.width,
      "height" to result.height,
      "bitrate" to result.bitrate,
    ))
  } catch (e: Throwable) {
    promise.reject("ERR_UNSUPPORTED_SOURCE", e.message ?: "probe failed", e)
  }
}
```

- [ ] **Step 3: Smoke test**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor/example
npx expo run:android
```

Push a test video to the emulator:
```bash
adb push ~/some-test.mp4 /sdcard/Download/test.mp4
```

Call `probeVideo('file:///sdcard/Download/test.mp4')` from the app (add to App.tsx temporarily as in Task 7 Step 3). Expected: logs show `hasVideoTrack: true` with valid width/height.

- [ ] **Step 4: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add android/src/main/java/expo/modules/rnvideocompressor/VideoProbe.kt \
        android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt
git commit -m "feat(android): implement probeVideo via MediaMetadataRetriever"
```

---

## Task 9: iOS — implement transcode with NextLevelSessionExporter

**Files:**
- Create: `ios/VideoCompressor.swift`
- Modify: `ios/RnVideoCompressorModule.swift`

- [ ] **Step 1: Implement VideoCompressor.swift**

Create `ios/VideoCompressor.swift`:

```swift
import AVFoundation
import NextLevelSessionExporter
import Foundation

struct TranscodeParamsSwift {
  let width: Int
  let height: Int
  let videoBitrate: Int
  let audioBitrate: Int
  let fps: Int
}

enum VideoCompressor {
  static func transcode(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsSwift,
    progress: @escaping (Float) -> Void,
    completion: @escaping (Result<Void, Error>) -> Void
  ) {
    guard let inputURL = URL(string: inputUri),
          let outputURL = URL(string: outputUri) else {
      completion(.failure(makeError("ERR_ENCODING_FAILED", "invalid URL")))
      return
    }

    // Remove any pre-existing file at outputURL (exporter will fail otherwise).
    try? FileManager.default.removeItem(at: outputURL)

    let asset = AVAsset(url: inputURL)
    let exporter = NextLevelSessionExporter(withAsset: asset)
    exporter.outputFileType = .mp4
    exporter.outputURL = outputURL
    exporter.optimizeForNetworkUse = true
    exporter.videoOutputConfiguration = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: params.width,
      AVVideoHeightKey: params.height,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: params.videoBitrate,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264High40,
        AVVideoExpectedSourceFrameRateKey: params.fps,
        AVVideoMaxKeyFrameIntervalKey: 250,
      ],
    ]
    exporter.audioOutputConfiguration = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVNumberOfChannelsKey: 2,
      AVSampleRateKey: 44_100,
      AVEncoderBitRateKey: params.audioBitrate,
    ]

    // Throttle progress to ~10/s
    var lastEmit = Date.distantPast
    exporter.export(progressHandler: { p in
      let now = Date()
      if now.timeIntervalSince(lastEmit) >= 0.1 {
        lastEmit = now
        progress(p)
      }
    }, completionHandler: { result in
      switch result {
      case .success(.completed):
        progress(1.0)
        completion(.success(()))
      case .success(let status):
        completion(.failure(makeError("ERR_ENCODING_FAILED", "export status: \(status)")))
      case .failure(let err):
        completion(.failure(makeError("ERR_ENCODING_FAILED", err.localizedDescription)))
      }
    })
  }

  private static func makeError(_ code: String, _ message: String) -> NSError {
    NSError(domain: "RnVideoCompressor", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "\(code): \(message)"])
  }
}
```

> **Note on NextLevelSessionExporter API:** Version 1.0.1 exposes `export(progressHandler:completionHandler:)` (closure-based). If the installed version exposes an async variant instead, adapt to `for try await event in exporter.exportAsync()` — progress events via `.progress(Float)`, completion via `.completed(URL)`. Behavior is equivalent.

- [ ] **Step 2: Wire into module**

In `ios/RnVideoCompressorModule.swift`, replace the `transcode` stub body:

```swift
AsyncFunction("transcode") { (
  inputUri: String,
  outputUri: String,
  params: TranscodeParams,
  onProgress: JavaScriptFunction<Void>,
  promise: Promise
) in
  let swiftParams = TranscodeParamsSwift(
    width: params.width,
    height: params.height,
    videoBitrate: params.videoBitrate,
    audioBitrate: params.audioBitrate,
    fps: params.fps
  )

  VideoCompressor.transcode(
    inputUri: inputUri,
    outputUri: outputUri,
    params: swiftParams,
    progress: { p in
      try? onProgress.call(Double(p))
    },
    completion: { result in
      switch result {
      case .success: promise.resolve(nil)
      case .failure(let err):
        let msg = err.localizedDescription
        let code = msg.contains("no video track") ? "ERR_UNSUPPORTED_SOURCE" : "ERR_ENCODING_FAILED"
        promise.reject(code, msg)
      }
    }
  )
}
```

- [ ] **Step 3: Smoke test via example app**

Add a temporary button in `App.tsx`:

```tsx
import NativeModule from '@qiushizhao/rn-video-compressor/src/RnVideoCompressorModule';

<Button title="Test transcode" onPress={async () => {
  const input = 'file:///path/to/test-1080p.mp4';
  const output = `file://${RNFS.DocumentDirectoryPath}/out.mp4`;  // or use expo-file-system
  await NativeModule.transcode(input, output,
    { width: 1280, height: 720, videoBitrate: 1280*720*3, audioBitrate: 128000, fps: 30 },
    (p) => console.log('progress', p));
  console.log('done');
}} />
```

Expected: progress logs 0→1, output file plays in VLC/QuickTime.

- [ ] **Step 4: Commit**

```bash
git add ios/VideoCompressor.swift ios/RnVideoCompressorModule.swift
git commit -m "feat(ios): implement transcode with NextLevelSessionExporter"
```

---

## Task 10: Android — implement transcode with Transcoder

**Files:**
- Create: `android/src/main/java/expo/modules/rnvideocompressor/VideoCompressor.kt`
- Modify: `android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt`

- [ ] **Step 1: Implement VideoCompressor.kt**

Create `android/src/main/java/expo/modules/rnvideocompressor/VideoCompressor.kt`:

```kotlin
package expo.modules.rnvideocompressor

import android.net.Uri
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.ExactResizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy

data class TranscodeParamsKt(
  val width: Int,
  val height: Int,
  val videoBitrate: Int,
  val audioBitrate: Int,
  val fps: Int,
)

object VideoCompressor {
  fun transcode(
    inputUri: String,
    outputUri: String,
    params: TranscodeParamsKt,
    onProgress: (Double) -> Unit,
    onCompleted: () -> Unit,
    onFailed: (Throwable) -> Unit,
  ) {
    val inputPath = Uri.parse(inputUri).path
      ?: return onFailed(IllegalArgumentException("invalid input uri: $inputUri"))
    val outputPath = Uri.parse(outputUri).path
      ?: return onFailed(IllegalArgumentException("invalid output uri: $outputUri"))

    val videoStrategy = DefaultVideoStrategy.Builder()
      .addResizer(ExactResizer(params.width, params.height))
      .bitRate(params.videoBitrate.toLong())
      .frameRate(params.fps)
      .keyFrameInterval(250f / params.fps.toFloat())
      .build()

    val audioStrategy = DefaultAudioStrategy.Builder()
      .channels(2)
      .sampleRate(44_100)
      .bitRate(params.audioBitrate.toLong())
      .build()

    var lastEmitMs = 0L

    Transcoder.into(outputPath)
      .addDataSource(inputPath)
      .setVideoTrackStrategy(videoStrategy)
      .setAudioTrackStrategy(audioStrategy)
      .setListener(object : TranscoderListener {
        override fun onTranscodeProgress(progress: Double) {
          val now = System.currentTimeMillis()
          if (now - lastEmitMs >= 100) {
            lastEmitMs = now
            onProgress(progress)
          }
        }
        override fun onTranscodeCompleted(successCode: Int) {
          onProgress(1.0)
          onCompleted()
        }
        override fun onTranscodeCanceled() {
          onFailed(IllegalStateException("canceled"))
        }
        override fun onTranscodeFailed(exception: Throwable) {
          onFailed(exception)
        }
      })
      .transcode()
  }
}
```

- [ ] **Step 2: Wire into module**

In `RnVideoCompressorModule.kt`, replace the `transcode` stub body:

```kotlin
AsyncFunction("transcode") { inputUri: String,
                              outputUri: String,
                              params: TranscodeParams,
                              onProgress: JavaScriptFunction<Unit>,
                              promise: Promise ->
  val ktParams = TranscodeParamsKt(
    width = params.width,
    height = params.height,
    videoBitrate = params.videoBitrate,
    audioBitrate = params.audioBitrate,
    fps = params.fps,
  )

  VideoCompressor.transcode(
    inputUri, outputUri, ktParams,
    onProgress = { p -> onProgress(p) },
    onCompleted = { promise.resolve(null) },
    onFailed = { err ->
      val msg = err.message ?: err.javaClass.simpleName
      val code = if (msg.contains("no video", ignoreCase = true)) "ERR_UNSUPPORTED_SOURCE" else "ERR_ENCODING_FAILED"
      promise.reject(code, msg, err)
    }
  )
}
```

- [ ] **Step 3: Smoke test**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor/example
npx expo run:android
```

Push a test video, call `transcode` from app temp button (similar to iOS task). Expected: `onTranscodeProgress` logs climb, output MP4 written to app files dir.

- [ ] **Step 4: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add android/src/main/java/expo/modules/rnvideocompressor/VideoCompressor.kt \
        android/src/main/java/expo/modules/rnvideocompressor/RnVideoCompressorModule.kt
git commit -m "feat(android): implement transcode with deepmedia Transcoder"
```

---

## Task 11: Wire TS public API (compressAuto, compress)

**Files:**
- Create: `src/index.ts` (replace default template content)

- [ ] **Step 1: Write index.ts**

Replace `src/index.ts`:

```ts
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
```

- [ ] **Step 2: Confirm TS compiles**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Run existing tests still pass**

```bash
npm test
```

Expected: validate + autoRules tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/index.ts
git commit -m "feat: implement compressAuto and compress public API"
```

---

## Task 12: Example app for manual verification

**Files:**
- Replace: `example/App.tsx`
- Modify: `example/package.json` — add `expo-image-picker`, `expo-file-system`

- [ ] **Step 1: Install deps in example**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor/example
npx expo install expo-image-picker expo-file-system
```

- [ ] **Step 2: Write App.tsx**

Replace `example/App.tsx` with:

```tsx
import { useState } from 'react';
import { Button, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';
import {
  compressAuto,
  compress,
  CompressResult,
} from '@qiushizhao/rn-video-compressor';

async function pickVideo(): Promise<string | null> {
  const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
  if (!perm.granted) return null;
  const res = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ImagePicker.MediaTypeOptions.Videos,
    allowsEditing: false,
  });
  if (res.canceled) return null;
  return res.assets[0].uri; // file://
}

function buildOutputPath(): string {
  const name = `out-${Date.now()}.mp4`;
  return `${FileSystem.documentDirectory}${name}`;
}

async function fileSize(uri: string): Promise<number> {
  const info = await FileSystem.getInfoAsync(uri);
  return info.exists ? (info.size ?? 0) : 0;
}

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [progress, setProgress] = useState(0);

  const append = (line: string) => setLog((l) => [...l, line]);

  const runAuto = async () => {
    setProgress(0);
    try {
      const input = await pickVideo();
      if (!input) return;
      const output = buildOutputPath();
      const inSize = await fileSize(input);
      append(`auto: input=${input} (${inSize} bytes), output=${output}`);
      const started = Date.now();
      const result: CompressResult = await compressAuto(input, output, {
        onProgress: setProgress,
      });
      const outSize = await fileSize(result.uri);
      append(`auto done in ${Date.now() - started}ms: skipped=${result.skipped}, out=${outSize} bytes`);
    } catch (e: any) {
      append(`auto error: ${e?.code ?? 'UNKNOWN'} ${e?.message ?? e}`);
    }
  };

  const runCustom = async () => {
    setProgress(0);
    try {
      const input = await pickVideo();
      if (!input) return;
      const output = buildOutputPath();
      const started = Date.now();
      const result = await compress(input, output, {
        width: 640,
        height: 360,
        videoBitrate: 800_000,
        onProgress: setProgress,
      });
      const outSize = await fileSize(result.uri);
      append(`custom done in ${Date.now() - started}ms: out=${outSize} bytes`);
    } catch (e: any) {
      append(`custom error: ${e?.code ?? 'UNKNOWN'} ${e?.message ?? e}`);
    }
  };

  const runBadOutput = async () => {
    try {
      const input = await pickVideo();
      if (!input) return;
      await compressAuto(input, 'file:///tmp/out.mov');
    } catch (e: any) {
      append(`expected error: ${e?.code} ${e?.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.root}>
      <View style={styles.buttons}>
        <Button title="Pick & compressAuto" onPress={runAuto} />
        <Button title="Pick & compress (640x360 @ 800kbps)" onPress={runCustom} />
        <Button title="Bad output path (expect error)" onPress={runBadOutput} />
      </View>
      <Text>progress: {(progress * 100).toFixed(1)}%</Text>
      <ScrollView style={styles.log}>
        {log.map((line, i) => (
          <Text key={i} style={styles.logLine}>{line}</Text>
        ))}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 16 },
  buttons: { gap: 8, marginBottom: 16 },
  log: { flex: 1, borderWidth: 1, borderColor: '#ccc', padding: 8 },
  logLine: { fontFamily: 'Menlo', fontSize: 11, marginBottom: 4 },
});
```

- [ ] **Step 3: Run full verification matrix on iOS**

```bash
cd example && npx expo run:ios
```

Manually verify each scenario (have test clips ready in Photos):

| Scenario | Expected |
|---|---|
| Pick ≤720p clip, compressAuto | log `skipped=true`, out size == 0 (no file written) |
| Pick 1080p landscape, compressAuto | log `skipped=false`, out file exists, plays 1280×720 in QuickTime |
| Pick 1080p portrait, compressAuto | 720×1280 output |
| Pick 4K clip, compressAuto | 1280×720 output |
| Pick any clip, compress 640x360 | output plays at 640×360 |
| Bad output path button | log shows `ERR_OUTPUT_PATH_INVALID` |

- [ ] **Step 4: Run full verification matrix on Android**

```bash
cd example && npx expo run:android
```

Same matrix. Note: iOS Photos rotation metadata may cause `probeVideo` width/height to appear transposed for portrait clips — verify output orientation visually.

- [ ] **Step 5: Commit**

```bash
cd /Users/jerry/dev/mine/rn-video-compressor
git add example/App.tsx example/package.json example/package-lock.json
git commit -m "chore(example): add verification UI covering all scenarios"
```

---

## Task 13: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README**

Create `README.md`:

````markdown
# @qiushizhao/rn-video-compressor

Video compression for React Native (Expo). iOS uses NextLevelSessionExporter, Android uses deepmedia/Transcoder.

Output is always MP4 / H.264 / AAC.

## Install

```bash
npm install @qiushizhao/rn-video-compressor
cd ios && pod install
```

Min targets: iOS 13, Android 24, Expo SDK 50.

## Usage

```ts
import { compressAuto, compress } from '@qiushizhao/rn-video-compressor';

// Auto mode: short edge capped at 720p, bitrate = w*h*3 bps, skips if source already qualifies
const { uri, skipped } = await compressAuto(
  'file:///path/to/input.mp4',
  'file:///path/to/output.mp4',
  { onProgress: (p) => console.log(p) }
);

// Custom mode
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
- Output directory must exist.
- When `compressAuto` returns `skipped: true`, `uri === inputUri` and no output file is written.

## Errors

Rejects with `VideoCompressorError` carrying `code`:

- `ERR_INPUT_NOT_FOUND` — input URI not `file://`, missing, or unreadable.
- `ERR_OUTPUT_PATH_INVALID` — output URI not `file://`, not `.mp4`, parent missing, or not writable.
- `ERR_UNSUPPORTED_SOURCE` — file has no video track.
- `ERR_ENCODING_FAILED` — underlying exporter/transcoder reported an error.
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add README"
```

---

## Self-review checklist (for plan author)

1. **Spec coverage — every requirement maps to a task:**
   - ✓ `compressAuto` / `compress` two-function API → Task 11
   - ✓ `AutoOptions` / `CustomOptions` / `CompressResult` / `ErrorCode` types → Task 3
   - ✓ iOS target 13.0, Android minSdk 24 → Task 2
   - ✓ NextLevelSessionExporter dep → Task 2, Task 9
   - ✓ Transcoder dep → Task 2, Task 10
   - ✓ H.264 High 4.0, 30 fps, keyframe interval 250, AAC 2ch 44.1k → Tasks 9, 10
   - ✓ Short-edge 720 cap, bitrate w*h*3 → Task 5 (logic) + Tasks 9/10 (applied)
   - ✓ Skip when short edge ≤720 AND bitrate ≤ w*h*3; `skipped: true, uri === inputUri` → Tasks 5, 11
   - ✓ Output must be `.mp4` → Task 4 validation
   - ✓ Progress callback, throttled 100ms → Tasks 9 (iOS), 10 (Android)
   - ✓ Error codes `ERR_INPUT_NOT_FOUND` / `ERR_OUTPUT_PATH_INVALID` / `ERR_UNSUPPORTED_SOURCE` / `ERR_ENCODING_FAILED` → Tasks 4, 11
   - ✓ Manual verification via example app → Task 12

2. **No placeholders:** All code blocks contain actual runnable code. No "TBD" / "fill in" / "handle errors appropriately".

3. **Type consistency:** `TranscodeParams` shape identical across iOS (Swift Record), Android (Kotlin Record), and TS (NativeModule). Field names `width`/`height`/`videoBitrate`/`audioBitrate`/`fps` match everywhere.

4. **Helper name consistency:** `shouldSkip`, `computeTargetSize`, `computeBitrate`, `roundEven` — same names in autoRules.ts (Task 5) and index.ts (Task 11). `validateInputUri`/`validateOutputUri` — same in validate.ts (Task 4) and index.ts (Task 11).

5. **Divergence from spec (intentional):**
   - Spec said "No automated unit tests" — plan adds Jest tests for pure TS helpers (validate, autoRules) because they need no fixtures or devices. Native encoding still has no automated tests.
   - Spec referenced `com.otaliastudios:transcoder` — plan uses `io.deepmedia.community:transcoder-android:0.11.2` because natario1 moved the project. Same API surface.
