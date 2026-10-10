#!/usr/bin/env python3
"""Generate the three local clips; no advertising service is contacted."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--ffmpeg', default='ffmpeg')
args = parser.parse_args()
assets = Path(__file__).resolve().parents[1] / 'src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
for index, duration in [(1, 20), (2, 30), (3, 15)]:
    subprocess.run([
        args.ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
        f'testsrc2=size=640x360:rate=12:duration={duration}', '-vf', f'hue=h={index * 70}',
        '-an', '-c:v', 'libx264', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
        '-preset', 'ultrafast', '-crf', '35', '-movflags', '+faststart', str(assets / f'ad-{index}.mp4'),
    ], check=True)
