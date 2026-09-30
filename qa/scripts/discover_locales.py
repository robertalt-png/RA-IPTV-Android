#!/usr/bin/env python3
from pathlib import Path
import re, json, sys
root=Path(sys.argv[1] if len(sys.argv)>1 else 'app/src/main/res')
locales={'default'}
for p in root.glob('values*'):
    if not p.is_dir(): continue
    name=p.name
    if name=='values': continue
    q=name[len('values-'):]
    if re.search(r'(^|-)r[A-Z]{2}($|-)', q) or re.match(r'^[a-z]{2,3}($|-)', q) or q.startswith('b+'):
        locales.add(q)
print(json.dumps(sorted(locales), ensure_ascii=False))
