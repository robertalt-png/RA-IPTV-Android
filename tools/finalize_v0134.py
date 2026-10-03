from pathlib import Path
import re, sys

if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}:
    raise SystemExit("usage: finalize_v0134.py light|modular")
mode=sys.argv[1]
root=Path(".")
gradle=root/"app/build.gradle"
text=gradle.read_text(encoding="utf-8")
vc=77 if mode=="light" else 78
vn="0.13.4-light-test" if mode=="light" else "0.13.4-play1"
text=re.sub(r"versionCode\s+\d+",f"versionCode {vc}",text,count=1)
text=re.sub(r"versionName\s+'[^']+'",f"versionName '{vn}'",text,count=1)
gradle.write_text(text,encoding="utf-8")

arch=root/"NENOTV_ARCHITECTURE.txt"
a=arch.read_text(encoding="utf-8")
a=a.replace("NenoTV v0.13.3","NenoTV v0.13.4").replace("NenoTV v0.13.2","NenoTV v0.13.4")
a=re.sub(r"versionCode=\d+",f"versionCode={vc}",a,count=1)
a=re.sub(r"versionName=.*",f"versionName={vn}",a,count=1)
for line in ("category_fast_start=persisted_cache_with_freshness","restart_category_network_fetch=avoided_when_cache_fresh"):
    if line not in a:a+=line+"\n"
arch.write_text(a,encoding="utf-8")
pro=root/"proextras/PRO_LIBRARY_ARCHITECTURE.txt"
if pro.exists():
    p=pro.read_text(encoding="utf-8").replace("v0.13.3","v0.13.4").replace("v0.13.2","v0.13.4")
    pro.write_text(p,encoding="utf-8")
print(f"Finalized NenoTV {mode} {vn} vc={vc}")
