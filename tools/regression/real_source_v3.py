#!/usr/bin/env python
"""v3: each real URL imports in a CLEAN app state (wipe files/tvbox + force-stop
before each), success/failure judged from LOGCAT (config parse line), banner
text captured when present. UI interaction: only the import dialog."""
import io
import re
import subprocess
import time
import urllib.parse

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
SHOT = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression'
SER = 'emulator-5554'


def sh(*args, timeout=90):
    return subprocess.run([ADB, '-s', SER] + list(args), capture_output=True, text=True, timeout=timeout)


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
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        if t and t.group(1) == needle and b:
            g = b.groups()
            return ((int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2)
        if t and t.group(1) == needle and not b:
            continue  # truncated fragment; keep scanning other occurrences
    return None


def find_edittext(xml):
    best = None
    for chunk in xml.split('<node'):
        cls = re.search(r'class="([^"]*)"', chunk)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        if cls and 'EditText' in cls.group(1) and b:
            g = b.groups()
            c = ((int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2)
            if best is None or c[1] > best[1]:
                best = c
    return best


def tap(p, wait=2.5):
    if p:
        sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    time.sleep(wait)


def logcat_tvbox():
    pid = sh('shell', 'pidof', 'tv.own.owntv.mobile').stdout.strip().split()[0]
    out = sh('logcat', '-d', f'--pid={pid}', '-v', 'time', timeout=60).stdout
    return [l for l in out.splitlines() if 'Tvbox' in l]


def top_bar_config(xml):
    best = None
    for chunk in xml.split('<node'):
        cl = 'clickable="true"' in chunk
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        cls = re.search(r'class="[^"]*Button"', chunk)
        if cl and b and cls:
            g = b.groups()
            c = ((int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2)
            if c[1] < 400 and (best is None or c[0] > best[0]):
                best = c
    return best


CASES = [
    ('欧歌免费', 'https://tv.nxog.top/m/111.php?ou=' + urllib.parse.quote('公众号欧歌app') + '&mz=index&jar=index&123&b=tv'),
    ('GG影视', 'https://raw.giteeusercontent.com/junge8888/little-second-brother-999/raw/master/aiwex.json'),
]


def one_case(idx, name, url):
    sh('shell', 'am', 'force-stop', 'tv.own.owntv.mobile')
    time.sleep(2)
    sh('shell', 'run-as', 'tv.own.owntv.mobile', 'rm', '-rf', 'files/tvbox')
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(14)
    # the wiped app re-asks for notifications — dismiss it first
    xml = dump()
    deny = find_exact(xml, 'Don’t allow') or find_exact(xml, "Don't allow")
    if deny is not None:
        tap(deny, 3)
    # VOD tab -> config page
    tap((866, 2226), 8)
    cfg = None
    for _ in range(4):
        cfg = top_bar_config(dump())
        if cfg and find_exact(dump(), 'Import') is not None:
            break
        tap(cfg, 3) if cfg else None
    xml = dump()
    if find_exact(xml, 'Import') is None:
        return 'CONFIG_PAGE_FAIL'
    tap(find_exact(xml, 'Import'), 2.5)
    xml = dump()
    if find_exact(xml, 'Cancel') is None:
        return 'DIALOG_FAIL'
    field = find_edittext(xml)
    if field is None:
        return 'FIELD_LOST'
    tap(field, 1.5)
    # the soft keyboard shifts the dialog: re-locate the field at its new position
    xml2 = dump()
    field2 = find_edittext(xml2)
    if field2 is not None and field2 != field:
        tap(field2, 1)
    sh('shell', 'input', 'text', url)
    time.sleep(1)
    xml = dump()
    ok_btn = find_exact(xml, 'Import')
    if ok_btn is None:
        return 'OK_LOST'
    tap(ok_btn, 2)
    # poll for banner AND give the parse/jar time to log
    banner = None
    for _ in range(14):
        xml = dump()
        m = re.search(r'text="(已导入[^"]*|此配置已存在[^"]*|配置解析失败[^"]*|下载配置失败[^"]*|配置地址返回[^"]*|配置内容是网页[^"]*|地址为空[^"]*|无法读取[^"]*)"', xml)
        if m:
            banner = m.group(1)
            break
        time.sleep(2)
    screenshot(f'shot_real_{idx}.png')
    logs = logcat_tvbox()
    parsed = [l for l in logs if '配置解析完成' in l]
    restored = [l for l in logs if '已从缓存恢复配置' in l or '配置已刷新' in l]
    errors = [l for l in logs if '配置刷新失败' in l or '解析失败' in l]
    jar = [l for l in logs if 'spider jar 已缓存' in l]
    verdict = 'IMPORT_OK' if parsed else ('IMPORT_FAIL' if errors else 'NO_PARSE_LOG')
    return f'{verdict} | banner={banner or "-"} | sites={parsed[-1][-50:] if parsed else "-"} | jar={len(jar)}'


def screenshot(name):
    out = sh('shell', 'exec-out', 'screencap', '-p')
    data = out.stdout if isinstance(out.stdout, bytes) else out.stdout.encode()
    with open(SHOT + '\\' + name, 'wb') as f:
        f.write(data)


def main():
    print('==== real endpoints, clean state per case ====')
    for idx, (name, url) in enumerate(CASES, 2):
        r = one_case(idx, name, url)
        print(f'[{idx}] {name}: {r}')
        time.sleep(3)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
