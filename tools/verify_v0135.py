from pathlib import Path
import sys

if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}:
    raise SystemExit("usage: verify_v0135.py light|modular")
mode=sys.argv[1]; root=Path(".")
def r(p):
    x=root/p
    if not x.exists(): raise SystemExit("V0135_GATE_FAIL missing:"+p)
    return x.read_text(encoding="utf-8")
def need(c,n):
    if not c: raise SystemExit("V0135_GATE_FAIL "+n)
    print("V0135_GATE_OK "+n)

g=r("app/build.gradle")
main=r("app/src/main/java/com/robertalt/raiptv/MainActivity.java")
instr=r("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java")
arch=r("NENOTV_ARCHITECTURE.txt")
vc=79 if mode=="light" else 80
vn="0.13.5-light-test" if mode=="light" else "0.13.5-play1"
need(f"versionCode {vc}" in g,"version_code")
need(f"versionName '{vn}'" in g,"version_name")
need('searchIndex.categoriesFresh(key,s,30L*60L*1000L)' in main,"cold_start_uses_disk_freshness")
need('if(!cached.isEmpty()&&searchIndex.categoriesFresh' in main,"cold_start_skips_remote_when_fresh")
need('provider.categories(s)' in main,"stale_cache_still_refreshes")
need("cold_start_category_network=skip_when_disk_cache_fresh" in arch,"architecture_marker")
need("NENOTV_RESUME_VERIFY" in instr,"resume_gate_preserved")
if mode=="light":
    need("LIGHT_BUILD', 'true'" in g,"light_flag")
    need("dynamicFeatures" not in g,"light_no_dynamic_feature")
else:
    need("LIGHT_BUILD', 'false'" in g,"modular_flag")
    need("dynamicFeatures = [':proextras']" in g,"modular_dynamic_feature")
print("V0135_GATE_PASS",mode)
