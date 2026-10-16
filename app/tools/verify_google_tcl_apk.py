#!/usr/bin/env python3
"""Audit every DEX type, packaged manifest and resource table in the Google TCL experiment."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import subprocess
import zipfile

FORBIDDEN_PREFIXES = (
    "Lcom/google/ads/interactivemedia/v3/", "Lcom/google/android/ump/", "Landroidx/media3/",
    "Lcom/tcl/ff/component/adsdkbi/", "Lcom/tcl/ff/component/overseabase/database/",
)
FORBIDDEN_PREFIXES += ("La/a/a/a/a/", "Lcom/tcl/tuniplayer_tracker/")
FORBIDDEN_PLUGIN_DEFINITIONS = ()
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "ad-sdk-tcl/tools"))
from verify_isolation_dex import audit_isolation

FORBIDDEN_HOST_CLASSES = (
    "Lcom/smart/android/ad_app/GoogleUmp", "Lcom/smart/android/ad_app/GoogleAdTvDesktopAdManager",
    "Lcom/smart/android/ad_app/GoogleAdTvDesktopFormalAd", "Lcom/smart/android/ad_app/google/GoogleAdVastPlayerView",
    "Lcom/smart/android/ad_app/Hq008TclVideoAd",
)

def dex_types_and_definitions(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX format")
    string_count, string_offset, type_count, type_offset = struct.unpack_from("<IIII", data, 56)
    def string_at(index):
        offset = struct.unpack_from("<I", data, string_offset + index * 4)[0]
        for _ in range(5):
            length_byte = data[offset]
            offset += 1
            if length_byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        return data[offset:data.index(b"\0", offset)].decode("utf-8", "replace")
    strings = [string_at(index) for index in range(string_count)]
    types = [strings[struct.unpack_from("<I", data, type_offset + index * 4)[0]] for index in range(type_count)]
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    definitions = {types[struct.unpack_from("<I", data, class_offset + index * 32)[0]] for index in range(class_count)}
    return set(types), definitions

def class_name(data):
    # Read the classfile's own name; zip entry names alone are not an ABI guarantee.
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid native classfile")
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    offset, index = 10, 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = data[offset:offset + size].decode("utf-8", "replace")
            offset += size
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = struct.unpack_from(">H", data, offset)[0]
            offset += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 15:
            offset += 3
        else:
            raise ValueError("Unknown classfile constant tag: " + str(tag))
        index += 1
    this_class = struct.unpack_from(">H", data, offset + 2)[0]
    return "L" + pool[pool[this_class]] + ";"

def audit_common_patches(apk, aapt2, manifest, mapping=None):
    expected = json.loads(manifest.read_text())
    renamed = {}
    if mapping:
        for line in mapping.read_text().splitlines():
            match = re.fullmatch(r"([^\s]+) -> ([^\s]+):", line)
            if match:
                renamed[match.group(1).replace(".", "/")] = match.group(2).replace(".", "/")
    def mapped_signature(signature):
        return re.sub(r"L([^;]+);", lambda match: "L" + renamed.get(match.group(1), match.group(1)) + ";", signature)
    targets = {"L" + item["className"][:-6] + ";": item for item in expected["changedClasses"]}
    blocks = {name: [] for name in targets}
    current = None
    sxk_calls = 0
    command = [str(aapt2.with_name("dexdump")), "-d", str(apk)]
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, errors="replace") as process:
        for line in process.stdout:
            match = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
            if match:
                current = match.group(1)
            if current in blocks:
                blocks[current].append(line)
            if current and current.startswith(("Lcom/tcl/", "Lcom/google/android/exoplayer2/", "La/a/", "Ltv/danmaku/ijk/")):
                if "invoke-static" in line and re.search(r"Hq008XhsxAarRuntimeBridge;\.(shouldApplySxkPlaybackPatch|shouldRenderSxkVideoFrame):", line):
                    sxk_calls += 1
        error = process.stderr.read()
        if process.wait():
            raise ValueError("Cannot audit packaged TCL patches: " + error)
    violations = []
    counts = {}
    for descriptor, item in targets.items():
        text = "".join(blocks[descriptor])
        actual = Counter(re.findall(r"invoke-static(?:/range)? [^\n]*Hq008XhsxAarRuntimeBridge;\.([\w$]+):", text))
        if dict(actual) != item["bridgeCalls"]:
            violations.append("Common TCL bridge mismatch: " + descriptor)
        if not text:
            violations.append("Missing patched TCL class: " + descriptor)
        counts[descriptor] = dict(actual)

    def instructions(descriptor, name, signature):
        text = "".join(blocks[descriptor])
        starts = list(re.finditer(r"(?m)^    #\d+\s*:", text))
        for index, start in enumerate(starts):
            section = text[start.start():starts[index + 1].start() if index + 1 < len(starts) else len(text)]
            method = re.search(r"name\s*:\s*'([^']+)'", section)
            typ = re.search(r"type\s*:\s*'([^']+)'", section)
            if method and typ and method.group(1) == name and typ.group(1) == mapped_signature(signature):
                return re.findall(r"\|[0-9a-f]+: (.*)", section)
        violations.append("Missing patched TCL method: " + descriptor + "." + name + signature)
        return []

    checked_policies = []
    view = "Lcom/tcl/ff/component/vastad/core/f/c;"
    lifecycle = "Lcom/tcl/ff/component/vastad/lifecycle/LifecycleOwnerAgent;"
    for name, signature in [("a", "(II)Z"), ("b", "(Landroid/view/View;)Z")]:
        code = instructions(view, name, signature)
        if len(code) != 2 or not re.search(r"const/4 .*#int 1(?:\D|$)", code[0]) or not code[1].startswith("return "):
            violations.append("Common TCL size policy missing: " + name + signature)
        checked_policies.append(view + "." + name + signature)
    for signature in ["()Lcom/tcl/ff/component/vastad/lifecycle/a;", "(Landroid/view/View;)Lcom/tcl/ff/component/vastad/lifecycle/a;", "(Landroidx/lifecycle/Lifecycle;)Lcom/tcl/ff/component/vastad/lifecycle/a;"]:
        code = instructions(lifecycle, "a", signature)
        if len(code) != 2 or ".RESUMED:" not in code[0] or not code[1].startswith("return-object "):
            violations.append("Common TCL lifecycle policy missing: " + signature)
        checked_policies.append(lifecycle + ".a" + signature)
    for descriptor, name, signature in [
        (lifecycle, "b", "(Ljava/lang/Object;)V"),
        ("Lcom/tcl/ff/component/vastad/core/d/c;", "onViewDetachedFromWindow", "(Landroid/view/View;)V"),
        ("Lcom/tcl/ff/component/vastad/core/d/e;", "onViewDetachedFromWindow", "(Landroid/view/View;)V"),
    ]:
        if instructions(descriptor, name, signature) != ["return-void"]:
            violations.append("Common TCL lifecycle method differs: " + descriptor + "." + name)
        checked_policies.append(descriptor + "." + name + signature)
    for descriptor, name in [("Lcom/tcl/ff/component/vastad/core/d/a;", "onSessionInActive"), ("Lcom/tcl/ff/component/vastad/core/d/c;", "o")]:
        code = instructions(descriptor, name, "()V")
        if any("ViewGroup;.isAttachedToWindow:" in line for line in code):
            violations.append("Common TCL attachment policy missing: " + descriptor + "." + name)
        checked_policies.append(descriptor + "." + name + "()V")
    if sxk_calls:
        violations.append("SXK policy calls entered APK: " + str(sxk_calls))
    return {"profile": expected["profile"], "changedClassesChecked": len(targets),
            "bridgeCallsByClass": counts, "policyMethodsChecked": checked_policies,
            "sxkPolicyCalls": sxk_calls, "violations": violations}


def audit(apk, aapt2, native_jars, common_patch_manifest, expected_application_id, mapping=None, isolation_manifests=()):
    all_types = set()
    all_definitions = set()
    dex_names = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                dex_names.append(name)
                types, definitions = dex_types_and_definitions(archive.read(name))
                all_types.update(types)
                all_definitions.update(definitions)
    if not dex_names:
        raise ValueError("APK has no DEX")
    violations = sorted(value for value in all_types if value.startswith(FORBIDDEN_PREFIXES + FORBIDDEN_HOST_CLASSES))
    violations.extend("Forbidden plugin implementation: " + value for value in all_definitions
                      if value in FORBIDDEN_PLUGIN_DEFINITIONS)
    manifest = subprocess.run([str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], check=True, capture_output=True, text=True).stdout
    resources = subprocess.run([str(aapt2), "dump", "resources", str(apk)], check=True, capture_output=True, text=True).stdout
    for value in ("com.google.android.gms.ads.APPLICATION_ID", "GoogleUmpConsentActivity", "GoogleUmpConsentHostTheme", "com.google.android.ump", "com.google.ads.interactivemedia", "androidx.media3"):
        if value in manifest or value in resources:
            violations.append("manifest/resource: " + value)
    badging = subprocess.run([str(aapt2), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    package_match = re.search(r"package: name='([^']+)'", badging)
    package = package_match.group(1) if package_match else None
    if package != expected_application_id:
        violations.append("Unexpected experimental package: " + str(package))
    original_classes = set()
    for jar in native_jars:
        with zipfile.ZipFile(jar) as archive:
            original_classes.update(class_name(archive.read(name)) for name in archive.namelist() if name.endswith(".class"))
    missing_original = sorted(original_classes - all_definitions)
    violations.extend("Missing original runtime class definition: " + value for value in missing_original)
    if apk.parent.name == "release":
        violations.extend("Debug network capture in release: " + value for value in all_types
                          if value.startswith("Lcom/smart/android/ad_app/GoogleTclNetworkCapture$Capture"))
    common_patches = audit_common_patches(apk, aapt2, common_patch_manifest, mapping)
    violations.extend(common_patches["violations"])
    isolation = audit_isolation(apk, aapt2.with_name("dexdump"), isolation_manifests, mapping)
    violations.extend(isolation["violations"])
    report = {
        "isolation": isolation,
        "apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "applicationId": package, "expectedApplicationId": expected_application_id, "dexFiles": sorted(dex_names), "dexTypesChecked": len(all_types), "dexClassDefinitionsChecked": len(all_definitions),
        "originalRuntimeClassesExpected": len(original_classes),
        "originalRuntimeClassesPresent": len(original_classes & all_definitions),
        "nativeVastTypes": sum(value.startswith("Lcom/tcl/ff/component/vastad/") for value in all_types),
        "nativePlayerTypes": sum(value.startswith("Lcom/tcl/ff/component/oversea/uniplayer/") for value in all_types),
        "commonPatches": common_patches,
        "forbiddenTypesAndResources": violations,
    }
    if report["nativeVastTypes"] == 0 or report["nativePlayerTypes"] == 0:
        violations.append("Missing original TCL VAST/player implementation")
    return report

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expected-application-id", required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--native-jar", type=Path, action="append", required=True)
    parser.add_argument("--common-patch-manifest", type=Path, required=True)
    parser.add_argument("--isolation-manifest", type=Path, action="append", required=True)
    parser.add_argument("--mapping", type=Path)
    args = parser.parse_args()
    report = audit(args.apk, args.aapt2, args.native_jar, args.common_patch_manifest, args.expected_application_id, args.mapping, args.isolation_manifest)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end="")
    raise SystemExit(bool(report["forbiddenTypesAndResources"]))

if __name__ == "__main__":
    main()
