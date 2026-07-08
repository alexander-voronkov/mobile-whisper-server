#!/usr/bin/env bash
#
# Cross-compiles a *minimal* static FFmpeg CLI for Android and drops the
# resulting `ffmpeg` executable (packaged as libffmpeg.so) next to the
# whisper-server binary in the app source tree.
#
# Why: whisper-server's `--convert` shells out to an executable literally named
# `ffmpeg` on PATH (see WhisperBridge.resolveFfmpegDir). It runs exactly:
#     ffmpeg -i "<input>" -y -ar 16000 -ac 1 -c:a pcm_s16le "<output>.wav"
# so we only need file I/O, a handful of decoders/demuxers, resampling, and the
# WAV/pcm_s16le output path. Everything else is disabled to keep the binary
# small (a few MB per ABI instead of the usual tens of MB).
#
# Executables can only be exec'd from nativeLibraryDir on modern Android, and
# the only way to land one there is to name it lib*.so so the APK packager
# extracts it (same trick as libwhisper-server.so). A PIE executable is ET_DYN,
# so the .so name is consistent.
#
# Invoked by:  ./gradlew buildFfmpegNative
# or directly: ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-ffmpeg.sh
#
# Environment (all provided by the Gradle task, with sane fallbacks):
#   FFMPEG_COMMIT     git tag/sha to build (default: n7.1.1)
#   FFMPEG_REPO       git URL (default: upstream FFmpeg/FFmpeg)
#   ANDROID_NDK_HOME  path to NDK r26+ (required)
#   APP_JNILIBS_DIR   output dir for lib*.so (default: app/src/main/jniLibs)
#   ANDROID_API       min API level to target (default: 30)
#   ABIS              space-separated ABIs (default: "arm64-v8a armeabi-v7a")
#
# Licensing: built WITHOUT --enable-gpl / --enable-nonfree, so the result is
# plain LGPL FFmpeg. Only FFmpeg's own native decoders are enabled (no external
# codec libraries), so nothing extra needs to be shipped or attributed beyond
# FFmpeg itself.
set -euo pipefail

FFMPEG_COMMIT="${FFMPEG_COMMIT:-n7.1.1}"
FFMPEG_REPO="${FFMPEG_REPO:-https://github.com/FFmpeg/FFmpeg.git}"
ANDROID_API="${ANDROID_API:-30}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_JNILIBS_DIR="${APP_JNILIBS_DIR:-$ROOT_DIR/app/src/main/jniLibs}"
WORK_DIR="$ROOT_DIR/.ffmpeg-build"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: ANDROID_NDK_HOME is not set. Install NDK r26+ and export ANDROID_NDK_HOME." >&2
    exit 1
fi

# Locate the NDK's prebuilt LLVM toolchain (host tag differs by OS).
HOST_TAG=""
for t in linux-x86_64 darwin-x86_64 darwin-arm64; do
    if [[ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$t" ]]; then
        HOST_TAG="$t"
        break
    fi
done
if [[ -z "$HOST_TAG" ]]; then
    echo "ERROR: could not find LLVM toolchain under $ANDROID_NDK_HOME/toolchains/llvm/prebuilt" >&2
    exit 1
fi
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
BIN="$TOOLCHAIN/bin"

echo "==> FFmpeg @ $FFMPEG_COMMIT  (API $ANDROID_API, ABIs: $ABIS)"
echo "    NDK toolchain: $TOOLCHAIN"

# 1. Clone / checkout the pinned revision (shallow — we only need one commit).
mkdir -p "$WORK_DIR"
SRC_DIR="$WORK_DIR/FFmpeg"
if [[ ! -d "$SRC_DIR/.git" ]]; then
    git clone --depth 1 --branch "$FFMPEG_COMMIT" "$FFMPEG_REPO" "$SRC_DIR" \
        || git clone "$FFMPEG_REPO" "$SRC_DIR"
fi
git -C "$SRC_DIR" fetch --depth 1 --tags --force origin "$FFMPEG_COMMIT" || true
git -C "$SRC_DIR" checkout --force "$FFMPEG_COMMIT"

# Only what the whisper-server `--convert` command actually exercises.
DECODERS="mp3,aac,alac,vorbis,opus,flac,wavpack,pcm_s16le,pcm_s16be,pcm_u8,pcm_f32le,pcm_s24le,pcm_s32le,pcm_mulaw,pcm_alaw"
DEMUXERS="mp3,mov,ogg,flac,wav,aac,matroska,w64,aiff,wavpack"
PARSERS="aac,flac,vorbis,opus,mpegaudio"
FILTERS="aresample,aformat,anull,abuffer,abuffersink"

for ABI in $ABIS; do
    echo "==> Building $ABI"
    case "$ABI" in
        arm64-v8a)
            ARCH="aarch64"; CPU="armv8-a"
            CC_PREFIX="aarch64-linux-android"
            EXTRA_CFLAGS=""
            ;;
        armeabi-v7a)
            ARCH="arm"; CPU="armv7-a"
            CC_PREFIX="armv7a-linux-androideabi"
            EXTRA_CFLAGS="-mfpu=neon -mfloat-abi=softfp"
            ;;
        *)
            echo "ERROR: unsupported ABI '$ABI'" >&2
            exit 1
            ;;
    esac

    CC="$BIN/${CC_PREFIX}${ANDROID_API}-clang"
    if [[ ! -x "$CC" ]]; then
        echo "ERROR: compiler not found: $CC" >&2
        exit 1
    fi

    BUILD_DIR="$WORK_DIR/build-$ABI"
    rm -rf "$BUILD_DIR"
    mkdir -p "$BUILD_DIR"

    (
        cd "$BUILD_DIR"
        "$SRC_DIR/configure" \
            --prefix="$BUILD_DIR/out" \
            --target-os=android \
            --enable-cross-compile \
            --arch="$ARCH" \
            --cpu="$CPU" \
            --cc="$CC" \
            --ar="$BIN/llvm-ar" \
            --nm="$BIN/llvm-nm" \
            --ranlib="$BIN/llvm-ranlib" \
            --strip="$BIN/llvm-strip" \
            --pkg-config-flags="--static" \
            --disable-everything \
            --disable-shared --enable-static --enable-pic \
            --disable-doc --disable-htmlpages --disable-manpages \
            --disable-podpages --disable-txtpages \
            --disable-ffprobe --disable-ffplay \
            --disable-network --disable-debug --disable-autodetect \
            --enable-small \
            --enable-swresample --enable-avfilter \
            --enable-filter="$FILTERS" \
            --enable-demuxer="$DEMUXERS" \
            --enable-decoder="$DECODERS" \
            --enable-parser="$PARSERS" \
            --enable-muxer=wav \
            --enable-encoder=pcm_s16le \
            --enable-protocol=file,pipe \
            --extra-cflags="$EXTRA_CFLAGS" \
            --extra-ldexeflags="-pie"

        make -j"$(nproc)" ffmpeg
    )

    FFMPEG_BIN="$BUILD_DIR/ffmpeg"
    if [[ ! -f "$FFMPEG_BIN" ]]; then
        echo "ERROR: ffmpeg binary not produced for $ABI" >&2
        exit 1
    fi
    "$BIN/llvm-strip" --strip-unneeded "$FFMPEG_BIN" || true

    OUT_DIR="$APP_JNILIBS_DIR/$ABI"
    mkdir -p "$OUT_DIR"
    # Package as lib*.so so the APK packager extracts it to nativeLibraryDir,
    # the only exec-allowed app location on Android 10+.
    cp "$FFMPEG_BIN" "$OUT_DIR/libffmpeg.so"
    echo "    -> $OUT_DIR/libffmpeg.so ($(du -h "$OUT_DIR/libffmpeg.so" | cut -f1))"
done

echo ""
echo "==> Done. libffmpeg.so placed under $APP_JNILIBS_DIR/<abi>/"
echo "    The app auto-detects it at runtime and passes --convert so mp3/m4a/"
echo "    ogg/flac uploads are transcoded to 16 kHz WAV."
