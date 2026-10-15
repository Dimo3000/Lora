#!/usr/bin/env bash
# Скачивает русскую модель Vosk (~45 МБ) в assets, чтобы распознавание речи было встроено в APK.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/model-ru
NAME=vosk-model-small-ru-0.22
if [ -f "$DEST/uuid" ]; then
  echo "Модель уже на месте: $DEST"
else
  TMP=$(mktemp -d)
  curl -fL --retry 3 -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
  unzip -q "$TMP/model.zip" -d "$TMP"
  rm -rf "$DEST"
  mkdir -p "$(dirname "$DEST")"
  mv "$TMP/$NAME" "$DEST"
  # StorageService.unpack сравнивает этот uuid, чтобы понять, нужно ли заново распаковывать модель.
  (command -v uuidgen >/dev/null && uuidgen || cat /proc/sys/kernel/random/uuid) > "$DEST/uuid"
  rm -rf "$TMP"
  echo "Готово: $DEST"
fi

# Модель для узнавания говорящего по голосу (~13 МБ): по ней Лора отличает членов семьи.
SPK_DEST=app/src/main/assets/model-spk
SPK_NAME=vosk-model-spk-0.4
if [ ! -f "$SPK_DEST/uuid" ]; then
  TMP=$(mktemp -d)
  curl -fL --retry 3 -o "$TMP/spk.zip" "https://alphacephei.com/vosk/models/$SPK_NAME.zip"
  unzip -q "$TMP/spk.zip" -d "$TMP"
  rm -rf "$SPK_DEST"
  mv "$TMP/$SPK_NAME" "$SPK_DEST"
  (command -v uuidgen >/dev/null && uuidgen || cat /proc/sys/kernel/random/uuid) > "$SPK_DEST/uuid"
  rm -rf "$TMP"
  echo "Готово: $SPK_DEST"
fi
