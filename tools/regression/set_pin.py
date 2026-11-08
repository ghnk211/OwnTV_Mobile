#!/usr/bin/env python
"""Robust PIN setup: navigate the Customize sheet, enter the PIN twice with
re-dumped coordinates after every keyboard shift."""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
PIN = '1234'


def sh(*args):
    return subprocess.run([ADB, '-s', 'emulator-5554'] + list(args), capture_output=True, text=True)


def dump():
    for _ in range(3):
        sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
        if '<node' in out:
            io.open(UI, 'w', encoding='utf-8').write(out)
            return out
        time.sleep(1)
    return ''


def find_exact(xml, needle):
    for chunk in xml.split('<node'):
        t = re.search(r'text="([^"]*)"', chunk)
        b = re.search(r'bounds="([^"]*)"', chunk)
        if t and t.group(1) == needle and b:
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b.group(1))
            return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
    return None


def tap(x, y):
    sh('shell', 'input', 'tap', str(x), str(y))


def enter_pin_dialog(title):
    """Open a dialog by [title], type the PIN, tap OK (coords re-dumped after shift)."""
    xml = dump()
    field = find_exact(xml, title)
    if field is None:
        print('dialog field not found for', title)
        return False
    tap(*field)
    time.sleep(1.5)
    sh('shell', 'input', 'text', PIN)
    time.sleep(1)
    xml = dump()
    # the typed value must be in the field
    if PIN not in xml:
        print('typed PIN not present for', title)
        return False
    ok = find_exact(xml, 'OK')
    if ok is None:
        print('OK not found for', title)
        return False
    tap(*ok)
    time.sleep(2)
    return True


# 0. make sure we are on the Customize page and open its overflow menu
xml = dump()
if find_exact(xml, 'Customize') is None or find_exact(xml, 'Bulk rename') is None:
    print('not on the Customize page; aborting')
    raise SystemExit(1)
tap(1000, 393)
time.sleep(2)

# 1. Set PIN (first entry)
xml = dump()
p = find_exact(xml, 'Set PIN')
if p is None:
    print('menu shows no Set PIN — maybe already set')
else:
    tap(*p)
    time.sleep(2)
    if not enter_pin_dialog('Enter a new PIN'):
        raise SystemExit(1)
    # 2. confirm entry
    xml = dump()
    if find_exact(xml, 'Confirm new PIN') is None:
        print('no confirm step; checking menu directly')
    else:
        if not enter_pin_dialog('Confirm new PIN'):
            raise SystemExit(1)

# 3. verify via the overflow menu
tap(1000, 393)
time.sleep(2)
xml = dump()
if find_exact(xml, 'Remove PIN') is not None or find_exact(xml, 'Change PIN') is not None:
    print('PIN SET OK')
elif find_exact(xml, 'Set PIN') is not None:
    print('PIN NOT SET (menu still shows Set PIN)')
sh('shell', 'input', 'keyevent', '4')
