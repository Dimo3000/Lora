#!/usr/bin/env bash
# Скачивает русскую модель Vosk (~45 МБ) в assets, чтобы распознавание речи было встроено в APK.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/model-ru
NAME=vosk-model-small-ru-0.22
if [ -f "$DEST/uuid" ]; then echo "Модель уже на месте: $DEST"; exit 0; fi
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
