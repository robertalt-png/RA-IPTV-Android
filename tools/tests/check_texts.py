#!/usr/bin/env python3
"""Fails the build when a UI text key used in the app is missing in Dutch or English.

Missing keys make the app show raw codes such as "pro_required" to customers.
"""
import glob, re, sys

ROOT = 'android'
UITEXT = f'{ROOT}/app/src/main/java/com/robertalt/raiptv/UiText.java'
REQUIRED = ('NL', 'EN')

src = open(UITEXT, encoding='utf-8').read()
# Each language is either 'XX=map(...)' or, since 0.14.27, a method 'xx(){return map(...)}'.
starts = [(m.group(1).upper(), m.start()) for m in re.finditer(r'private static (?:final )?Map<String,String> (\w+)(?:=map|\(\)\{return map)', src)]
maps = {}
for i, (name, start) in enumerate(starts):
    end = starts[i + 1][1] if i + 1 < len(starts) else len(src)
    maps[name] = set(re.findall(r'\{"([^"]+)","', src[start:end]))

used = {}
for path in glob.glob(f'{ROOT}/app/src/main/java/**/*.java', recursive=True) + glob.glob(f'{ROOT}/proextras/src/main/java/**/*.java', recursive=True):
    text = open(path, encoding='utf-8').read()
    for pattern in (r'\bT\("([a-z0-9_]+)"\)', r'UiText\.t\(\w+,\s*"([a-z0-9_]+)"\)'):
        for key in re.findall(pattern, text):
            used.setdefault(key, path)

problems = [f'{lang}: missing "{key}" (used in {used[key]})' for lang in REQUIRED for key in sorted(used) if key not in maps[lang]]
if problems:
    print('UI text check failed:\n  ' + '\n  '.join(problems))
    sys.exit(1)
print(f'UI text check: {len(used)} keys present in {", ".join(REQUIRED)}')
