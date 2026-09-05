#!/usr/bin/env bash
#
# Downloads audio from YouTube channels or playlists and converts it into the
# 16 kHz mono WAV files the training pipeline reads.
#
# Run this on your own machine: it needs yt-dlp, ffmpeg, and a network that can
# reach YouTube. Nothing here is called by the build.
#
#   tools/fetch-corpus.sh ary "https://www.youtube.com/@<ARY channel>/videos" 8
#   tools/fetch-corpus.sh humtv "https://www.youtube.com/@<Hum TV channel>/videos" 8
#
# Downloading from YouTube is against its Terms of Service; whether that matters
# for a private, non-redistributed training set is your call to make, not this
# script's. Prefer material you have rights to where you can.
#
# Arguments:
#   $1  source tag, used as the folder name and the manifest's source column
#   $2  channel, playlist or video URL
#   $3  how many videos to take (default 6)
#   $4  seconds to keep from each video (default 600 - ten minutes is plenty)

set -euo pipefail

SOURCE="${1:?usage: fetch-corpus.sh <source-tag> <url> [count] [seconds-per-video]}"
URL="${2:?missing URL}"
COUNT="${3:-6}"
SECONDS_PER_VIDEO="${4:-600}"

OUT_DIR="${AUTOTUNE_DATA_DIR:-data}/${SOURCE}"
RAW_DIR="${OUT_DIR}/.raw"

for tool in yt-dlp ffmpeg; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "error: $tool is not installed." >&2
        echo "  macOS:  brew install yt-dlp ffmpeg" >&2
        echo "  Debian: sudo apt install ffmpeg && pipx install yt-dlp" >&2
        exit 1
    }
done

mkdir -p "$RAW_DIR" "$OUT_DIR"

echo "==> fetching up to $COUNT items from $URL"
yt-dlp \
    --playlist-end "$COUNT" \
    --extract-audio \
    --audio-format wav \
    --audio-quality 0 \
    --no-playlist-reverse \
    --ignore-errors \
    --no-overwrites \
    --output "${RAW_DIR}/%(id)s.%(ext)s" \
    "$URL"

echo "==> converting to 16 kHz mono"
shopt -s nullglob
for raw in "${RAW_DIR}"/*.wav; do
    name="$(basename "${raw%.*}")"
    target="${OUT_DIR}/${SOURCE}-${name}.wav"
    [ -f "$target" ] && continue
    # Mono at the analysis rate, trimmed: the model only ever sees 16 kHz mono,
    # so storing anything richer just costs disk.
    ffmpeg -loglevel error -y -i "$raw" \
        -t "$SECONDS_PER_VIDEO" \
        -ac 1 -ar 16000 -c:a pcm_s16le \
        "$target"
    echo "    $(basename "$target")"
done

TOTAL=$(find "$OUT_DIR" -maxdepth 1 -name '*.wav' | wc -l | tr -d ' ')
echo
echo "==> $TOTAL files in $OUT_DIR"
echo "Next:"
echo "  ./gradlew :model-training:autoLabel --args=\"$OUT_DIR --source=$SOURCE\""
echo "  # correct the labels it got wrong, starting with $OUT_DIR/review.csv"
echo "  ./gradlew :model-training:trainModel --args=\"--data=$OUT_DIR/labels.csv --fine-tune\""
echo
echo "The raw downloads in $RAW_DIR are no longer needed and can be deleted."
