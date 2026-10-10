#!/usr/bin/env python3
"""Compare every original TCL class with the corresponding packaged runtime AAR."""
from pathlib import Path
import hashlib
import io
import json
import zipfile

root = Path(__file__).resolve().parents[1]
summary = []
for component in ("base", "player", "media"):
    source = root / f"vendor/tcl-{component}-2.8.02.aar"
    output = root / f"runtime-{component}/build/outputs/aar/runtime-{component}-release.aar"
    with zipfile.ZipFile(source) as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as jar:
            original = {n: jar.read(n) for n in jar.namelist() if n.endswith(".class")}
    packaged = {}
    with zipfile.ZipFile(output) as aar:
        for name in aar.namelist():
            if name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(aar.read(name))) as jar:
                    for entry in jar.namelist():
                        if entry.endswith(".class"):
                            if entry in packaged:
                                raise SystemExit(f"Duplicate packaged class: {entry}")
                            packaged[entry] = jar.read(entry)
    missing = [n for n in original if n not in packaged]
    changed = [n for n in original if n in packaged and original[n] != packaged[n]]
    if missing or changed:
        raise SystemExit(f"{component}: missing={missing}, changed={changed}")
    summary.append({"component": component, "classes": len(original), "changed": 0,
                    "missing": 0, "input_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                    "packaged_aar": str(output)})
print(json.dumps(summary, indent=2))
