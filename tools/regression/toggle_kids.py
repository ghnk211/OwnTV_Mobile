#!/usr/bin/env python
"""Toggle Kids mode off (Settings -> Sources & guide -> Kids mode row switch)."""
import io
import re
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'


def sh(*args, timeout=30):
    return subprocess.run([ADB, '-s', 'emulator-5554'] + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    for _ in range(4):
        sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        out = sh('shell', 'cat', '/sdcard/ui.xml').stdout
        if '<node' in out and 'text="Kids mode"' in out:
            io.open(UI, 'w', encoding='utf-8').write(out)
            return out
        time.sleep(1.2)
    return ''


def scroll_to_kids():
    for _ in range(12):
        xml = dump()
        if 'Kids mode' in xml:
            return True
        sh('shell', 'input', 'swipe', '540', '1800', '540', '400', '300')
        time.sleep(0.8)
    return False


def kids_row_bounds(xml):
    for chunk in xml.split('<node'):
        if 'text="Kids mode"' not in chunk:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        if b:
            return tuple(int(g) for g in b.groups())
    return None


def profiles():
    out = sh('shell', 'run-as', 'tv.own.owntv.mobile', 'sqlite3', 'databases/owntv.db',
             'SELECT id,name,isKids FROM profiles;').stdout
    return out.strip()


if not scroll_to_kids():
    print('FAIL: Kids mode row not found')
    raise SystemExit(1)
xml = dump()
b = kids_row_bounds(xml)
y = (b[1] + b[3]) // 2
# the row's trailing switch sits at x ~930-990
sh('shell', 'input', 'tap', '960', str(y))
time.sleep(2.5)
print('profiles after toggle:')
print(profiles())
