from pathlib import Path
import sys
if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}: raise SystemExit("usage: verify_v0136.py light|modular")
mode=sys.argv[1]; root=Path(".")
def r(p):
 x=root/p
 if not x.exists(): raise SystemExit("V0136_GATE_FAIL missing:"+p)
 return x.read_text(encoding="utf-8")
def need(c,n):
 if not c: raise SystemExit("V0136_GATE_FAIL "+n)
 print("V0136_GATE_OK "+n)
g=r("app/build.gradle"); main=r("app/src/main/java/com/robertalt/raiptv/MainActivity.java"); profile=r("app/src/main/java/com/robertalt/raiptv/ProfileActivity.java"); policy=r("app/src/main/java/com/robertalt/raiptv/DemoPolicy.java"); store=r("app/src/main/java/com/robertalt/raiptv/storage/SecureProfileStore.java"); instr=r("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java"); arch=r("NENOTV_ARCHITECTURE.txt")
vc=81 if mode=="light" else 82; vn="0.13.6-light-test" if mode=="light" else "0.13.6-play1"
need(f"versionCode {vc}" in g,"version_code"); need(f"versionName '{vn}'" in g,"version_name")
need("DURATION_MS=30L*24L*60L*60L*1000L" in policy,"exact_30_day_policy")
need("demo_consumed" in policy and "startOrKeep" in policy,"one_time_demo_policy")
need("expireDemoProfileIfNeeded()" in main and 'profiles.clear()' in main,"expired_demo_removed")
need("DemoPolicy.expired(this)" in profile and "DemoPolicy.startOrKeep" in profile,"onboarding_uses_demo_policy")
need("public void clear()" in store,"profile_clear_api")
need("Demo restart extended expiry" in instr and "Expired demo restarted" in instr,"demo_policy_instrumentation")
need("demo_policy=one_time_30_days" in arch,"architecture_marker")
if mode=="light": need("LIGHT_BUILD', 'true'" in g,"light_flag"); need("dynamicFeatures" not in g,"light_no_pro")
else: need("LIGHT_BUILD', 'false'" in g,"modular_flag"); need("dynamicFeatures = [':proextras']" in g,"modular_pro")
print("V0136_GATE_PASS",mode)
