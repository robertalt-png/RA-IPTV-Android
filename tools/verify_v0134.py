from pathlib import Path
import sys
if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}: raise SystemExit("usage: verify_v0134.py light|modular")
mode=sys.argv[1]; root=Path(".")
def r(p):
    x=root/p
    if not x.exists(): raise SystemExit("V0134_GATE_FAIL missing:"+p)
    return x.read_text(encoding="utf-8")
def need(c,n):
    if not c: raise SystemExit("V0134_GATE_FAIL "+n)
    print("V0134_GATE_OK "+n)
g=r("app/build.gradle"); main=r("app/src/main/java/com/robertalt/raiptv/MainActivity.java"); store=r("app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java"); instr=r("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"); arch=r("NENOTV_ARCHITECTURE.txt")
vc=77 if mode=="light" else 78; vn="0.13.4-light-test" if mode=="light" else "0.13.4-play1"
need(f"versionCode {vc}" in g,"version_code"); need(f"versionName '{vn}'" in g,"version_name")
need("categoriesFresh(" in store,"category_freshness_api")
need("cachedCategories(key,type)" in main and "categoriesFresh(key,type,30L*60L*1000L)" in main,"background_index_reuses_disk_categories")
need("restart_category_network_fetch=avoided_when_cache_fresh" in arch,"architecture_speed_marker")
need('final String[] baseTypes={"live","vod","series"};' in main,"live_first_background_index")
need("Category cache not fresh" in instr,"category_cache_instrumentation")
need("NENOTV_RESUME_VERIFY" in instr,"resume_test_preserved")
if mode=="light":
    need("LIGHT_BUILD', 'true'" in g,"light_flag"); need("dynamicFeatures" not in g,"light_no_pro")
else:
    need("LIGHT_BUILD', 'false'" in g,"modular_flag"); need("dynamicFeatures = [':proextras']" in g,"modular_pro")
print("V0134_GATE_PASS",mode)
