#!/usr/bin/env python3
"""Independent decoded A/V marker check; requires ffmpeg/ffprobe, Python stdlib only."""
import argparse
import array
import json
import math
import subprocess
from pathlib import Path


def command(args):
    return subprocess.check_output(args)


def video_markers(path):
    frames = json.loads(command([
        'ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames',
        '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(path),
    ]))['frames']
    data = command([
        'ffmpeg', '-v', 'error', '-i', str(path), '-map', '0:v:0', '-an',
        '-vf', 'scale=16:16:flags=area,format=gray', '-fps_mode', 'passthrough',
        '-f', 'rawvideo', '-pix_fmt', 'gray', '-',
    ])
    count = len(data) // 256
    if count != len(frames):
        raise AssertionError(f'Decoded frame/timestamp count differs: {count}/{len(frames)}')
    starts = []
    previous_white = False
    for index, frame in enumerate(frames):
        white = sum(data[index * 256:(index + 1) * 256]) / 256 > 128
        if white and not previous_white:
            starts.append(round(float(frame['best_effort_timestamp_time']) * 1_000_000))
        previous_white = white
    return starts


def audio_markers(path):
    stream = json.loads(command(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=start_time', '-of', 'json', str(path)]))['streams'][0]
    start_us = round(float(stream.get('start_time', '0')) * 1_000_000)
    samples = array.array('f')
    samples.frombytes(command([
        'ffmpeg', '-v', 'error', '-i', str(path), '-map', '0:a:0',
        '-ac', '1', '-ar', '48000', '-f', 'f32le', '-',
    ]))
    # One millisecond RMS suppresses AAC pre-echo while resolving a frame by >30 bins.
    step = 48
    starts = []
    previous_active = False
    for index in range(0, len(samples) - step + 1, step):
        rms = math.sqrt(sum(value * value for value in samples[index:index + step]) / step)
        active = rms > 0.15
        if active and not previous_active:
            starts.append(start_us + round(index * 1_000_000 / 48_000))
        previous_active = active
    return starts


def check(path, expected, tolerance):
    video = video_markers(path)
    audio = audio_markers(path)
    report = {'file': path.name, 'expected_us': expected, 'video_us': video, 'audio_us': audio,
              'tolerance_us': tolerance}
    counts_match = len(video) == len(audio) == len(expected)
    if counts_match:
        report['audio_minus_video_us'] = [a - v for a, v in zip(audio, video)]
        report['video_minus_expected_us'] = [v - e for v, e in zip(video, expected)]
        report['audio_minus_expected_us'] = [a - e for a, e in zip(audio, expected)]
    report['pass'] = counts_match and all(abs(a - v) <= tolerance for a, v in zip(audio, video)) and all(
        abs(v - e) <= tolerance for v, e in zip(video, expected))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--expected-us', required=True, help='Comma-separated source/timeline flash timestamps')
    parser.add_argument('--tolerance-us', type=int, default=33_334)
    parser.add_argument('--json-out', type=Path)
    args = parser.parse_args()
    expected = [int(value) for value in args.expected_us.split(',')]
    report = check(args.file, expected, args.tolerance_us)
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.json_out:
        args.json_out.write_text(rendered + '\n')
    raise SystemExit(0 if report['pass'] else 1)


if __name__ == '__main__':
    main()
