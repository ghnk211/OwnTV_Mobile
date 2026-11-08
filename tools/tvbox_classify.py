import re, sys, importlib.util

spec = importlib.util.spec_from_file_location('chs', 'tools/i18n/check_hardcoded_strings.py')
chs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chs)

inv = chs._inventory()
safe_counts, safe_cats, _ = chs._safe_entries()
baseline = chs._parse(chs.BASELINE.read_text(encoding='utf-8'))

def excluded(counts):
    out = dict(counts)
    for src in (safe_counts, baseline):
        for k, v in src.items():
            if k in out:
                out[k] -= v
                if out[k] <= 0:
                    del out[k]
    return out

unclassified = excluded(inv)

BS = chr(92)  # backslash

def classify(text):
    t = text
    if t == '':
        return 'empty'
    if re.search(r'^https?://|^ws://|^clan://|^file://|^tcp://|^rtmp', t) or re.match(r'^[\w.-]+\.(com|cn|net|org|top|xyz|vip|fun)(/|$|:)', t):
        return 'url'
    if t.startswith('#EXT'):
        return 'protocol'
    if re.search(r'application/|text/|image/|video/|audio/|multipart/', t):
        return 'mime'
    if BS in t and re.search(r'[' + BS + BS + r'w]|[' + BS + BS + r's]|[' + BS + BS + r'd]|\(\?:', t):
        return 'regex'
    if t in ('GET', 'POST', 'HEAD', 'PUT', 'DELETE', 'OPTIONS', 'PATCH'):
        return 'protocol'
    if re.fullmatch(r'[A-Za-z][A-Za-z0-9-]{1,30}', t) and '-' in t:
        return 'protocol'   # HTTP header names like Set-Cookie
    if re.fullmatch(r'\.[A-Za-z0-9]{1,6}', t):
        return 'file-path'
    if re.fullmatch(r'[a-z][a-zA-Z0-9_]{0,40}', t):
        return 'json'       # JSON/protocol keys
    if t.startswith('tvbox:'):
        return 'preference-key'  # TVbox identity keys (content keys / lock keys)
    if t in ('*/*', 'application/json', 'text/plain', 'application/json; charset=utf-8'):
        return 'mime'
    if re.fullmatch(r'[=&|.:·$#!?@,;+%^~\[\]{}()<>/ _\-0-9a-zA-Z]*', t) and t.strip():
        return 'technical'
    if re.fullmatch(r'[A-Za-z0-9_.:\-/%{}$<>= ]{0,60}', t) and t.strip():
        return 'technical'
    return 'log'            # diagnostic messages (incl. CJK log/error strings)

targets = {k: v for k, v in unclassified.items()
           if '/tvbox/' in k[0]
           or k[0].startswith('app/src/main/java/com/github/catvod/')
           or '/ui/screens/vod/' in k[0]
           or k[0].endswith('library/VodTuner.kt')}
print(f"unclassified total={len(unclassified)}, tvbox-scope={len(targets)}")

from collections import Counter
cats = Counter()
lines = []
for (path, text), count in sorted(targets.items()):
    c = classify(text)
    cats[c] += 1
    lines.append(f"{count}\t{c}\t{path}\t{chs._escape(text)}")

with open('tools/i18n/safe_literals.txt', 'a', encoding='utf-8') as f:
    f.write('\n'.join(lines) + '\n')
print("categories:", dict(cats))
