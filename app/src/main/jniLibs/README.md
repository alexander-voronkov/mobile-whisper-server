# Native libraries (jniLibs)

The app launches the whisper.cpp HTTP server as a native child process. On
Android 10+ you can only exec files from the read-only, execute-allowed
`nativeLibraryDir`, so the server executable is packaged here as a `lib*.so`:

```
app/src/main/jniLibs/
├── arm64-v8a/
│   ├── libwhisper-server.so      # whisper-server executable (primary target)
│   └── libffmpeg.so              # ffmpeg executable, audio conversion (optional)
└── armeabi-v7a/
    ├── libwhisper-server.so      # 32-bit fallback
    └── libffmpeg.so              # 32-bit ffmpeg (optional)
```

These binaries are **not** checked into the repository (they are large and
platform-specific). Produce them with:

```bash
export ANDROID_NDK_HOME=/path/to/ndk   # r26+
./gradlew buildWhisperNative          # -> libwhisper-server.so
./gradlew buildFfmpegNative           # -> libffmpeg.so (optional, enables --convert)
```

which run `scripts/build-whisper.sh` and `scripts/build-ffmpeg.sh`
(clone → cross-compile → copy here).

`WhisperBridge` resolves the executables under
`applicationInfo.nativeLibraryDir/` and reports a clear error in the UI/logs if
`libwhisper-server.so` is missing. `libffmpeg.so` is optional: when present the
app exposes it as `ffmpeg` on PATH and passes `--convert`, so mp3/m4a/ogg/flac
uploads are transcoded to 16 kHz WAV; when absent, the *Convert audio* setting
is disabled and uploads must already be 16 kHz WAV.
