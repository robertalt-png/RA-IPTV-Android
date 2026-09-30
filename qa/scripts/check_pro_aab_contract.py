#!/usr/bin/env python3
import argparse, json, sys, zipfile
from pathlib import Path

MLKIT_PROVIDER_MANIFEST=b"MlKitInitProvider"
MLKIT_PROVIDER_DEX=b"com/google/mlkit/common/internal/MlKitInitProvider"

ap=argparse.ArgumentParser()
ap.add_argument("aab")
ap.add_argument("--report", default="qa-pro-packaging-contract.json")
args=ap.parse_args()

aab=Path(args.aab)
if not aab.exists():
    raise SystemExit(f"AAB not found: {aab}")

with zipfile.ZipFile(aab) as z:
    names=z.namelist()
    def read_if(name):
        try: return z.read(name)
        except KeyError: return b""
    base_manifest=read_if("base/manifest/AndroidManifest.xml")
    base_dex=b"".join(z.read(n) for n in names if n.startswith("base/dex/classes") and n.endswith(".dex"))
    features=sorted({n.split("/",1)[0] for n in names if "/" in n and not n.startswith(("base/","BUNDLE-METADATA/")) and n.split("/",1)[0] not in {"BundleConfig.pb"}})
    feature_info={}
    feature_has_provider_class=False
    for mod in features:
        manifest=read_if(f"{mod}/manifest/AndroidManifest.xml")
        dex=b"".join(z.read(n) for n in names if n.startswith(f"{mod}/dex/classes") and n.endswith(".dex"))
        feature_info[mod]={
            "manifest_mentions_mlkit_provider": MLKIT_PROVIDER_MANIFEST in manifest,
            "dex_contains_mlkit_provider_class": MLKIT_PROVIDER_DEX in dex,
        }
        feature_has_provider_class |= MLKIT_PROVIDER_DEX in dex

report={
    "aab": str(aab),
    "base_manifest_mentions_mlkit_provider": MLKIT_PROVIDER_MANIFEST in base_manifest,
    "base_dex_contains_mlkit_provider_class": MLKIT_PROVIDER_DEX in base_dex,
    "feature_contains_mlkit_provider_class": feature_has_provider_class,
    "features": feature_info,
    "status": "pass",
    "violations": [],
}

# Base must be independently startable when dynamic features are on-demand.
if report["base_manifest_mentions_mlkit_provider"] and not report["base_dex_contains_mlkit_provider_class"]:
    report["violations"].append(
        "Base manifest registers MlKitInitProvider but base DEX does not contain the provider class. "
        "An on-demand feature cannot satisfy a provider needed during base process startup."
    )

for mod,info in feature_info.items():
    if info["manifest_mentions_mlkit_provider"] and not info["dex_contains_mlkit_provider_class"]:
        report["violations"].append(
            f"{mod} manifest registers MlKitInitProvider but that module DEX lacks the provider class."
        )

if report["violations"]:
    report["status"]="fail"

Path(args.report).write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,indent=2))
if report["violations"]:
    sys.exit(2)
