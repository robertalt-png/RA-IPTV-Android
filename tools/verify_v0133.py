from pathlib import Path
import re, sys

if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}:
    raise SystemExit("usage: verify_v0133.py light|modular")
mode=sys.argv[1]
root=Path(".")
def read(p):
    path=root/p
    if not path.exists(): raise SystemExit(f"V0133_GATE_FAIL missing:{p}")
    return path.read_text(encoding="utf-8")
def need(cond,label):
    if not cond: raise SystemExit("V0133_GATE_FAIL "+label)
    print("V0133_GATE_OK "+label)

g=read("app/build.gradle")
profile=read("app/src/main/java/com/robertalt/raiptv/ProfileActivity.java")
settings=read("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java")
ui=read("app/src/main/java/com/robertalt/raiptv/UiText.java")
store=read("app/src/main/java/com/robertalt/raiptv/storage/SearchIndexStore.java")
main=read("app/src/main/java/com/robertalt/raiptv/MainActivity.java")
instr=read("app/src/androidTest/java/com/nenotv/player/ImportInstrumentation.java")
arch=read("NENOTV_ARCHITECTURE.txt")

vc=75 if mode=="light" else 76
vn="0.13.3-light-test" if mode=="light" else "0.13.3-play1"
need("applicationId 'com.nenotv.player'" in g,"package")
need(f"versionCode {vc}" in g,"version_code")
need(f"versionName '{vn}'" in g,"version_name")
need("NENOTV_DEMO_M3U_URL" in g,"demo_build_config")
need("onboarding=simplified_xtream_m3u_demo" in arch,"architecture_onboarding")
need("import_resume=durable_sqlite_checkpoint" in arch,"architecture_resume")
need("try_demo_30_days" in ui and "source_choice_intro" in ui,"localized_onboarding")
need("demoRadio" in profile and "connectAndSave" in profile,"simple_source_setup")
need("bridgeField" not in profile and "bridgeTokenField" not in profile,"bridge_removed_from_onboarding")
need("editSubtitleBridge" in settings and "bridge_help" in ui,"bridge_moved_to_settings")
need("import_progress" in store and "category_cache" in store,"durable_schema")
need("checkpointImport" in store and "cachedCategories" in store,"durable_api")
need("replaceCategories" in main and "cachedCategories" in main,"persistent_categories")
need("prepare_resume" in instr and "verify_resume" in instr,"process_resume_instrumentation")
need("NENOTV_RESUME_PREPARE" in instr and "NENOTV_RESUME_VERIFY" in instr,"resume_evidence_keys")
need("ORDER BY CASE WHEN lang_tag=" not in main+store,"light_no_auto_language_ranking")
if mode=="light":
    need("LIGHT_BUILD', 'true'" in g,"light_flag")
    need("dynamicFeatures" not in g and "feature-delivery" not in g,"light_no_dynamic_feature")
    need(not (root/"proextras").exists(),"light_no_proextras")
else:
    need("LIGHT_BUILD', 'false'" in g,"modular_flag")
    need("dynamicFeatures = [':proextras']" in g,"modular_dynamic_feature")
    pro=root/"proextras"
    need(pro.exists(),"proextras_present")
    forbidden=("provider.items","provider.categories","XtreamProvider","M3uProvider","HttpText","XtreamUrls","com.nenotv.player.provider")
    hits=[]
    for p in pro.rglob("*.java"):
        t=p.read_text(encoding="utf-8")
        for n in forbidden:
            if n in t: hits.append(f"{p}:{n}")
    need(not hits,"pro_no_provider_loading")
print("V0133_GATE_PASS",mode)
