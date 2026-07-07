# Native libraries (jniLibs)

The app launches the whisper.cpp HTTP server as a native child process. On
Android 10+ you can only exec files from the read-only, execute-allowed
`nativeLibraryDir`, so the server executable is packaged here as a `lib*.so`:

```
app/src/main/jniLibs/
├── arm64-v8a/
│   └── libwhisper-server.so      # whisper-server executable (primary target)
└── armeabi-v7a/
    └── libwhisper-server.so      # 32-bit fallback
```

These binaries are **not** checked into the repository (they are large and
platform-specific). Produce them with:

```bash
export ANDROID_NDK_HOME=/path/to/ndk   # r26+
./gradlew buildWhisperNative
```

which runs `scripts/build-whisper.sh` (clone → cross-compile → copy here).

`WhisperBridge` resolves the executable at
`applicationInfo.nativeLibraryDir/libwhisper-server.so` and reports a clear
error in the UI/logs if it is missing.
