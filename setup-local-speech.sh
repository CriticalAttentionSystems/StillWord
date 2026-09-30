#!/bin/sh
# Local macOS speech recognition. Installed apps use writable per-user storage.
set -eu

task_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
speech_root=${STILLWORD_SPEECH_DIR:-}
speech_runtime_only=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --speech-dir) [ "$#" -ge 2 ] || { printf '%s\n' 'Missing --speech-dir value.' >&2; exit 2; }; speech_root=$2; shift 2 ;;
        --prepare-runtime-only) speech_runtime_only=true; shift ;;
        *) printf 'Unknown speech setup option: %s\n' "$1" >&2; exit 2 ;;
    esac
done
if [ -z "$speech_root" ]; then
    case "$task_root" in
        *.app/Contents/app) speech_root="$HOME/Library/Application Support/Stillword/speech" ;;
        *) speech_root="$task_root/tools/speech" ;;
    esac
fi
case "$speech_root" in
    /*) ;;
    *) printf '%s\n' 'The speech directory must be an absolute path.' >&2; exit 2 ;;
esac
# Never modify an application bundle, including through an explicit override.
case "$speech_root" in
    *.app|*.app/*) printf '%s\n' 'Speech data must be stored outside the application bundle.' >&2; exit 2 ;;
esac
speech_bundled_root="$task_root/tools/speech"
speech_version=1.9.4
speech_source_sha=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
speech_model_sha=1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69
speech_model_revision=98aa99a0a9db05ae2342309f5096248665f7cba3
speech_archive="$speech_root/downloads/whisper-v$speech_version.tar.gz"
speech_model="$speech_root/models/ggml-large-v3-turbo.bin"
speech_binary="$speech_root/bin/whisper-cli"
speech_receipt="$speech_root/bin/build-receipt.txt"
speech_lock="$speech_root/.setup-lock"
speech_work=
speech_child=
speech_locked=false

say() { printf '%s\n' "$*"; }
fail() { say "Speech setup: $*" >&2; exit 1; }

# Stop build/download subprocesses as well as their children when cancelled.
stop_tree() {
    for speech_descendant in $(/usr/bin/pgrep -P "$1" 2>/dev/null || true); do
        stop_tree "$speech_descendant"
    done
    kill -TERM "$1" 2>/dev/null || true
}

cleanup() {
    speech_exit=$?
    trap - 0 HUP INT TERM
    if [ -n "$speech_child" ]; then
        stop_tree "$speech_child"
        wait "$speech_child" 2>/dev/null || true
    fi
    if [ -n "$speech_work" ]; then rm -rf "$speech_work"; fi
    if [ "$speech_locked" = true ]; then rm -rf "$speech_lock"; fi
    exit "$speech_exit"
}
trap cleanup 0
trap 'exit 130' INT
trap 'exit 143' HUP TERM

run() {
    "$@" &
    speech_child=$!
    wait "$speech_child"
    speech_child=
}

valid_hash() {
    [ -f "$1" ] || return 1
    speech_actual=$(/usr/bin/shasum -a 256 "$1") || return 1
    [ "${speech_actual%% *}" = "$2" ]
}

download() {
    say "$3"
    run /usr/bin/curl --fail --location --show-error --progress-bar \
        --proto '=https' --proto-redir '=https' --connect-timeout 20 \
        --max-time 5400 --retry 2 --output "$2" "$1"
}

[ "$(uname -s)" = Darwin ] || fail 'This setup script currently supports macOS only.'
speech_arch=$(uname -m)
case "$speech_arch" in arm64|x86_64) ;; *) fail "Unsupported Mac architecture: $speech_arch" ;; esac
# Resolve the nearest existing ancestor before creating anything, so a symlink
# into an application bundle cannot make setup modify that bundle.
speech_existing="$speech_root"
speech_suffix=
while [ ! -d "$speech_existing" ]; do
    speech_suffix="/$(basename -- "$speech_existing")$speech_suffix"
    speech_existing=$(dirname -- "$speech_existing")
done
speech_root="$(CDPATH= cd -- "$speech_existing" && pwd -P)$speech_suffix"
case "$speech_root" in *.app|*.app/*) fail 'Speech data must be stored outside the application bundle.' ;; esac
mkdir -p "$speech_root"
speech_archive="$speech_root/downloads/whisper-v$speech_version.tar.gz"
speech_model="$speech_root/models/ggml-large-v3-turbo.bin"
speech_binary="$speech_root/bin/whisper-cli"
speech_receipt="$speech_root/bin/build-receipt.txt"
speech_lock="$speech_root/.setup-lock"
mkdir -p "$speech_root/bin" "$speech_root/models"
if ! mkdir "$speech_lock" 2>/dev/null; then
    fail "Another speech setup may be running. If it was forcibly stopped, remove $speech_lock after checking that setup is no longer running."
fi
speech_locked=true
printf '%s\n' "$$" > "$speech_lock/pid"
speech_work=$(mktemp -d "$speech_root/.setup-XXXXXX")
speech_build_id="whisper.cpp $speech_version $speech_arch static embedded-metal"

speech_binary_ready=false
if [ -x "$speech_binary" ] && [ -f "$speech_receipt" ]; then
    speech_saved_id=$(sed -n '1p' "$speech_receipt")
    speech_saved_sha=$(sed -n '2p' "$speech_receipt")
    if [ "$speech_saved_id" = "$speech_build_id" ] && valid_hash "$speech_binary" "$speech_saved_sha"; then
        speech_binary_ready=true
        say "Reusing verified whisper.cpp $speech_version for $speech_arch."
    fi
fi

if [ "$speech_binary_ready" = false ] && [ "$speech_bundled_root" != "$speech_root" ]; then
    speech_bundled_binary="$speech_bundled_root/bin/whisper-cli"
    speech_bundled_receipt="$speech_bundled_root/bin/build-receipt.txt"
    if [ -x "$speech_bundled_binary" ] && [ -f "$speech_bundled_receipt" ]; then
        speech_saved_id=$(sed -n '1p' "$speech_bundled_receipt")
        speech_saved_sha=$(sed -n '2p' "$speech_bundled_receipt")
        [ "$speech_saved_id" = "$speech_build_id" ] || fail 'The bundled speech engine is not compatible with this Mac. Install the correct StillWord download.'
        valid_hash "$speech_bundled_binary" "$speech_saved_sha" || fail 'The bundled speech engine failed verification. Download a fresh copy of StillWord.'
        cp "$speech_bundled_binary" "$speech_work/whisper-cli"
        chmod 755 "$speech_work/whisper-cli"
        valid_hash "$speech_work/whisper-cli" "$speech_saved_sha" || fail 'The copied speech engine failed verification.'
        run "$speech_work/whisper-cli" --help > "$speech_work/cli-help.txt" 2>&1
        cp "$speech_bundled_receipt" "$speech_work/build-receipt.txt"
        if [ -f "$speech_bundled_root/WHISPER_LICENSE" ]; then
            cp "$speech_bundled_root/WHISPER_LICENSE" "$speech_root/WHISPER_LICENSE"
        fi
        mv "$speech_work/whisper-cli" "$speech_binary"
        mv "$speech_work/build-receipt.txt" "$speech_receipt"
        speech_binary_ready=true
        say "Installed the bundled whisper.cpp $speech_version for $speech_arch."
    fi
fi

if [ "$speech_binary_ready" = false ]; then
    case "$task_root" in *.app/Contents/app) fail 'The bundled speech engine is missing. Download a fresh copy of StillWord.' ;; esac
    [ -f "$task_root/build.sh" ] && [ -d "$task_root/src/main/java" ] || fail 'The bundled speech engine is missing; compilation is available only in a StillWord source checkout.'
    mkdir -p "$speech_root/downloads"
    speech_cmake=
    for speech_candidate in /opt/homebrew/bin/cmake /usr/local/bin/cmake /Applications/CMake.app/Contents/bin/cmake; do
        if [ -x "$speech_candidate" ]; then speech_cmake="$speech_candidate"; break; fi
    done
    if [ -z "$speech_cmake" ]; then speech_cmake=$(command -v cmake || true); fi
    [ -n "$speech_cmake" ] || fail 'CMake is needed for the first build. Install CMake from cmake.org or with brew install cmake, then retry.'
    speech_clang=$(/usr/bin/xcrun --find clang 2>/dev/null) || fail 'Install Apple Command Line Tools with xcode-select --install, then retry.'
    speech_clangxx=$(/usr/bin/xcrun --find clang++ 2>/dev/null) || fail 'Apple Command Line Tools are incomplete; install them before retrying.'
    /usr/bin/xcrun --sdk macosx --show-sdk-path >/dev/null 2>&1 || fail 'The macOS SDK is unavailable. Install or select Apple Command Line Tools, then retry.'

    if valid_hash "$speech_archive" "$speech_source_sha"; then
        say "Reusing verified whisper.cpp $speech_version source archive."
    else
        download "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$speech_version.tar.gz" \
            "$speech_work/source.tar.gz" "Downloading whisper.cpp $speech_version source…"
        valid_hash "$speech_work/source.tar.gz" "$speech_source_sha" || fail 'Source archive checksum did not match the pinned release; nothing was installed.'
        mv "$speech_work/source.tar.gz" "$speech_archive"
    fi
    run /usr/bin/tar -xzf "$speech_archive" -C "$speech_work"
    speech_source="$speech_work/whisper.cpp-$speech_version"
    say "Building whisper.cpp $speech_version for this $speech_arch Mac (CPU and Metal)…"
    run "$speech_cmake" -S "$speech_source" -B "$speech_work/build" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES="$speech_arch" -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 \
        -DCMAKE_C_COMPILER="$speech_clang" -DCMAKE_CXX_COMPILER="$speech_clangxx" \
        -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
        -DWHISPER_BUILD_SERVER=OFF -DWHISPER_BUILD_IS_DEV=OFF \
        -DWHISPER_CURL=OFF -DWHISPER_COREML=OFF -DWHISPER_USE_SYSTEM_GGML=OFF \
        -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_METAL=ON -DGGML_METAL_EMBED_LIBRARY=ON
    speech_jobs=$(/usr/sbin/sysctl -n hw.logicalcpu 2>/dev/null || printf '2')
    case "$speech_jobs" in ''|*[!0-9]*) speech_jobs=2 ;; esac
    if [ "$speech_jobs" -gt 8 ]; then speech_jobs=8; fi
    run "$speech_cmake" --build "$speech_work/build" --config Release --target whisper-cli --parallel "$speech_jobs"
    speech_built="$speech_work/build/bin/whisper-cli"
    [ -x "$speech_built" ] || fail 'Build completed without producing whisper-cli.'
    run "$speech_built" --help > "$speech_work/cli-help.txt" 2>&1
    # Static project libraries and embedded Metal leave only macOS system dependencies.
    /usr/bin/otool -L "$speech_built" > "$speech_work/libraries.txt"
    if ! awk 'NR > 1 && $1 !~ /^\/System\/Library\// && $1 !~ /^\/usr\/lib\// { bad = 1 } END { exit bad }' "$speech_work/libraries.txt"; then
        fail 'Build unexpectedly depends on non-system shared libraries; the existing installation was retained.'
    fi
    speech_built_sha=$(/usr/bin/shasum -a 256 "$speech_built")
    printf '%s\n%s\n' "$speech_build_id" "${speech_built_sha%% *}" > "$speech_work/build-receipt.txt"
    cp "$speech_source/LICENSE" "$speech_work/WHISPER_LICENSE"
    mv "$speech_work/WHISPER_LICENSE" "$speech_root/WHISPER_LICENSE"
    mv "$speech_built" "$speech_binary"
    mv "$speech_work/build-receipt.txt" "$speech_receipt"
    say 'Local speech engine is built and verified.'
fi

if [ "$speech_runtime_only" = true ]; then
    say 'Speech runtime verified; model download was not requested.'
    exit 0
fi

say 'Checking the Whisper large-v3-turbo model…' 
if valid_hash "$speech_model" "$speech_model_sha"; then
    say 'Reusing the verified Whisper large-v3-turbo model.'
else
    download "https://huggingface.co/ggerganov/whisper.cpp/resolve/$speech_model_revision/ggml-large-v3-turbo.bin" \
        "$speech_work/model.bin" 'Downloading Whisper large-v3-turbo (1.62 GB); this may take several minutes…'
    say 'Verifying the downloaded speech model…'
    valid_hash "$speech_work/model.bin" "$speech_model_sha" || fail 'Speech model checksum did not match; the incomplete download was not installed.'
    mv "$speech_work/model.bin" "$speech_model"
fi
say 'Ready: Whisper large-v3-turbo can transcribe video audio locally on this Mac.'
