#!/usr/bin/env python
"""Relock-on-leave end-to-end test (plan §10 item 10).

Flow: VOD tab -> unlock the sealed category (PIN) -> play episode 1 -> confirm the
five-second progress timer is writing -> press HOME -> assert the writes stop and the
TVbox guard logged the seal.
"""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
PIN = '1234'


def sh(*args, timeout=30):
    return subprocess.run([ADB, '-s', 'emulator-5554'] + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(4):
        sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
        if '<node' in out:
            io.open(UI, 'w', encoding='utf-8').write(out)
            return out
        time.sleep(1.2)
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


def tap(p):
    if p:
        sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    return p


def tap_text(xml, needle, wait=2.5):
    p = find_exact(xml, needle) or find_contains(xml, needle)
    tap(p)
    time.sleep(wait)
    return p


def progress_stamp():
    out = sh('shell', 'run-as', 'tv.own.owntv.mobile', 'cat', 'files/tvbox/progress.json').stdout
    m = re.search(r'"updatedAt":(\d+)', out)
    return int(m.group(1)) if m else 0


def main():
    # 1. go to the VOD tab; wait for the poster grid
    tap_text(dump(), 'On demand')
    for _ in range(8):
        xml = dump()
        if find_contains(xml, '电影测试01'):
            break
        tap((866, 2226))
        time.sleep(3)
    else:
        print('FAIL: VOD grid never appeared')
        return 1
    print('step1 VOD grid ok')

    # 2. unlock the sealed category (the lock icon prefix renders when sealed)
    xml = dump()
    chip = find_contains(xml, '电影')
    tap(chip)
    time.sleep(2.5)
    xml = dump()
    if find_exact(xml, 'Enter PIN') is None:
        # maybe already unlocked this session; either way continue
        print('step2 no PIN sheet (already unlocked in session?)')
    else:
        p = find_exact(xml, 'Enter PIN')
        # tap the FIELD (full-width row under the sheet title), type, confirm
        xml = dump()
        field = None
        for chunk in xml.split('<node'):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
            t = re.search(r'text="([^"]*)"', chunk)
            if b and t and t.group(1) == PIN:
                field = centre(b.group(1))
        if field is None:
            # sheet title at ~1971 pre-keyboard; the field row is the wide node above OK
            field = (540, 1971)
        tap(field)
        time.sleep(1.5)
        sh('shell', 'input', 'text', PIN)
        time.sleep(1)
        xml = dump()
        ok = find_exact(xml, 'OK')
        if ok is None:
            print('FAIL: OK not found after typing PIN')
            return 1
        tap(ok)
        time.sleep(4)
        print('step2 PIN entered')

    # 3. open the first poster -> detail -> tap 第01集
    xml = dump()
    poster = find_contains(xml, '电影测试01')
    if poster is None:
        print('FAIL: poster gone after unlock')
        return 1
    tap(poster)
    time.sleep(4)
    xml = dump()
    ep = find_exact(xml, '第01集')
    if ep is None:
        print('FAIL: detail page did not show 第01集')
        return 1
    tap(ep)
    time.sleep(6)

    # 4. confirm the progress timer is writing (two samples differ)
    t1 = progress_stamp()
    time.sleep(5.5)
    t2 = progress_stamp()
    playing = t1 != t2 and t2 > 0
    print(f'step4 playing={playing} (t1={t1} t2={t2})')
    if not playing:
        print('FAIL: progress timer not writing — playback did not start')
        return 1

    # 5. HOME -> PiP/stop; the guard must stop the TVbox stream after the core relock
    sh('shell', 'input', 'keyevent', '3')
    time.sleep(6)
    t3 = progress_stamp()
    time.sleep(7)
    t4 = progress_stamp()
    stopped = t3 == t4
    print(f'step5 after HOME: writes_stopped={stopped} (t3={t3} t4={t4})')

    pid = sh('shell', 'pidof', 'tv.own.owntv.mobile').stdout.strip()
    log = sh('logcat', '-d', '--pid=' + pid, '-v', 'usec').stdout
    guard_lines = [l for l in log.splitlines() if 'TvboxGuard' in l and 'ctx=true' in l]
    print('guard ctx=true lines:', len(guard_lines))
    for l in guard_lines[-3:]:
        print('   ', l[-100:])

    if stopped and guard_lines:
        print('PASS: relock-on-leave stopped TVbox playback')
        return 0
    if stopped:
        print('PARTIAL: playback stopped but no guard seal log (check why)')
        return 1
    print('FAIL: TVbox playback continued after leaving the app')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
