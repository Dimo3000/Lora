#!/usr/bin/env bash
# Подтягивает для сборки всё, что нужно женскому голосу Лоры:
#  1) нативные библиотеки sherpa-onnx для Android (arm64) -> app/src/main/jniLibs
#  2) Kotlin-обёртку над ними (Tts.kt) -> app/src/main/java/com/k2fsa/sherpa/onnx
#  3) русский голос Piper «Ирина» (женский, ~60 МБ) -> app/src/main/assets/voice-ru
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=k2-fsa/sherpa-onnx
JNI=app/src/main/jniLibs/arm64-v8a
KT=app/src/main/java/com/k2fsa/sherpa/onnx
VOICE=app/src/main/assets/voice-ru

if [ -f "$VOICE/tokens.txt" ] && [ -f "$JNI/libsherpa-onnx-jni.so" ] && [ -f "$KT/Tts.kt" ]; then
  echo "Голос и библиотеки уже на месте."
  exit 0
fi

api() { # GET к GitHub API, с токеном если он есть
  if command -v gh >/dev/null && [ -n "${GH_TOKEN:-}" ]; then gh api "$1"; else curl -fsSL "https://api.github.com/$1"; fi
}

TAG=$(api repos/$REPO/releases/latest | jq -r .tag_name)
echo "Версия sherpa-onnx: $TAG"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# 1) нативные библиотеки
curl -fL --retry 3 -o "$TMP/android.tar.bz2" "https://github.com/$REPO/releases/download/$TAG/sherpa-onnx-$TAG-android.tar.bz2"
mkdir -p "$TMP/android" && tar xjf "$TMP/android.tar.bz2" -C "$TMP/android"
SRC=$(find "$TMP/android" -type d -path '*jniLibs/arm64-v8a' | head -1)
[ -n "$SRC" ] || { echo "В архиве Android нет jniLibs/arm64-v8a"; find "$TMP/android" -maxdepth 3; exit 1; }
rm -rf "$JNI" && mkdir -p "$JNI" && cp "$SRC"/*.so "$JNI"/
ls -la "$JNI"

# 2) Kotlin-обёртка
mkdir -p "$KT"
curl -fL --retry 3 -o "$KT/Tts.kt" "https://raw.githubusercontent.com/$REPO/$TAG/sherpa-onnx/kotlin-api/Tts.kt"
head -20 "$KT/Tts.kt"

# 3) голос «Ирина»
NAME=$(api repos/$REPO/releases/tags/tts-models | jq -r '.assets[].name' | grep -E '^vits-piper-ru_RU-irina-.*\.tar\.bz2$' | sort | tail -1)
[ -n "$NAME" ] || { echo "Голос Ирина не найден среди tts-models"; exit 1; }
echo "Голос: $NAME"
curl -fL --retry 3 -o "$TMP/voice.tar.bz2" "https://github.com/$REPO/releases/download/tts-models/$NAME"
mkdir -p "$TMP/voice" && tar xjf "$TMP/voice.tar.bz2" -C "$TMP/voice"
DIR=$(find "$TMP/voice" -mindepth 1 -maxdepth 1 -type d | head -1)
rm -rf "$VOICE" && mkdir -p "$(dirname "$VOICE")" && mv "$DIR" "$VOICE"
rm -f "$VOICE"/MODEL_CARD "$VOICE"/*.json
ls -la "$VOICE" | head -20
du -sh "$VOICE" "$JNI"
