#!/usr/bin/env python3
import argparse, json, sys, zipfile
from pathlib import Path

PROVIDER=b"MlKitInitProvider"
PROVIDER_CLASS=b"com/google/mlkit/common/internal/MlKitInitProvider"

ap=argparse.ArgumentParser()
ap.add_argument("aab")
ap.add_argument("--report", default="qa-pro-packaging-contract.json")
args=ap.parse_args()
aab=Path(args.aab)
if not aab.exists():
    raise SystemExit(f"AAB not found: {aab}")

with zipfile.ZipFile(aab) as z:
    names=z.namelist()
    def read(name):
        try:return z.read(name)
        except KeyError:return b""
    base_manifest=read("base/manifest/AndroidManifest.xml")
    base_dex=b"".join(read(n) for n in names if n.startswith("base/dex/classes") and n.endswith(".dex"))
    feature_manifest=read("proextras/manifest/AndroidManifest.xml")
    feature_dex=b"".join(read(n) for n in names if n.startswith("proextras/dex/classes") and n.endswith(".dex"))

base_mentions=PROVIDER in base_manifest
base_class=PROVIDER_CLASS in base_dex
feature_mentions=PROVIDER in feature_manifest
feature_class=PROVIDER_CLASS in feature_dex

violations=[]
if base_mentions:
    violations.append(
        "MlKitInitProvider leaked into the base bundle manifest. The base must start before proextras is installed."
    )
if feature_mentions:
    violations.append(
        "MlKitInitProvider remains as automatic provider metadata in proextras. NenoTV Pro must use manual ML Kit initialization."
    )
if base_class:
    violations.append(
        "ML Kit provider implementation leaked into base DEX; heavy ML Kit code must remain in proextras."
    )
if not feature_class:
    violations.append(
        "Expected ML Kit implementation is missing from proextras DEX."
    )

report={
    "aab":str(aab),
    "base_manifest_mentions_mlkit_provider":base_mentions,
    "base_dex_contains_mlkit_provider_class":base_class,
    "proextras_manifest_mentions_mlkit_provider":feature_mentions,
    "proextras_dex_contains_mlkit_provider_class":feature_class,
    "provider_ownership":"manual-proextras" if (not base_mentions and not feature_mentions and not base_class and feature_class) else "invalid",
    "status":"fail" if violations else "pass",
    "violations":violations,
}
Path(args.report).write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2))
if violations: sys.exit(2)
