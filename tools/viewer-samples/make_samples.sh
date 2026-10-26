#!/usr/bin/env bash
# Makes the debug build's file viewer samples (Settings › Developer › File viewer samples) into
# app/src/debug/assets/viewer-samples/, with index.tsv listing each one's group, name, the MIME
# type it is opened as and its size. Every sample is made here, from test patterns, tones and
# text of our own; nothing is downloaded.
#
# Needs macOS, for VideoToolbox's HEVC Main 10 encoder (as an iPhone records; libx265 builds are
# often 8-bit only) and sips for the HEIC sample; ffmpeg built with libx264, libx265, libaom,
# libvpx, libmp3lame, libopus, libvorbis and libwebp; and python3. Run it from anywhere:
#   tools/viewer-samples/make_samples.sh
# then run ViewerSamplesIndexTest, whose table needs an answer for every new sample.

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/app/src/debug/assets/viewer-samples"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/viewer-samples.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

command -v ffmpeg >/dev/null || { echo "error: ffmpeg not found" >&2; exit 1; }
command -v sips >/dev/null || { echo "error: sips not found (macOS only, for HEIC)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
# A moving test pattern and a 440 Hz tone, [seconds] long at [size] and [rate].
pattern() { echo "testsrc2=size=$1:rate=$2:duration=$3"; }
tone() { echo "sine=frequency=440:sample_rate=48000:duration=$1"; }

# --- Video -----------------------------------------------------------------------------------
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 30 -c:a aac -b:a 64k -movflags +faststart "$OUT/h264.mp4"
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 30 -c:a aac -b:a 64k -movflags +faststart "$OUT/h264.mov"
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libx265 -x265-params log-level=error -pix_fmt yuv420p -crf 32 -tag:v hvc1 -c:a aac -b:a 64k \
  -movflags +faststart "$OUT/hevc-8bit.mov"
# As an iPhone records: HEVC Main 10 from VideoToolbox in a QuickTime file, tagged hvc1, HLG in
# BT.2020.
HLG="setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc,format=p010le"
ff -f lavfi -i "$(pattern 1920x1080 30 2)" -f lavfi -i "$(tone 2)" -vf "$HLG" \
  -c:v hevc_videotoolbox -profile:v main10 -b:v 2M -tag:v hvc1 \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -c:a aac -b:a 64k \
  -movflags +faststart "$OUT/hevc-main10-1080p.mov"
# The same at 4K 60 fps, which a low-end phone's decoder may not take.
ff -f lavfi -i "$(pattern 3840x2160 60 1.5)" -f lavfi -i "$(tone 1.5)" -vf "$HLG" \
  -c:v hevc_videotoolbox -profile:v main10 -b:v 4M -tag:v hvc1 \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -c:a aac -b:a 64k \
  -movflags +faststart "$OUT/hevc-main10-4k60.mov"
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libaom-av1 -cpu-used 8 -crf 40 -b:v 0 -pix_fmt yuv420p -c:a aac -b:a 64k -movflags +faststart "$OUT/av1.mp4"
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libvpx-vp9 -crf 40 -b:v 0 -deadline realtime -cpu-used 8 -c:a libopus -b:a 48k "$OUT/vp9.webm"
ff -f lavfi -i "$(pattern 640x360 30 2)" -f lavfi -i "$(tone 2)" \
  -c:v libx264 -pix_fmt yuv420p -crf 30 -c:a aac -b:a 64k "$OUT/h264.mkv"
ff -f lavfi -i "$(pattern 176x144 15 2)" -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=2" \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 30 -c:a aac -b:a 24k -ac 1 "$OUT/h264.3gp"

# --- Audio -----------------------------------------------------------------------------------
ff -f lavfi -i "$(tone 3)" -c:a libmp3lame -b:a 64k "$OUT/tone.mp3"
ff -f lavfi -i "$(tone 3)" -c:a aac -b:a 64k "$OUT/tone.m4a"
ff -f lavfi -i "$(tone 3)" -c:a libvorbis -q:a 2 "$OUT/tone.ogg"
ff -f lavfi -i "$(tone 3)" -c:a libopus -b:a 32k "$OUT/tone.opus"
ff -f lavfi -i "sine=frequency=440:sample_rate=22050:duration=2" -c:a flac "$OUT/tone.flac"
ff -f lavfi -i "sine=frequency=440:sample_rate=22050:duration=2" -c:a pcm_s16le -ac 1 "$OUT/tone.wav"

# --- Images ----------------------------------------------------------------------------------
ff -f lavfi -i "$(pattern 640x360 1 1)" -frames:v 1 -q:v 5 "$OUT/pattern.jpg"
ff -f lavfi -i "$(pattern 640x360 1 1)" -frames:v 1 "$OUT/pattern.png"
ff -f lavfi -i "$(pattern 320x180 10 2)" -vf "split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse" "$OUT/animated.gif"
ff -f lavfi -i "$(pattern 640x360 1 1)" -frames:v 1 -c:v libwebp -quality 60 "$OUT/pattern.webp"
ff -f lavfi -i "$(pattern 320x180 1 1)" -frames:v 1 -pix_fmt bgr24 "$OUT/pattern.bmp"
sips -s format heic "$OUT/pattern.png" --out "$OUT/pattern.heic" >/dev/null

# --- Documents, archives and edge cases ------------------------------------------------------
python3 "$HERE/make_documents.py" "$OUT"

# Edge cases made from the samples above.
head -c "$(( $(wc -c <"$OUT/h264.mp4") / 3 ))" "$OUT/h264.mp4" >"$OUT/truncated.mp4"
cp "$OUT/h264.mp4" "$OUT/mp4-named.mov"
cp "$OUT/h264.mov" "$OUT/mov-named.mp4"
: >"$OUT/zero-bytes.pdf"

python3 "$HERE/write_index.py" "$OUT"
echo "Made $(($(wc -l <"$OUT/index.tsv") - 1)) samples, $(du -sh "$OUT" | cut -f1) in all, in $OUT"
