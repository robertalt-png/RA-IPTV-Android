#!/usr/bin/env python3
import re, sys, json
path=sys.argv[1]
text=open(path,encoding='utf-8',errors='replace').read()
patterns={
 'fatal_exception':r'FATAL EXCEPTION',
 'anr':r'ANR in com\.robertalt\.raiptv',
 'process_died':r'Process com\.robertalt\.raiptv .* has died',
 'possible_secret':r'(?i)(password|passwd|token|authorization)\s*[:=]\s*[^\s]{6,}'
}
findings={k:len(re.findall(v,text)) for k,v in patterns.items()}
print(json.dumps(findings,indent=2))
if findings['fatal_exception'] or findings['anr'] or findings['possible_secret']:
    sys.exit(2)
