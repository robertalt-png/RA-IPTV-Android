from pathlib import Path
import re, sys

if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}:
    raise SystemExit("usage: finalize_v0135.py light|modular")
mode=sys.argv[1]
root=Path(".")
gradle=root/"app/build.gradle"
text=gradle.read_text(encoding="utf-8")
vc=79 if mode=="light" else 80
vn="0.13.5-light-test" if mode=="light" else "0.13.5-play1"
text=re.sub(r"versionCode\s+\d+",f"versionCode {vc}",text,count=1)
text=re.sub(r"versionName\s+'[^']+'",f"versionName '{vn}'",text,count=1)
gradle.write_text(text,encoding="utf-8")

arch=root/"NENOTV_ARCHITECTURE.txt"
a=arch.read_text(encoding="utf-8")
a=a.replace("NenoTV v0.13.4","NenoTV v0.13.5")
a=re.sub(r"versionCode=\d+",f"versionCode={vc}",a,count=1)
a=re.sub(r"versionName=.*",f"versionName={vn}",a,count=1)
for line in ("cold_start_category_network=skip_when_disk_cache_fresh","category_cache_ttl_minutes=30"):
    if line not in a:a+=line+"\n"
arch.write_text(a,encoding="utf-8")
pro=root/"proextras/PRO_LIBRARY_ARCHITECTURE.txt"
if pro.exists():
    p=pro.read_text(encoding="utf-8").replace("v0.13.4","v0.13.5")
    pro.write_text(p,encoding="utf-8")
print(f"Finalized NenoTV {mode} {vn} vc={vc}")
