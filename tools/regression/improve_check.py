#!/usr/bin/env python
"""Improvement regression checks on device (fresh app state):
1. dual-write lock (unlock via PIN, re-lock via long-press, assert both key forms)
2. import-duplicate banner stays visible ~4s (screenshot at +1.5s)
3. aggregate search still returns results
Run after force-stop + fresh start on the VOD grid."""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
SHOT = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\shot_banner.png'
PIN = '1234'


def sh(*args, timeout=40):
    return subprocess.run([ADB, '-s', 'emulator-5554'] + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(5):
        sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
        if '<node' in out:
            io.open(UI, 'w', encoding='utf-8').write(out)
            return out
        time.sleep(1.5)
    return ''


def centre(b):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
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


def go_vod_grid():
    tap((866, 2226), 8)
    for _ in range(6):
        xml = dump()
        if find_contains(xml, '电影测试01') or find_contains(xml, '电影'):
            return xml
        tap((866, 2226), 6)
    return None


def main():
    ok = True

    # ---- 1. dual-write lock ----
    xml = go_vod_grid()
    if xml is None:
        print('FAIL: VOD grid not reachable')
        return 1
    chip = find_contains(xml, '电影')
    tap(chip, 3)
    xml = dump()
    if find_exact(xml, 'Enter PIN') is not None:
        tap((540, 1971), 1.5)
        sh('shell', 'input', 'text', PIN)
        time.sleep(1)
        xml = dump()
        tap(find_exact(xml, 'OK'), 5)
        xml = dump()
        chip = find_contains(xml, '电影')
    sh('shell', 'input', 'swipe', '114', '460', '114', '460', '1200')
    time.sleep(2.5)
    xml = dump()
    lock_item = find_exact(xml, 'Lock this category')
    if lock_item is None:
        # already locked? then it reads Unlock — means the session/lock state differs
        print('NOTE: menu shows no Lock item (maybe already dual-locked)')
        unlock_item = find_exact(xml, 'Unlock this category')
        if unlock_item is None:
            print('FAIL: no lock menu at all')
            return 1
        tap(unlock_item, 3)
        sh('shell', 'input', 'swipe', '114', '460', '114', '460', '1200')
        time.sleep(2.5)
        xml = dump()
        lock_item = find_exact(xml, 'Lock this category')
        tap(lock_item, 3)
    data = sh('shell', 'run-as', 'tv.own.owntv.mobile', 'sh', '-c',
              '"strings files/datastore/owntv_customizations.preferences_pb"').stdout
    has_type = 'tvbox:local_cms:1' in data
    has_alias = 'tvbox:local_cms:电影' in data
    print(f'check1 dual-write: typeId={has_type} alias={has_alias}')
    ok = ok and has_type and has_alias

    # ---- 2. duplicate-import banner visibility ----
    sh('shell', 'input', 'tap', '964', '343')  # config page button
    time.sleep(3)
    xml = dump()
    imp = find_exact(xml, 'Import')
    tap(imp, 2.5)
    xml = dump()
    field = find_exact(xml, 'Config URL')
    tap((540, 1971), 1.5)
    sh('shell', 'input', 'text', 'http://10.0.2.2:8899/tvbox-local.json')
    time.sleep(1)
    xml = dump()
    tap(find_exact(xml, 'Import'), 2)
    time.sleep(1.2)
    sh('shell', 'exec-out', 'screencap', '-p', '>' + SHOT.replace('\\', '/'))
    xml = dump()
    banner = find_contains(xml, '此配置已存在') or find_contains(xml, '配置已存在')
    print(f'check2 banner visible: {banner is not None}')
    ok = ok and banner is not None
    time.sleep(5)
    sh('shell', 'input', 'keyevent', '4')
    time.sleep(1.5)
    sh('shell', 'input', 'keyevent', '4')
    time.sleep(2)

    # ---- 3. aggregate search still works ----
    tap((866, 2226), 8)
    xml = dump()
    if find_contains(xml, '电影') is None:
        tap((866, 2226), 6)
        xml = dump()
    sh('shell', 'input', 'tap', '840', '343')
    time.sleep(2.5)
    tap((540, 1971), 1.5)
    sh('shell', 'input', 'text', '01')
    time.sleep(1)
    xml = dump()
    tap(find_exact(xml, 'Search'), 9)
    xml = dump()
    names = re.findall(r'text="([^"]*测试[^"]*)"', xml)
    print(f'check3 search results: {len(names)} rows')
    ok = ok and len(names) >= 3

    print('ALL PASS' if ok else 'SOME CHECKS FAILED')
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
