#!/usr/bin/env python3
import re, sys, json

path=sys.argv[1]
text=open(path,encoding='utf-8',errors='replace').read()

patterns={
 'fatal_exception':r'FATAL EXCEPTION:[\\s\\S]{0,1400}?Process:\\s*com\\.robertalt\\.raiptv(?:,|\\s)',
 'anr':r'ANR in com\\.robertalt\\.raiptv',
 'process_died':r'Process com\\.robertalt\\.raiptv .* has died',
}
findings={k:len(re.findall(v,text)) for k,v in patterns.items()}

# Scan for credential-like key/value logging while excluding Android framework
# object handles and non-secret status values. These exclusions are structural,
# not NenoTV-specific credentials: a real app password/token still fails.
secret_re=re.compile(
    r'(?i)\b(password|passwd|token|authorization)\b\s*[:=]\s*["\']?([^\s,;"\']{1,200})'
)
safe_literals={
    'false','true','null','none','disabled','enabled','redacted',
    '<redacted>','***','-'
}
safe_exact={'WidevineCdmVersion','ProvisioningModel'}
safe_prefixes=(
    'android.os.Binder','BinderProxy','RemoteToken','WCT{',
    'AppWindowToken{','Token{',
)

secret_hits=[]
for m in secret_re.finditer(text):
    key=m.group(1).lower()
    value=m.group(2).strip()
    line_start=text.rfind('\n',0,m.start())+1
    line_end=text.find('\n',m.end())
    line=text[line_start:line_end if line_end>=0 else len(text)]
    if key == 'token' and re.search(r'\bI CAR\.TOKEN:', line):
        continue
    if key == 'password' and re.search(r'\bI AndroidIME:.*\bPasswordIme\.onActivate\(\)', line):
        continue
    if len(value) < 6:
        continue
    if value.lower() in safe_literals:
        continue
    if value in safe_exact:
        continue
    if value.startswith(safe_prefixes):
        continue
    # Android/Google services sometimes log phrases such as
    # "Failed to get ... token: java.io.IOException: QUOTA_EXCEEDED".
    # The matched value is an exception class, not a credential.
    if re.fullmatch(r'java(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+(?:Exception|Error):?', value):
        continue
    if value.startswith('java.') or value.startswith('javax.') or value.startswith('javascript:'):
        continue
    secret_hits.append({'key':key,'value_preview':value[:3]+'…','offset':m.start()})

findings['possible_secret']=len(secret_hits)
print(json.dumps(findings,indent=2))
if secret_hits:
    # Never echo the full suspected secret into CI output.
    print(json.dumps({'secret_hit_previews':secret_hits[:20]},indent=2),file=sys.stderr)

if findings['fatal_exception'] or findings['anr'] or findings['possible_secret']:
    sys.exit(2)

