# Whisper Server (Android)

A native Android app that bundles [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
and runs its HTTP server as a persistent foreground service, exposing an
**OpenAI-compatible** `/v1/audio/transcriptions` endpoint over the local network
(including Tailscale). It provides a Jetpack Compose UI to configure and control
the server, manage models with RAM/storage guards, and watch live logs & stats.

> **Status:** application scaffold + full app logic. The native `whisper-server`
> binary is not committed (it is large and platform-specific); build it once with
> `./gradlew buildWhisperNative` (see [Native build](#native-build)). Everything
> else — UI, service lifecycle, model manager, downloader, memory guard, config,
> Tailscale detection, boot autostart — is implemented and unit-tested.

## Features

- **Foreground service** (`WhisperServerService`) running whisper.cpp as a native
  child process, with a persistent notification (`Stop` / `Restart` actions),
  a partial wake lock, and crash auto-restart (up to 3× within 5 minutes).
- **Model manager** with radio-button selection, per-model **memory & storage
  guard** (green / yellow / red), resumable HuggingFace downloads with progress +
  speed, and optional SHA-256 verification. Ships both **full-precision (F16)** and
  smaller/faster **quantized (Q5)** variants of every size.
- **OpenAI-compatible API** behind a small in-app front (`LocalProxyServer`):
  `POST /v1/audio/transcriptions` (mapped via `--inference-path`), plus
  `GET /health` and `GET /v1/models` served directly, and **Bearer API-key
  auth** enforced on all requests when a key is set. whisper-server itself binds
  `127.0.0.1`; the proxy owns the public `host:port`.
- **Server config UI** — host (0.0.0.0 or auto-detected Tailscale IP), port,
  optional API key (Keystore-encrypted), language, translate, convert (ffmpeg),
  threads.
- **Autostart on boot** (`BootReceiver`) using the last-saved config.
- **Live logs** (last 500 lines, auto-scroll) and **stats** (requests, avg time,
  uptime, memory).

## Requirements

- Android Studio (Ladybug or newer) / AGP 8.7, JDK 17
- Android SDK 35, min SDK 30 (Android 11)
- For the native build: **NDK r26+**, CMake, git

## Build & run

```bash
# 1. (Once) build the native whisper-server for the bundled ABIs:
export ANDROID_NDK_HOME=/path/to/ndk   # r26+
./gradlew buildWhisperNative

# 2. Build & install the app:
./gradlew :app:installDebug

# 3. Run unit tests (memory guard, model registry, config, tailscale):
./gradlew :app:testDebugUnitTest

# 4. Run the instrumented startup test (device/emulator required):
./gradlew :app:connectedDebugAndroidTest
```

Without step 1 the app still installs and runs; starting the server surfaces a
clear "binary not found" message in the logs/UI.

## Releases (GitHub Actions)

`.github/workflows/release.yml` builds an installable APK on GitHub's runners
and publishes it as a **GitHub Release** named `0.<build-number>`:

- On **pull requests** a lightweight `pr-check` job only compiles the app and
  runs the unit tests (no native build, no APK, no release) — a fast "does it
  still build?" gate. The full release runs only on push to `main` (a merge) or
  manual dispatch.
- Triggers on push to `main`, or manually via **Actions → Build & Release APK →
  Run workflow**.
- Runs the unit tests, best-effort cross-compiles the native `whisper-server`
  **and a minimal `ffmpeg`** (arm64-v8a + armeabi-v7a), builds the **debug** APK
  with `versionName=0.<run>`, and attaches one APK **per ABI**
  (`WhisperServer-0.<run>-arm64-v8a.apk`, `…-armeabi-v7a.apk`) to the release
  (and as workflow artifacts). Install the one matching your phone's CPU.
- The APK is debug-signed so it installs without setup. Because the debug
  keystore is per-runner, uninstall a previous build before updating if the
  signature differs. For stable, updatable signing, add a release keystore via
  repo **Secrets** (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`), wire a `signingConfigs.release` in `app/build.gradle.kts`,
  and switch the workflow's assemble step to `assembleRelease`.

## Native build

`./gradlew buildWhisperNative` runs [`scripts/build-whisper.sh`](scripts/build-whisper.sh):

1. Clones whisper.cpp at a **pinned ref** (`-PwhisperCommit=<ref>`, default
   `v1.7.4`).
2. Cross-compiles for `arm64-v8a` (primary) and `armeabi-v7a` (secondary) with
   the NDK, using `-DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DWHISPER_BUILD_SERVER=ON`.
   The `arm64-v8a` build also passes `-DGGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod`
   so ggml compiles in its **fast fp16 + dotprod NEON kernels** (2–4× faster than
   the armv8.0 baseline the NDK targets by default). This assumes an ARMv8.2+ CPU
   (any phone SoC since ~2018); override with `ARM64_CPU_ARCH=` for older devices.
3. Packages the `whisper-server` executable as
   `app/src/main/jniLibs/<abi>/libwhisper-server.so`.

> **arm64 CPU requirement:** the tuned `arm64-v8a` binary needs an ARMv8.2 CPU
> with fp16 + dotprod (essentially any phone since ~2018). On an older 64-bit CPU
> (all-ARMv8.0 cores such as Cortex-A53/A72/A73) it exits with an illegal
> instruction; the app detects this (SIGILL, exit 132) and surfaces a clear error
> instead of crash-looping. For those devices, rebuild the arm64 split with
> `ARM64_CPU_ARCH= ./gradlew buildWhisperNative` to get a portable baseline binary.

The executable is shipped as a `lib*.so` on purpose: Android 10+ only allows
`exec()` from the read-only, execute-permitted `nativeLibraryDir`, and native
libraries land there at install time.

### FFmpeg (audio conversion)

`./gradlew buildFfmpegNative` runs [`scripts/build-ffmpeg.sh`](scripts/build-ffmpeg.sh),
which cross-compiles a **minimal static `ffmpeg`** (pinned `-PffmpegCommit=<ref>`,
default `n7.1.1`; LGPL config, only the demuxers/decoders needed for
mp3/m4a/ogg/flac → 16 kHz WAV) and packages it as
`app/src/main/jniLibs/<abi>/libffmpeg.so`. The release workflow builds it
automatically (best-effort).

whisper-server's `--convert` execs a program named `ffmpeg` on `PATH`, so the
app symlinks `libffmpeg.so` → `ffmpeg` and prepends its dir to `PATH` at launch.
When `libffmpeg.so` is present the *Convert audio* setting is enabled and
mp3/m4a/ogg/flac uploads are transcoded to 16 kHz WAV; when absent the setting is
disabled and uploads must already be 16 kHz WAV.

## Using the API

From another device on the same network (e.g. Tailscale):

```bash
# Liveness check:
curl http://<phone-ip>:8080/health                     # {"status":"ok"}
curl http://<phone-ip>:8080/v1/models                  # {"data":[{"id":"whisper-1",...}]}

# Transcribe an audio file (OpenAI-compatible response).
# NOTE: send a 16 kHz WAV unless you've bundled ffmpeg and enabled "Convert audio".
curl -F file=@audio.wav http://<phone-ip>:8080/v1/audio/transcriptions

# With an API key configured in the app (enforced on all requests):
curl -H "Authorization: Bearer <key>" \
     -F file=@audio.wav \
     -F response_format=json \
     http://<phone-ip>:8080/v1/audio/transcriptions
```

Find `<phone-ip>` in the app's **Server** tab (it shows the Tailscale IP when
host is `0.0.0.0`).

## Architecture

```
app/src/main/java/com/example/whisperserver/
├── WhisperApp.kt                # Application + manual DI container
├── MainActivity.kt              # Compose UI host (bottom-nav: Server/Models/Logs/Stats)
├── service/
│   ├── WhisperServerService.kt  # foreground service, notification, wake lock
│   ├── BootReceiver.kt          # autostart on BOOT_COMPLETED
│   └── ServerController.kt      # process-wide state/logs/stats StateFlows
├── native/
│   ├── WhisperBridge.kt         # launch spec → args, lifecycle, restart policy
│   ├── LocalProxyServer.kt      # auth + /health + /v1/models front for the server
│   └── ServerProcess.kt         # ProcessBuilder subprocess + log streaming
├── data/
│   ├── ModelRegistry.kt         # model metadata table
│   ├── ModelDownloader.kt       # resumable HF download + checksum
│   ├── MemoryChecker.kt         # RAM/storage guard (pure + Android wrapper)
│   ├── ServerConfig.kt          # config model + DataStore persistence
│   └── SecureStore.kt           # Keystore-encrypted API key / HF token
├── network/TailscaleDetector.kt # find 100.64.0.0/10 interface
└── ui/                          # Compose screens, components, theme + ViewModel
```

- **Pattern:** MVVM with `ViewModel` + `StateFlow`. Hilt skipped (manual DI).
- **`ServerController`** is the single source of truth shared between the service
  (writer) and UI (reader), safe as a singleton because both live in the app's
  main process.
- **Native integration** uses the MVP **subprocess** option (Option B). A JNI
  implementation (Option A) can replace `WhisperBridge` without touching the
  service or UI, since callers only depend on `start`/`stop` + `ServerController`.

## Memory guard

Before a download or model switch (`MemoryGuard`, unit-tested in
`MemoryGuardTest`):

| Condition | Verdict |
|-----------|---------|
| `requiredRam > totalRam * 0.7` | 🔴 **Hard block** — download disabled |
| `requiredRam > availableRam * 1.5` | 🟡 **Soft warning** — allowed, warns |
| otherwise | 🟢 **OK** |
| `downloadSize > freeStorage * 0.9` | 🔴 **Storage block** |

A 3 GB device (e.g. Moto G Pure) passes the guard for Tiny/Base/Small and is hard-
blocked from Large-v3, matching the acceptance criteria.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`.

> **Foreground-service type:** the service uses **`specialUse`** (a persistent
> local HTTP server fits no other FGS category). This deviates from the spec's
> `microphone` type on purpose: on Android 14/15 a `microphone`/`dataSync` FGS
> **cannot** be started from a background `BOOT_COMPLETED` broadcast (so
> autostart would fail), and `dataSync` is additionally capped at 6h/24h.
> `specialUse` starts from boot, has no time cap, and needs no `RECORD_AUDIO`.
> The manifest declares `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; Google Play review
> would ask to justify it (irrelevant for sideloaded / self-hosted use).

## Known limitations

- The native `whisper-server` binary must be built locally (see above); it is
  not committed.
- `--convert` (transcoding non-WAV uploads) is **off by default** because no
  `ffmpeg` binary is bundled — whisper-server exits at startup if `--convert` is
  passed without ffmpeg on `PATH`. Package an `ffmpeg` (as `libffmpeg.so`) to
  enable it; the app auto-detects it and only then passes `--convert`.
- **VAD is disabled** — the pinned whisper.cpp server build doesn't parse
  `--vad` (and it needs a separate VAD model). Re-enable once you build a server
  revision with VAD support and ship the model.
- Request stats are parsed best-effort from server log output and reset on
  restart.
- Model SHA-256 checksums are not embedded by default (upstream publishes no
  stable manifest); fill them into `ModelRegistry` to enforce verification.
- The in-app proxy is intentionally minimal (Content-Length bodies,
  `Connection: close`); it is not a general-purpose HTTP proxy.

## License

whisper.cpp is MIT-licensed (© Georgi Gerganov). This app is provided as-is.
