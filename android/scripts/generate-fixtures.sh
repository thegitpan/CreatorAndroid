#!/usr/bin/env bash
set -euo pipefail
output="${1:?Usage: generate-fixtures.sh OUTPUT_DIRECTORY}"
command -v ffmpeg >/dev/null || { echo "Install ffmpeg to generate Android media test fixtures." >&2; exit 1; }
mkdir -p "$output"
temporary="$(mktemp -d "$output/.fixtures.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=30:duration=4' -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=4' -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -b:a 96k -shortest -movflags +faststart "$temporary/landscape-tone.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=360x640:rate=30:duration=3' -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an -movflags +faststart "$temporary/portrait-silent.mp4"
ffmpeg -hide_banner -loglevel error -y -display_rotation 90 -i "$temporary/landscape-tone.mp4" -c copy "$temporary/rotated-tone.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=black:size=640x360:rate=30:duration=4,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='between(mod(n,30),15,16)'" -f lavfi -i 'aevalsrc=0.8*sin(2*PI*1000*t)*gte(mod(n\,48000)\,24000)*lt(mod(n\,48000)\,27200):s=48000:d=4' -c:v libx264 -preset ultrafast -g 90 -keyint_min 90 -sc_threshold 0 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest -movflags +faststart "$temporary/sync-markers.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=black:size=640x360:rate=30:duration=60,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='between(n,1755,1756)'" -f lavfi -i 'aevalsrc=0.8*sin(2*PI*1000*t)*gte(n\,2808000)*lt(n\,2811200):s=48000:d=60' -c:v libx264 -preset ultrafast -g 90 -keyint_min 90 -sc_threshold 0 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest -movflags +faststart "$temporary/sync-markers-long.mp4"
for name in landscape-tone.mp4 portrait-silent.mp4 rotated-tone.mp4 sync-markers.mp4 sync-markers-long.mp4; do mv "$temporary/$name" "$output/$name"; done
