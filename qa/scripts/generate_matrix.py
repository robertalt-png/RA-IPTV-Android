#!/usr/bin/env python3
import json, argparse
p=argparse.ArgumentParser()
p.add_argument('--layer',choices=['smoke','full','release','nightly'],default='smoke')
p.add_argument('--locales',default='["default"]')
a=p.parse_args()
locales=json.loads(a.locales)
apis={'smoke':[35],'full':[26,30,34,35],'release':[26,30,34,35],'nightly':[26,30,34,35,36]}[a.layer]
include=[]
for ed in ['lite','pro']:
    include.append({'edition':ed,'api':35,'locale':'default','form_factor':'phone','suite':'core'})
if a.layer!='smoke':
    for ed in ['lite','pro']:
        for api in apis:
            include.append({'edition':ed,'api':api,'locale':'default','form_factor':'phone','suite':'compat'})
    for ed in ['lite','pro']:
        for loc in locales:
            include.append({'edition':ed,'api':35,'locale':loc,'form_factor':'phone','suite':'locale'})
    include += [
      {'edition':'lite','api':35,'locale':'default','form_factor':'tablet','suite':'layout'},
      {'edition':'pro','api':35,'locale':'default','form_factor':'tablet','suite':'layout'},
      {'edition':'pro','api':35,'locale':'default','form_factor':'android_tv','suite':'tv'},
    ]
seen=set(); out=[]
for x in include:
    k=tuple(sorted(x.items()))
    if k not in seen:
        seen.add(k); out.append(x)
print(json.dumps({'include':out},separators=(',',':')))
