#!/usr/bin/env python
"""resolveError display check: detail loads with the fixture up, the fixture is
killed just before play, the resolver errors and the detail page must surface
the business guidance text (not the fixed generic copy)."""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'


def sh(*args, timeout=60):
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


def tap(p, wait=3):
    if p:
        sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    time.sleep(wait)


def main():
    # 1. VOD tab with the fixture UP (node restarted by the caller)
    sh('shell', 'am', 'force-stop', 'tv.own.owntv.mobile')
    time.sleep(2)
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(14)
    xml = None
    for _ in range(6):
        tap((866, 2226), 7)
        xml = dump()
        if find_contains(xml, '电影测试01') is not None:
            break
    if find_contains(xml, '电影测试01') is None:
        print('FAIL: grid never loaded (fixture up)')
        return 1
    print('step1 grid ok')

    # 2. detail loads
    tap(find_contains(xml, '电影测试01'), 6)
    xml = dump()
    ep = find_exact(xml, '第01集')
    if ep is None:
        print('FAIL: detail did not load')
        return 1
    print('step2 detail ok')

    # 3. kill the fixture, then play — resolver must fail and surface its message
    sh('shell', 'exec-out', 'ps')  # keepalive probe
    subprocess.run(['powershell', '-Command', 'Get-Process node | Stop-Process -Force'],
                   capture_output=True, text=True)
    time.sleep(1)
    tap(ep, 12)
    xml = dump()
    # the business guidance from the resolver: "解析失败，可尝试切换线路或换源"
    shown = find_contains(xml, '解析失败')
    shot = sh('shell', 'exec-out', 'screencap', '-p')
    with open(r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\shot_resolveerr.png', 'wb') as f:
        f.write(shot.stdout if isinstance(shot.stdout, bytes) else shot.stdout.encode())
    print(f'step3 resolveError visible: {shown is not None}')
    return 0 if shown is not None else 1


if __name__ == '__main__':
    raise SystemExit(main())
