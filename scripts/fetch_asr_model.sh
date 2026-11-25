#!/usr/bin/env bash
# Downloads the sherpa-onnx Android AAR and Alibaba FunASR SenseVoiceSmall (int8) offline Chinese ASR model.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="2.0.3"
MODEL="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$ROOT/app/libs" "$ROOT/app/src/full/assets/asr/sensevoice"
if [ ! -f "$ROOT/app/libs/sherpa-onnx-$SHERPA_VERSION.aar" ]; then
  curl -fL -o "$ROOT/app/libs/sherpa-onnx-$SHERPA_VERSION.aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
fi
if [ ! -f "$ROOT/app/src/full/assets/asr/sensevoice/model.int8.onnx" ]; then
  curl -fL -o "$TMP/model.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL.tar.bz2"
  tar xjf "$TMP/model.tar.bz2" -C "$TMP"
  cp "$TMP/$MODEL/model.int8.onnx" "$TMP/$MODEL/tokens.txt" "$TMP/$MODEL/LICENSE" \
    "$ROOT/app/src/full/assets/asr/sensevoice/"
fi
echo "Offline ASR assets ready."
