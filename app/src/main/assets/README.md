# Assets

Model files are **not** bundled here — they are downloaded on demand from
HuggingFace into app-private storage (`filesDir/models/`) by the in-app model
manager, with memory/storage guards and optional SHA-256 verification.

The native `whisper-server` executable is packaged under
`../jniLibs/<abi>/libwhisper-server.so` rather than as an asset, because
Android 10+ only permits executing binaries from `nativeLibraryDir`. See
`../jniLibs/README.md` and `scripts/build-whisper.sh`.
