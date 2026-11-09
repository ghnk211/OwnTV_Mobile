#!/usr/bin/env python
"""Real-source import tests v2: after each import, recover from any jar-init
webview hijack via force-stop, re-navigate, continue with the next URL."""
import io
import re
import subprocess
import time
import urllib.parse

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
SHOT_DIR = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression'


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


def tap(p, wait=2.5):
    if p:
        sh('shell', 'input', 'tap', str(p[0]), str(p[1]))
    time.sleep(wait)


def screenshot(name):
    out = sh('shell', 'exec-out', 'screencap', '-p')
    data = out.stdout if isinstance(out.stdout, bytes) else out.stdout.encode()
    with open(SHOT_DIR + '\\' + name, 'wb') as f:
        f.write(data)


def hijacked(xml):
    """jar Init 的验证 WebView / 或其它非 App 文本页面。"""
    return find_exact(xml, 'Import') is None and find_contains(xml, 'Imported sources') is None \
        and find_contains(xml, 'Imported') is None


def fresh_app_to_config():
    sh('shell', 'am', 'force-stop', 'tv.own.owntv.mobile')
    time.sleep(2)
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(15)
    # the app may resume on the jar webview — back twice then settle
    for _ in range(3):
        xml = dump()
        if not hijacked(xml):
            break
        sh('shell', 'input', 'keyevent', '4')
        time.sleep(2)
    xml = dump()
    if hijacked(xml):
        return None
    on_vod = find_contains(xml, 'Pick a site') or find_contains(xml, '本地CMS') or find_contains(xml, 'No usable site')
    for _ in range(6):
        if on_vod:
            break
        tap((866, 2226), 6)
        xml = dump()
        on_vod = find_contains(xml, 'Pick a site') or find_contains(xml, '本地CMS') or find_contains(xml, 'No usable site')
    if not on_vod:
        return None
    tap((964, 343), 3)
    xml = dump()
    if find_exact(xml, 'Import') is None:
        tap((964, 343), 3)
        xml = dump()
    return xml if find_exact(xml, 'Import') is not None else None


CASES = [
    ('欧歌免费', 'https://tv.nxog.top/m/111.php?ou=' + urllib.parse.quote('公众号欧歌app') + '&mz=index&jar=index&123&b=tv'),
    ('直播电视IPV4(负样本)', 'https://live.zbds.top/tv/iptv4.txt'),
    ('GG影视', 'https://raw.giteeusercontent.com/junge8888/little-second-brother-999/raw/master/aiwex.json'),
    ('装歌', 'https://tv.nxog.top/m/111.php'),
    ('新接口', 'https://www.iyouhun.com/tv/wex'),
]

BANNER_RE = re.compile(r'text="(已导入[^"]*|此配置已存在[^"]*|配置解析失败[^"]*|下载配置失败[^"]*|配置地址返回[^"]*|配置内容是网页[^"]*|地址为空[^"]*)"')


def main():
    results = []
    for idx, (name, url) in enumerate(CASES, 2):
        xml = fresh_app_to_config()
        if xml is None:
            print(f'[{idx}] {name}: could not reach config page (webview hijack?)')
            results.append((name, 'NAV_FAIL', ''))
            continue
        imp = find_exact(xml, 'Import')
        tap(imp, 2.5)
        xml = dump()
        if find_exact(xml, 'Cancel') is None:
            print(f'[{idx}] {name}: dialog did not open')
            results.append((name, 'DIALOG_FAIL', ''))
            continue
        tap((540, 1971), 1.5)
        sh('shell', 'input', 'text', url)
        time.sleep(1)
        xml = dump()
        ok_btn = find_exact(xml, 'Import')
        tap(ok_btn, 2)
        banner = None
        for _ in range(16):
            xml = dump()
            m = BANNER_RE.search(xml)
            if m:
                banner = m.group(1)
                break
            time.sleep(2)
        screenshot(f'shot_real_{idx}.png')
        results.append((name, banner or 'NO_BANNER', ''))
        print(f'[{idx}] {name}: banner={banner or "NO_BANNER"}')
        time.sleep(3)

    print('\n==== summary ====')
    for name, banner, extra in results:
        print(f'{name:26} | {banner[:70]}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
