#!/usr/bin/env python3
"""Reuse the app's controlled IMA artifacts, redirecting only the firmware bridge ABI.

This does not generate ad URLs or modify IMA request/playback/tracking code.
Run again when updating gradle/google-ima-controlled-maven inputs.
"""
from pathlib import Path
from io import BytesIO
import struct
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MODULE = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'gradle/google-ima-controlled-maven/com/smart/android/googleima/controlled'
TARGET = MODULE / 'controlled-maven/com/smart/android/tclad/ima/controlled'
OLD_GROUP = 'com.smart.android.googleima.controlled'
NEW_GROUP = 'com.smart.android.tclad.ima.controlled'
REPLACEMENTS = (
    (b'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge',
     b'com/smart/android/tclad/ima/GoogleImaFirmwareBridge'),
    (b'com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge',
     b'com.smart.android.tclad.ima.GoogleImaFirmwareBridge'),
)


def redirect_bridge(data):
    count = struct.unpack_from('>H', data, 8)[0]
    output = bytearray(data[:10])
    pos, index = 10, 1
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
              11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[pos]
        pos += 1
        output.append(tag)
        if tag == 1:
            size = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            value = data[pos:pos + size]
            pos += size
            for old, new in REPLACEMENTS:
                value = value.replace(old, new)
            output.extend(struct.pack('>H', len(value)))
            output.extend(value)
        else:
            size = widths[tag]
            output.extend(data[pos:pos + size])
            pos += size
            if tag in (5, 6):
                index += 1
        index += 1
    output.extend(data[pos:])
    return bytes(output)


def rewrite_zip(data, aar=False):
    result = BytesIO()
    with zipfile.ZipFile(BytesIO(data)) as source, zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in sorted(source.namelist()):
            payload = source.read(entry)
            if aar and entry == 'classes.jar':
                payload = rewrite_zip(payload)
            elif not aar and entry.endswith('.class'):
                payload = redirect_bridge(payload)
            metadata = zipfile.ZipInfo(entry, (1980, 1, 1, 0, 0, 0))
            metadata.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(metadata, payload)
    return result.getvalue()


def standalone_pom(source):
    # Exclusions live in the published POM so a plain consuming app also gets one
    # copy of each runtime, without inheriting app-only resolution substitutions.
    ns = 'http://maven.apache.org/POM/4.0.0'
    ET.register_namespace('', ns)
    q = lambda name: '{' + ns + '}' + name
    root = ET.fromstring(source.read_text().replace(OLD_GROUP, NEW_GROUP))
    originals = [
        ('com.google.ads.interactivemedia.v3', 'interactivemedia'),
        ('androidx.media3', 'media3-exoplayer-ima'),
        ('com.google.android.tv', 'tv-ads'),
        ('com.google.android.gms', 'play-services-ads-identifier'),
        ('com.google.android.gms', 'play-services-appset'),
    ]
    for dependency in root.findall('./' + q('dependencies') + '/' + q('dependency')):
        exclusions = ET.SubElement(dependency, q('exclusions'))
        for group, artifact in originals:
            exclusion = ET.SubElement(exclusions, q('exclusion'))
            ET.SubElement(exclusion, q('groupId')).text = group
            ET.SubElement(exclusion, q('artifactId')).text = artifact
    ET.indent(root, space='  ')
    return ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'


def expected_outputs():
    artifacts, outputs = [], {}
    for source in sorted(SOURCE.rglob('*')):
        if source.suffix not in ('.aar', '.pom'):
            continue
        destination = TARGET / source.relative_to(SOURCE)
        if source.suffix == '.pom':
            outputs[destination] = standalone_pom(source)
        else:
            original = source.read_bytes()
            generated = rewrite_zip(original, aar=True)
            outputs[destination] = generated
            artifacts.append({
                'artifact': str(source.relative_to(SOURCE)),
                'source_sha256': hashlib.sha256(original).hexdigest(),
                'standalone_sha256': hashlib.sha256(generated).hexdigest(),
            })
    if len(artifacts) != 5:
        raise RuntimeError(f'Expected all five controlled runtime AARs, found {len(artifacts)}')
    outputs[MODULE / 'controlled-maven/runtime-manifest.json'] = (
        json.dumps({'bridge': 'com.smart.android.tclad.ima.GoogleImaFirmwareBridge',
                    'artifacts': artifacts}, indent=2) + '\n').encode()
    return outputs


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='Fail on missing or stale standalone artifacts')
    options = parser.parse_args()
    for destination, expected in expected_outputs().items():
        if options.check:
            if not destination.is_file() or destination.read_bytes() != expected:
                raise RuntimeError(f'Missing/stale standalone artifact: {destination}')
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
    print('Verified all five standalone controlled IMA dependencies.' if options.check
          else 'Generated standalone controlled IMA dependencies.')


if __name__ == '__main__':
    main()
