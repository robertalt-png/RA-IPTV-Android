from pathlib import Path
import sys
if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}:raise SystemExit("usage: verify_v0138.py light|modular")
mode=sys.argv[1];root=Path(".")
def r(p):
 x=root/p
 if not x.exists():raise SystemExit("V0138_GATE_FAIL missing:"+p)
 return x.read_text(encoding="utf-8")
def need(c,n):
 if not c:raise SystemExit("V0138_GATE_FAIL "+n)
 print("V0138_GATE_OK "+n)
g=r("app/build.gradle");main=r("app/src/main/java/com/robertalt/raiptv/MainActivity.java");profile=r("app/src/main/java/com/robertalt/raiptv/ProfileActivity.java");instr=r("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java");arch=r("NENOTV_ARCHITECTURE.txt")
vc=85 if mode=="light" else 86;vn="0.13.8-light-test" if mode=="light" else "0.13.8-play1"
need(f"versionCode {vc}" in g,"version_code");need(f"versionName '{vn}'" in g,"version_name")
need('final String firstType=order.isEmpty()?"live":order.get(0)' in main,"first_section_category_discovery")
need("type.equals(firstType)" in main,"later_categories_deferred")
need('putInt("first_sync_total_count_"+key,grandTotal)' in main,"discovered_total_persisted")
need('putInt("first_sync_total_count_"+key,total)' in main,"banner_total_persisted")
need("progress_total=persisted_continuously" in arch,"architecture_marker")
need("demoRadio.setOnClickListener(v->{updateMode();if(demoRadio.isEnabled())connectAndSave();});" in profile,"one_tap_demo")
need("NENOTV_RESUME_VERIFY" in instr,"resume_test_preserved")
if mode=="light":need("LIGHT_BUILD', 'true'" in g,"light_flag");need("dynamicFeatures" not in g,"light_no_pro")
else:need("LIGHT_BUILD', 'false'" in g,"modular_flag");need("dynamicFeatures = [':proextras']" in g,"modular_pro")
print("V0138_GATE_PASS",mode)
