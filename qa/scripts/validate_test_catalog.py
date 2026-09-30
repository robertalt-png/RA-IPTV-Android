#!/usr/bin/env python3
import json, sys, collections
p=sys.argv[1] if len(sys.argv)>1 else 'qa/catalog/test-catalog.json'
d=json.load(open(p,encoding='utf-8'))
tests=d['tests']; ids=[t['id'] for t in tests]
assert len(ids)==len(set(ids)), 'duplicate test ids'
required={'id','domain','title','layers','editions','severity','status','expected'}
allowed=set(d.get('statuses',[]))
for t in tests:
    missing=required-set(t)
    assert not missing, f"{t.get('id')}: missing {missing}"
    assert t['status'] in allowed, f"{t['id']}: invalid status {t['status']}"
counts=collections.Counter(t['status'] for t in tests)
print(json.dumps({'github_catalog_count':len(tests),'status_counts':dict(counts),'canonical_project_catalog':d.get('canonical_project_catalog',{})},indent=2))
