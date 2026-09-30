#!/usr/bin/env python3
from pathlib import Path
import xml.etree.ElementTree as ET
import re, sys, json
res=Path(sys.argv[1] if len(sys.argv)>1 else 'app/src/main/res')
def load(p):
    if not p.exists(): return {}
    root=ET.parse(p).getroot(); out={}
    for e in root.findall('string'):
        name=e.attrib.get('name')
        if name: out[name]=''.join(e.itertext())
    return out
base=load(res/'values'/'strings.xml')
if not base:
    raise SystemExit('Default strings.xml missing or empty')
errors=[]; report={}
ph=re.compile(r'%(?:\d+\$)?[sdfoxegc]')
for d in sorted(res.glob('values-*')):
    cur=load(d/'strings.xml')
    if not cur: continue
    missing=sorted(set(base)-set(cur))
    mismatches=[]
    for k,v in cur.items():
        if k in base and sorted(ph.findall(v))!=sorted(ph.findall(base[k])):
            mismatches.append(k)
    report[d.name]={'missing_count':len(missing),'missing':missing,'placeholder_mismatches':mismatches}
    if mismatches: errors.append(f'{d.name}: placeholder mismatch {mismatches}')
print(json.dumps(report,indent=2,ensure_ascii=False))
if errors:
    print('\n'.join(errors), file=sys.stderr); sys.exit(2)
