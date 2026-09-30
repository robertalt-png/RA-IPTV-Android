#!/usr/bin/env python3
import argparse, json, sys, zipfile
from pathlib import Path

PROVIDER=b"MlKitInitProvider"
PROVIDER_CLASS=b"com/google/mlkit/common/internal/MlKitInitProvider"
FEATURE=b"proextras"

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

# App Bundles may encode a dynamic-feature component in the base manifest table while
# assigning it to that feature through android:splitName. In that case the provider
# class is expected to live in the feature DEX, not base DEX.
base_split_owned=base_mentions and b"splitName" in base_manifest and FEATURE in base_manifest

violations=[]
if base_mentions and not base_class and not (base_split_owned and feature_class):
    violations.append(
        "MlKitInitProvider is referenced from base without a base implementation or a valid proextras split owner."
    )
if feature_mentions and not feature_class:
    violations.append("proextras manifest references MlKitInitProvider but proextras DEX lacks the class.")
if feature_class and not feature_mentions:
    violations.append("proextras contains MlKitInitProvider but its feature manifest does not declare the provider.")

ownership=(
    "base" if base_class else
    "proextras-split" if base_split_owned and feature_class else
    "unknown"
)
report={
    "aab":str(aab),
    "base_manifest_mentions_mlkit_provider":base_mentions,
    "base_dex_contains_mlkit_provider_class":base_class,
    "base_manifest_marks_provider_as_proextras_split":base_split_owned,
    "proextras_manifest_mentions_mlkit_provider":feature_mentions,
    "proextras_dex_contains_mlkit_provider_class":feature_class,
    "provider_ownership":ownership,
    "status":"fail" if violations else "pass",
    "violations":violations,
}
Path(args.report).write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2))
if violations: sys.exit(2)
