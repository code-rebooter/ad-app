#!/usr/bin/env python3
"""Capture a device scenario; use demo trace files even when system logging is off."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tarfile
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb-server-port', type=int)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--package', default='com.smart.android.tclad.demo')
p.add_argument('--case', required=True)
p.add_argument('--reference', action='store_true')
p.add_argument('--live', action='store_true')
p.add_argument('--default-config', action='store_true')
p.add_argument('--start-delay', type=int, default=0)
p.add_argument('--release-after', type=int, default=0)
p.add_argument('--fallback', action='store_true')
p.add_argument('--controls', action='store_true')
p.add_argument('--release-after-vast', type=int, default=0)
p.add_argument('--budget', type=int, default=100)
a = p.parse_args()
ADB = '/Users/zy/.cache/googleapps-build-tools/android-sdk/platform-tools/adb'
PKG = a.package
connection = [ADB] + (['-P', str(a.adb_server_port)] if a.adb_server_port else []) + ['-s', a.serial]
out = a.output
out.mkdir(parents=True, exist_ok=True)
if (out / 'capture.tar').exists():
    p.error('Output already contains a capture; choose a new directory')


def adb(*args, check=True):
    return subprocess.run([*connection, *args], capture_output=True, check=check, timeout=20)


def capture_folders():
    result = adb('shell', 'run-as', PKG, 'ls', 'files', check=False)
    return {name for name in result.stdout.decode(errors='replace').splitlines() if name.startswith('live-')}


adb('shell', 'am', 'force-stop', PKG)
previous = capture_folders()
wake = adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', check=False)
if wake.returncode:
    (out / 'wake-warning.txt').write_bytes(wake.stderr + wake.stdout)
args = ['am', 'start', '-W', '-n', PKG + '/com.smart.android.tclad.demo.DemoActivity', '--ez', 'automated', 'true']
if a.start_delay:
    args += ['--ei', 'start_delay_ms', str(a.start_delay)]
if a.default_config:
    args += ['--ez', 'use_default_config', 'true']
if a.live:
    args += ['--es', 'live_source', a.case]
else:
    args += ['--es', 'case_name', a.case, '--es', 'vast_url', 'http://127.0.0.1:18888/tag/' + a.case]
if a.reference:
    args += ['--ez', 'reference', 'true']
if a.release_after_vast:
    args += ['--ei', 'release_after_vast_ms', str(a.release_after_vast)]
if a.release_after:
    args += ['--ei', 'release_after_ms', str(a.release_after)]
if a.controls:
    args += ['--ez', 'exercise_controls', 'true']
if a.fallback:
    args += ['--es', 'guarantee_url', 'http://127.0.0.1:18888/tag/single']

folder = None
rows = []
apk = Path(__file__).resolve().parents[1] / 'build/outputs/apk/debug/ad-sdk-tcl-demo-debug.apk'
metadata = {'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'serial': a.serial, 'package': PKG, 'case': a.case, 'reference': a.reference, 'launchTimeMs': int(time.time() * 1000)}
with (out / 'logcat.txt').open('wb') as log:
    proc = subprocess.Popen([*connection, 'logcat', '-v', 'threadtime', '-T', '1'], stdout=log, stderr=subprocess.STDOUT)
    try:
        launch = adb('shell', shlex.join(args))
        (out / 'launch.txt').write_bytes(launch.stdout)
        pid = adb('shell', 'pidof', PKG, check=False).stdout.decode().strip()
        metadata['processPid'] = pid
        (out / 'run.json').write_text(json.dumps(metadata, indent=2) + '\n')
        deadline = time.monotonic() + a.budget
        terminal = None
        screenshot = False
        started = None
        while time.monotonic() < deadline:
            time.sleep(1)
            if folder is None:
                new = capture_folders() - previous
                if len(new) > 1:
                    raise RuntimeError('Multiple new capture folders: ' + str(new))
                if new:
                    folder = new.pop()
            if folder:
                events = adb('exec-out', 'run-as', PKG, 'cat', 'files/' + folder + '/events.jsonl', check=False)
                if events.returncode == 0:
                    # A concurrent last write may not yet have its trailing newline.
                    rows = [json.loads(line) for line in events.stdout.splitlines(keepends=True) if line.endswith(b'\n')]
            callbacks = [row['kind'] for row in rows if row['phase'] == 'callback']
            if started is None and any(value.startswith('STARTED') for value in callbacks):
                started = time.monotonic()
                print(a.case, 'STARTED', flush=True)
            if started and not screenshot and time.monotonic() - started >= 2:
                shot = adb('exec-out', 'screencap', '-p', check=False)
                if shot.returncode == 0:
                    (out / 'playing.png').write_bytes(shot.stdout)
                else:
                    (out / 'screenshot-error.txt').write_bytes(shot.stderr)
                screenshot = True
            text = (out / 'logcat.txt').read_text(errors='replace')
            if terminal is None and (any(re.match(r'^(FINISHED|ERROR|CONFIG_ERROR|INIT_ERROR|HOST_RELEASE)', value) for value in callbacks)
                    or re.search(r'E AndroidRuntime: Process: ' + re.escape(PKG) + r', PID:', text)):
                terminal = time.monotonic()
            if terminal and time.monotonic() - terminal >= 8:
                break
        text = (out / 'logcat.txt').read_text(errors='replace')
        lines = [line for line in text.splitlines() if re.search(r'TclNativeDemo:|TclLiveHttp:|onAdStart|onAdFinish|onAdLoaded|onAdError|acquired channel|VAST', line)]
        callbacks = [row['kind'] for row in rows if row['phase'] == 'callback']
        (out / 'events.log').write_text('\n'.join(lines + callbacks) + '\n')
        if folder:
            archive = out / 'capture.tar'
            archive.write_bytes(adb('exec-out', 'run-as', PKG, 'tar', '-cf', '-', 'files/' + folder).stdout)
            with tarfile.open(archive) as tar:
                tar.extractall(out, filter='data')
        (out / 'app-directories.txt').write_bytes(adb('shell', 'run-as', PKG, 'ls', '-laR', '.').stdout)
        network = adb('exec-out', 'run-as', PKG, 'cat', 'files/network-audit.jsonl', check=False)
        if network.returncode == 0:
            (out / 'network-audit.jsonl').write_bytes(network.stdout)
        print('\n'.join(callbacks), flush=True)
        if folder is None:
            raise RuntimeError('No demo capture was created; inspect launch.txt and logcat.txt')
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        adb('shell', 'am', 'force-stop', PKG)
