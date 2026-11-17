#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set" >&2
  exit 1
fi

HOST_TAG=""
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) HOST_TAG="darwin-x86_64" ;;
  Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
  Linux-x86_64) HOST_TAG="linux-x86_64" ;;
  Linux-aarch64) HOST_TAG="linux-aarch64" ;;
  *)
    echo "Unsupported host: $(uname -s)-$(uname -m)" >&2
    exit 1
    ;;
esac

CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"
if [[ ! -x "$CC" ]]; then
  echo "Compiler not found: $CC" >&2
  exit 1
fi

"$CC" -O2 -static -s -o pend pend.c
install -m 755 pend ../app/src/main/assets/pend
shasum -a 256 ../app/src/main/assets/pend
