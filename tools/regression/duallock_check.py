#!/usr/bin/env python
"""Unlock the sealed category via the PIN sheet, then re-lock via the long-press
menu; assert the Core DataStore ends up holding BOTH key forms (dual-write)."""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
PIN = '1234'


def sh(*args, timeout=40):
    return subprocess.run([ADB, '-s', 'emulator-5554'] + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(4):
        sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
        if '<node' in out:
            io.open(UI, 'w', encoding='utf-8').write(out)
            return out
        time.sleep(1.5)
    return ''


def centre(bounds):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
    return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)


def find_exact(xml, needle):
    for chunk in xml.split('<node'):
        t = re.search(r'text="([^"]*)"', chunk)
        b = re.search(r'bounds="([^"]*)"', chunk)
        if t and t.group(1) == needle and b:
            return centre(b.group(1))
    return None


def find_contains(xml, needle):
    for chunk in xml.split('<node'):
        t = re.search(r'text="([^"]*)"', chunk)
        b = re.search(r'bounds="([^"]*)"', chunk)
        if t and needle in t.group(1) and b:
            return centre(b.group(1))
    return None


def tap(p, wait=2.5):
    if p:
        sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    time.sleep(wait)


def datastore():
    return sh('shell', 'run-as', 'tv.own.owntv.mobile', 'sh', '-c',
              '"strings files/datastore/owntv_customizations.preferences_pb"').stdout


def main():
    # go to the VOD tab
    tap((866, 2226), 8)
    xml = dump()
    chip = find_contains(xml, '电影')
    if chip is None:
        print('FAIL: locked chip not on screen')
        return 1
    tap(chip, 3)
    xml = dump()
    if find_exact(xml, 'Enter PIN') is None:
        print('FAIL: PIN sheet did not open for the locked category')
        return 1
    tap((540, 1971), 1.5)
    sh('shell', 'input', 'text', PIN)
    time.sleep(1)
    xml = dump()
    ok = find_exact(xml, 'OK')
    tap(ok, 5)

    # unlocked in session; now re-lock via long-press (dual write)
    xml = dump()
    chip = find_contains(xml, '电影')
    if chip is None:
        chip = (114, 460)
    sh('shell', 'input', 'swipe', '114', '460', '114', '460', '1200')
    time.sleep(2.5)
    xml = dump()
    lock_item = find_exact(xml, 'Lock this category')
    if lock_item is None:
        print('FAIL: lock menu did not open')
        return 1
    tap(lock_item, 3)

    data = datastore()
    has_type = 'tvbox:local_cms:1' in data
    has_alias = 'tvbox:local_cms:电影' in data
    print(f'datastore typeId form: {has_type}, typeName form: {has_alias}')
    print('PASS: dual-write verified' if (has_type and has_alias) else 'FAIL: dual-write missing a form')
    return 0 if (has_type and has_alias) else 1


if __name__ == '__main__':
    raise SystemExit(main())
