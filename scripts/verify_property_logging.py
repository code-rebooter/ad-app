#!/usr/bin/env python3
"""Audit APK DEX and all JARs inside AARs for raw Android Log / process stream bypasses."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')))
parser.add_argument('artifacts', nargs='+', type=Path)
args = parser.parse_args()
for artifact in args.artifacts:
    if not artifact.is_file():
        parser.error(f'Artifact does not exist: {artifact}')
libraries = args.sdk / 'cmdline-tools/latest/lib'
if not libraries.is_dir():
    parser.error('Install Android SDK command-line tools or pass --sdk')
classpath = os.pathsep.join(str(p) for p in libraries.rglob('*.jar'))
with tempfile.TemporaryDirectory(prefix='property-log-audit-') as directory:
    subprocess.run(['javac', '-cp', classpath, '-d', directory,
                    str(Path(__file__).with_name('PropertyLogArtifactAudit.java'))], check=True)
    result = subprocess.run(['java', '-cp', directory + os.pathsep + classpath,
                             'PropertyLogArtifactAudit', *(str(p) for p in args.artifacts)])
    raise SystemExit(result.returncode)
