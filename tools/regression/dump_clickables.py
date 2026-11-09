import io
import re
import sys

s = open(r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml', encoding='utf-8', errors='ignore').read()
for chunk in s.split('<node'):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
    cl = re.search(r'clickable="(true|false)"', chunk)
    desc = re.search(r'content-desc="([^"]*)"', chunk)
    cls = re.search(r'class="([^"]*)"', chunk)
    if b and cl and cl.group(1) == 'true':
        x1, y1, x2, y2 = (int(g) for g in b.groups())
        print(f'clickable ({(x1+x2)//2},{(y1+y2)//2}) desc={desc.group(1) if desc else ""!r} class={cls.group(1).split(".")[-1] if cls else "?"}')
