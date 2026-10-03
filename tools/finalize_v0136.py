from pathlib import Path
import re,sys
if len(sys.argv)!=2 or sys.argv[1] not in {"light","modular"}: raise SystemExit("usage: finalize_v0136.py light|modular")
mode=sys.argv[1]; root=Path(".")
g=root/"app/build.gradle"; s=g.read_text(encoding="utf-8")
vc=81 if mode=="light" else 82; vn="0.13.6-light-test" if mode=="light" else "0.13.6-play1"
s=re.sub(r"versionCode\s+\d+",f"versionCode {vc}",s,count=1)
s=re.sub(r"versionName\s+'[^']+'",f"versionName '{vn}'",s,count=1); g.write_text(s,encoding="utf-8")
a=root/"NENOTV_ARCHITECTURE.txt"; x=a.read_text(encoding="utf-8").replace("NenoTV v0.13.5","NenoTV v0.13.6")
x=re.sub(r"versionCode=\d+",f"versionCode={vc}",x,count=1); x=re.sub(r"versionName=.*",f"versionName={vn}",x,count=1)
for line in ("demo_policy=one_time_30_days","demo_expiry=enforced_on_startup","demo_restart_does_not_extend_expiry=true"):
    if line not in x:x+=line+"\n"
a.write_text(x,encoding="utf-8")
p=root/"proextras/PRO_LIBRARY_ARCHITECTURE.txt"
if p.exists(): p.write_text(p.read_text(encoding="utf-8").replace("v0.13.5","v0.13.6"),encoding="utf-8")
print(f"Finalized NenoTV {mode} {vn} vc={vc}")
