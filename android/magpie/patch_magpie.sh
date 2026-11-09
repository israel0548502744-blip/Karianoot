#!/bin/bash
# Fetches magpie-tts.cpp (MIT) at the pinned commit, with its ggml submodule, and applies magpie_he.patch:
# the Hebrew IPA tokenizer (NeMo BaseCharsTokenizer, language key "he") and a MAGPIE_PRIOR=0/1 test switch.
# Usage: android/magpie/patch_magpie.sh <checkout dir>      (safe to run again on the same dir)
set -euo pipefail
SRC="$1"; HERE="$(cd "$(dirname "$0")" && pwd)"
REV=3008ff73fc2d2da9e4d743b09350aa7023e8980c
if [ ! -d "$SRC/.git" ]; then
  git clone -q https://github.com/mudler/magpie-tts.cpp "$SRC"
fi
cd "$SRC"
git checkout -q -f "$REV"
git submodule update --init --depth 1 -q
git apply --whitespace=nowarn "$HERE/magpie_he.patch"
echo "magpie-tts.cpp $REV + magpie_he.patch in $SRC"
