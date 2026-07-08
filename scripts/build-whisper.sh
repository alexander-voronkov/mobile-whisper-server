#!/usr/bin/env bash
#
# Cross-compiles whisper.cpp (including the HTTP server example) for Android and
# drops the resulting executable + shared libraries into the app source tree.
#
# Invoked by:  ./gradlew buildWhisperNative
# or directly: ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-whisper.sh
#
# Environment (all provided by the Gradle task, with sane fallbacks):
#   WHISPER_COMMIT     git ref/tag/sha to build (default: v1.7.4)
#   WHISPER_REPO       git URL (default: upstream ggerganov/whisper.cpp)
#   ANDROID_NDK_HOME   path to NDK r26+ (required)
#   APP_JNILIBS_DIR    output dir for lib*.so (default: app/src/main/jniLibs)
#   APP_ASSETS_DIR     output dir for assets (default: app/src/main/assets)
#   ANDROID_API        min API level to target (default: 30)
#   ABIS               space-separated ABIs (default: "arm64-v8a armeabi-v7a")
#
set -euo pipefail

WHISPER_COMMIT="${WHISPER_COMMIT:-v1.7.4}"
WHISPER_REPO="${WHISPER_REPO:-https://github.com/ggerganov/whisper.cpp.git}"
ANDROID_API="${ANDROID_API:-30}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"

# ARM CPU tuning for the 64-bit (arm64-v8a) build.
#
# ggml gates its fast NEON kernels behind compile-time CPU features: the fp16
# vector-arithmetic GEMM (huge for the F16 models we ship) needs FEAT_FP16, and
# the int8 kernels used by quantized (q5/q8) models need FEAT_DotProd. When
# cross-compiling, ggml applies these ONLY from GGML_CPU_ARM_ARCH -> -march; with
# it unset the NDK default for arm64-v8a is baseline armv8.0-a, which compiles the
# fast kernels OUT and leaves inference on the slow generic path (2-4x slower).
#
# armv8.2-a+fp16+dotprod covers essentially every phone SoC since ~2018 (the
# Cortex-A76 in a MediaTek Dimensity 800U, all recent Snapdragon/Exynos, etc.).
# Trade-off: the resulting arm64 binary uses these instructions unconditionally,
# so it will SIGILL on a true ARMv8.0-only device. Override for such devices with
# `ARM64_CPU_ARCH=` (empty) to fall back to the portable baseline. i8mm/sve are
# intentionally NOT enabled — the A76 lacks them and would fault.
#
# `-` (not `:-`): substitute the default only when ARM64_CPU_ARCH is *unset*. An
# explicitly empty `ARM64_CPU_ARCH=` must stay empty so the escape hatch actually
# disables the -march tuning (`:-` would treat empty as unset and restore it).
ARM64_CPU_ARCH="${ARM64_CPU_ARCH-armv8.2-a+fp16+dotprod}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_JNILIBS_DIR="${APP_JNILIBS_DIR:-$ROOT_DIR/app/src/main/jniLibs}"
APP_ASSETS_DIR="${APP_ASSETS_DIR:-$ROOT_DIR/app/src/main/assets}"
WORK_DIR="$ROOT_DIR/.whisper-build"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "ERROR: ANDROID_NDK_HOME is not set. Install NDK r26+ and export ANDROID_NDK_HOME." >&2
    exit 1
fi
TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN" ]]; then
    echo "ERROR: NDK toolchain not found at $TOOLCHAIN" >&2
    exit 1
fi

echo "==> whisper.cpp @ $WHISPER_COMMIT  (API $ANDROID_API, ABIs: $ABIS)"

# 1. Clone / checkout the pinned revision.
mkdir -p "$WORK_DIR"
SRC_DIR="$WORK_DIR/whisper.cpp"
if [[ ! -d "$SRC_DIR/.git" ]]; then
    git clone "$WHISPER_REPO" "$SRC_DIR"
fi
git -C "$SRC_DIR" fetch --tags --force origin
git -C "$SRC_DIR" checkout --force "$WHISPER_COMMIT"

# 2. Build for each ABI.
for ABI in $ABIS; do
    echo "==> Building $ABI"
    BUILD_DIR="$WORK_DIR/build-$ABI"
    rm -rf "$BUILD_DIR"

    # Enable the fast fp16 + dotprod NEON kernels on arm64 (see ARM64_CPU_ARCH
    # above). Left off for armeabi-v7a, which the NDK already builds with NEON.
    # Scalar (not a bash array) so the empty case is safe under `set -u` on the
    # bash 3.2 that ships with macOS build hosts; the flag value has no spaces.
    ARCH_ARG=""
    if [[ "$ABI" == "arm64-v8a" && -n "$ARM64_CPU_ARCH" ]]; then
        echo "    (tuning arm64 for -march=$ARM64_CPU_ARCH)"
        ARCH_ARG="-DGGML_CPU_ARM_ARCH=$ARM64_CPU_ARCH"
    fi

    cmake -S "$SRC_DIR" -B "$BUILD_DIR" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$ANDROID_API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DGGML_OPENMP=OFF \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DWHISPER_BUILD_SERVER=ON \
        ${ARCH_ARG:+$ARCH_ARG}

    cmake --build "$BUILD_DIR" --config Release --target whisper-server -j"$(nproc)"

    # Locate the produced server executable.
    SERVER_BIN="$(find "$BUILD_DIR" -type f -name 'whisper-server' | head -n1 || true)"
    if [[ -z "$SERVER_BIN" ]]; then
        echo "ERROR: whisper-server binary not found for $ABI" >&2
        exit 1
    fi

    OUT_DIR="$APP_JNILIBS_DIR/$ABI"
    mkdir -p "$OUT_DIR"
    # Package the executable as a lib*.so so it lands in nativeLibraryDir, the
    # only app-associated location Android 10+ reliably allows exec from.
    cp "$SERVER_BIN" "$OUT_DIR/libwhisper-server.so"

    # Copy any shared libs that were produced (when BUILD_SHARED_LIBS=ON).
    while IFS= read -r so; do
        cp "$so" "$OUT_DIR/"
    done < <(find "$BUILD_DIR" -type f -name 'lib*.so' ! -name 'libwhisper-server.so' || true)

    echo "    -> $OUT_DIR/libwhisper-server.so"
done

echo ""
echo "==> Done. Native artifacts placed under $APP_JNILIBS_DIR/<abi>/"
echo "    NOTE: For --convert (audio transcoding) also drop an 'ffmpeg' binary"
echo "    (packaged as libffmpeg.so) next to the server, or integrate ffmpeg-kit."
