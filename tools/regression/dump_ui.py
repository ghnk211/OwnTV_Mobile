#!/usr/bin/env python
"""Parse an adb uiautomator dump: print clickable nodes with text/bounds.

Usage: python dump_ui.py <ui.xml> [filter-substring]
"""
import io
import re
import sys


def main():
    path = sys.argv[1]
    needle = sys.argv[2] if len(sys.argv) > 2 else None
    s = io.open(path, encoding='utf-8', errors='ignore').read()
    for chunk in s.split('<node'):
        text = re.search(r'text="([^"]*)"', chunk)
        desc = re.search(r'content-desc="([^"]*)"', chunk)
        res = re.search(r'resource-id="([^"]*)"', chunk)
        bounds = re.search(r'bounds="([^"]*)"', chunk)
        clickable = re.search(r'clickable="([^"]*)"', chunk)
        t = (text.group(1) if text else '') or (desc.group(1) if desc else '')
        if needle and needle.lower() not in (t + (res.group(1) if res else '')).lower():
            continue
        if t or clickable == 'true':
            rid = (res.group(1) if res else '')[-40:]
            b = bounds.group(1) if bounds else ''
            # centre point from bounds [x1,y1][x2,y2]
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
            centre = ''
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                centre = f'@({x},{y})'
            print(f'{t[:36]!r:40} id={rid:40} click={clickable.group(1) if clickable else "?":5} {b:28} {centre}')


if __name__ == '__main__':
    main()
