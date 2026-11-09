import io
import random
import re

raw = open(r'C:\Users\Admin\WorkBuddy\2026-11-08-22-47-57\tvbox源.txt', encoding='utf-8').read()
pairs = []
for line in raw.splitlines():
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    parts = line.split(',', 1)
    if len(parts) != 2:
        continue
    name, url = parts[0].strip(), parts[1].strip()
    if url.startswith('http') and ' ' not in url:
        pairs.append((name, url))

print(f'parseable http entries: {len(pairs)}')
random.seed(20261109)  # 可复现的随机抽取
picked = random.sample(pairs, 6)
for i, (name, url) in enumerate(picked, 1):
    print(f'{i}. {name} | {url}')
