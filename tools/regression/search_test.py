#!/usr/bin/env python
"""Drive the TVbox search overlay on the emulator with fresh coordinates each step.

Usage: python search_test.py <keyword>
"""
import io
import re
import subprocess
import sys
import time

ADB = r'E:/Android/Sdk/platform-tools/adb.exe'
SERIAL = 'emulator-5554'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'


def sh(*args):
    return subprocess.run([ADB, '-s', SERIAL] + list(args), capture_output=True, text=True)


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
    io.open(UI, 'w', encoding='utf-8').write(out)
    return out


def centre(bounds):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)


def find(xml, needle):
    for chunk in xml.split('<node'):
        text = re.search(r'text="([^"]*)"', chunk)
        bounds = re.search(r'bounds="([^"]*)"', chunk)
        if text and needle in text.group(1) and bounds:
            return centre(bounds.group(1))
    return None


def tap(x, y):
    sh('shell', 'input', 'tap', str(x), str(y))


keyword = sys.argv[1] if len(sys.argv) > 1 else '01'

# 1. open the search overlay from the VOD home top bar
xml = dump()
p = find(xml, 'Search sites')
if p is None:
    print('search entry not found on this screen; expecting VOD home')
    sys.exit(1)
# the entry text is in the overlay title, which appears once open; the top-bar icon has no text.
# Strategy: tap the top-right search icon (fixed), then re-dump.
tap(840, 343)
time.sleep(2)
xml = dump()
field = find(xml, 'Search sites')  # the text field's hint
if field is None:
    print('overlay did not open'); sys.exit(1)
tap(*field)
time.sleep(1.5)
sh('shell', 'input', 'text', keyword)
time.sleep(1)
xml = dump()
ok = find(xml, 'Search')
if ok is None:
    print('confirm button not found'); sys.exit(1)
tap(*ok)
time.sleep(8)
xml = dump()
hits = len(re.findall(r'text="([^"]*测试[^"]*)"', xml))
print('search results with 测试 in name:', hits)
m = re.search(r'text="(本地CMS)"', xml)
print('group header 本地CMS present:', bool(m))
sys.exit(0)
