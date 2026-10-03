from pathlib import Path
import re, sys

if len(sys.argv) != 2 or sys.argv[1] not in {"light","modular"}:
    raise SystemExit("usage: finalize_v0133.py light|modular")
mode=sys.argv[1]
root=Path(".")
gradle=root/"app/build.gradle"
text=gradle.read_text(encoding="utf-8")
vc=75 if mode=="light" else 76
vn="0.13.3-light-test" if mode=="light" else "0.13.3-play1"
text=re.sub(r"versionCode\s+\d+", f"versionCode {vc}", text, count=1)
text=re.sub(r"versionName\s+'[^']+'", f"versionName '{vn}'", text, count=1)
if "NENOTV_DEMO_M3U_URL" not in text:
    marker=re.search(r"(\s*buildConfigField 'boolean', 'LIGHT_BUILD', '(?:true|false)'\s*)", text)
    if not marker:
        raise SystemExit("LIGHT_BUILD marker missing")
    addition="""\n        def demoUrl = (System.getenv('NENOTV_DEMO_M3U_URL') ?: '').replace('\\\\','\\\\\\\\').replace('"','\\\\"')
        buildConfigField 'String', 'NENOTV_DEMO_M3U_URL', '"' + demoUrl + '"'
"""
    text=text[:marker.end()]+addition+text[marker.end():]
gradle.write_text(text,encoding="utf-8")

arch=root/"NENOTV_ARCHITECTURE.txt"
a=arch.read_text(encoding="utf-8")
a=a.replace("NenoTV v0.13.2","NenoTV v0.13.3")
a=re.sub(r"versionCode=\d+",f"versionCode={vc}",a,count=1)
a=re.sub(r"versionName=.*",f"versionName={vn}",a,count=1)
for line in ("onboarding=simplified_xtream_m3u_demo","import_resume=durable_sqlite_checkpoint","category_cache=persistent"):
    if line not in a: a += line+"\n"
arch.write_text(a,encoding="utf-8")
pro=root/"proextras/PRO_LIBRARY_ARCHITECTURE.txt"
if pro.exists():
    pro.write_text(pro.read_text(encoding="utf-8").replace("v0.13.2","v0.13.3"),encoding="utf-8")
print(f"Finalized NenoTV {mode} {vn} vc={vc}")
