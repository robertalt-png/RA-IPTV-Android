from pathlib import Path
import sys
if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}: raise SystemExit("usage: verify_v0137.py light|modular")
mode=sys.argv[1];root=Path(".")
def r(p):
 x=root/p
 if not x.exists():raise SystemExit("V0137_GATE_FAIL missing:"+p)
 return x.read_text(encoding="utf-8")
def need(c,n):
 if not c:raise SystemExit("V0137_GATE_FAIL "+n)
 print("V0137_GATE_OK "+n)
g=r("app/build.gradle");main=r("app/src/main/java/com/robertalt/raiptv/MainActivity.java");window=r("app/src/main/java/com/robertalt/raiptv/CategoryCompletionWindow.java");instr=r("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java");arch=r("NENOTV_ARCHITECTURE.txt")
vc=83 if mode=="light" else 84;vn="0.13.7-light-test" if mode=="light" else "0.13.7-play1"
need(f"versionCode {vc}" in g,"version_code");need(f"versionName '{vn}'" in g,"version_name")
need("categoryWindowSize=2" in main,"bounded_window_two")
need("CompletionService<Integer>" in main,"completion_service")
need("CategoryCompletionWindow" in main and "markDone" in window,"contiguous_checkpoint")
need("Concurrent category import lost rows" in instr,"parallel_sqlite_instrumentation")
need("Out-of-order category advanced cursor" in instr,"cursor_order_instrumentation")
need("category_resume_cursor=contiguous_only" in arch,"architecture_marker")
need("NENOTV_RESUME_VERIFY" in instr,"resume_test_preserved")
if mode=="light":need("LIGHT_BUILD', 'true'" in g,"light_flag");need("dynamicFeatures" not in g,"light_no_pro")
else:need("LIGHT_BUILD', 'false'" in g,"modular_flag");need("dynamicFeatures = [':proextras']" in g,"modular_pro")
print("V0137_GATE_PASS",mode)
