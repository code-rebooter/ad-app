#!/usr/bin/env python3
"""Run actual compiled IMA managers with a deterministic JVM Android callback queue.

Usage: JAVA_HOME=/path/to/jdk python3 run_regressions.py --sdk-classes /path/to/sdk.aar
An incremental class directory can be tested with --support-classes /path/to/previous-sdk.aar.
No device, network, playback, or measurement events are simulated or asserted here.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk-classes', type=Path, required=True)
parser.add_argument('--support-classes', type=Path)
parser.add_argument('--app-classes', type=Path)
parser.add_argument('--android-jar', type=Path)
parser.add_argument('--work-dir', type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parent
root = source.parents[5]
work = args.work_dir or Path(tempfile.mkdtemp(prefix='ima-lifecycle-regression-'))
work.mkdir(parents=True, exist_ok=True)
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'

def dependency(group, name, version):
    matches = [p for p in (cache / group / name / version).glob('*/*.jar')
               if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if not matches:
        raise SystemExit('Missing cached dependency: ' + group + ':' + name + ':' + version)
    return sorted(matches)[-1]

def classes(path, tag):
    if path.suffix == '.aar':
        output = work / (tag + '-classes.jar')
        with zipfile.ZipFile(path) as archive:
            output.write_bytes(archive.read('classes.jar'))
        return output
    return path.resolve()

android = args.android_jar
if android is None:
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    android = sdk / 'platforms/android-36/android.jar'
if not android.exists():
    raise SystemExit('Supply --android-jar for your Android SDK')
java_home = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
javac = str(java_home / 'bin/javac') if java_home else shutil.which('javac')
java = str(java_home / 'bin/java') if java_home else shutil.which('java')
classpath = [classes(args.sdk_classes, 'sdk')]
if args.support_classes:
    classpath.append(classes(args.support_classes, 'support'))
if args.app_classes:
    classpath.append(classes(args.app_classes, 'app'))
classpath += [android, dependency('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
              dependency('com.google.code.gson', 'gson', '2.13.2'),
              dependency('com.google.guava', 'guava', '33.3.1-android')]
compiled = work / 'fixture-classes'
compiled.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, classpath))
sources = sorted(source.rglob('*.java'))
subprocess.run([javac, '-cp', cp, '-d', str(compiled)] + list(map(str, sources)), check=True)
subprocess.run([java, '-cp', str(compiled) + os.pathsep + cp, 'ReentrancyRegression'], check=True)
if args.app_classes:
    subprocess.run([java, '-cp', str(compiled) + os.pathsep + cp, 'AppPendingRegression'], check=True)
print('Lifecycle fixture artifacts:', work)
