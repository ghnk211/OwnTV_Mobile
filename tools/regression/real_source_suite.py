#!/usr/bin/env python
"""Full real-source suite on the fresh OwnTV_Test_35 emulator:
wizard setup (no PIN) -> import the local fixture config -> sanity grid ->
then the 6 random real endpoints, one at a time, recording banners.

The serial is emulator-5556 everywhere.
"""
import io
import re
import urllib.parse
import subprocess
import time

ADB = r'D:\android\sdk\platform-tools\adb.exe'
UI = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\ui.xml'
SHOT = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression'
SER = 'emulator-5556'


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
        b = re.search(r'bounds="([^"]*)"', chunk)
        if t and t.group(1) == needle and b:
            return centre(b.group(1))
    return None


def top_bar_config_button(xml):
    """VOD home top bar: the rightmost clickable Button above y=400 is the config entry
    (device-dependent layout — Pixel 7 puts it at (964,343), Pixel 6 at (981,258))."""
    btns = []
    for chunk in xml.split('<node'):
        cl = re.search(r'clickable="true"', chunk)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        cls = re.search(r'class="[^"]*Button"', chunk)
        if cl and b and cls:
            g = b.groups()
            c = ([(int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2])
            if c[1] < 400:
                btns.append(c)
    btns.sort(key=lambda p: -p[0])
    return btns[0] if btns else None


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


def type_into(xml, needle, text):
    """Tap the field whose exact text is `needle`, then type."""
    p = find_exact(xml, needle)
    if p is None:
        return False
    tap(p, 1.5)
    sh('shell', 'input', 'text', text)
    time.sleep(1)
    return True


def screenshot(name):
    out = sh('shell', 'exec-out', 'screencap', '-p')
    data = out.stdout if isinstance(out.stdout, bytes) else out.stdout.encode()
    with open(SHOT + '\\' + name, 'wb') as f:
        f.write(data)


def wizard():
    """First-run wizard: welcome -> disclaimer -> profile(name) -> playlist skip.

    Idempotent: if the main shell is already up, it returns immediately."""
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(12)
    xml = dump()
    if find_contains(xml, 'Start watching') is not None or find_contains(xml, 'Pick a site') is not None             or find_contains(xml, '本地CMS') is not None:
        return
    for _ in range(12):
        xml = dump()
        if find_exact(xml, 'Allow') is not None:
            d = find_exact(xml, "Don't allow") or find_exact(xml, "Don’t allow")
            tap(d, 2)
            continue
        if find_contains(xml, 'Add a playlist') is not None and find_exact(xml, 'Skip for now') is not None:
            tap(find_exact(xml, 'Skip for now'), 6)
            continue
        for title in ('Welcome to OwnTV', 'Before you start', 'Set up OwnTV'):
            if find_contains(xml, title) is None:
                continue
            for btn_text in ('Get Started', 'I Understand', 'New profile', 'Continue', 'Skip for now'):
                b = find_exact(xml, btn_text)
                if b is not None:
                    tap(b, 4)
                    break
            else:
                # the profile page's name field
                if find_exact(xml, 'e.g. Alex') is not None:
                    tap((400, 832), 1)
                    sh('shell', 'input', 'text', 'Tester')
                    time.sleep(1)
                    sh('shell', 'input', 'keyevent', '111')
                    time.sleep(1)
                    tap(find_exact(xml, 'Continue'), 4)
            break
    print('wizard done')


def vod_tab():
    xml = dump()
    # a previous run's snapshot can leave the state machine on the config page
    if find_contains(xml, 'Sources') is not None and find_exact(xml, 'Import') is not None:
        sh('shell', 'input', 'keyevent', '4')
        time.sleep(3)
        xml = dump()
    if find_contains(xml, '电影测试01') or find_contains(xml, '本地CMS') or find_contains(xml, 'Pick a site'):
        return True
    for _ in range(5):
        tap((866, 2226), 6)
        xml = dump()
        if find_contains(xml, '电影测试01') or find_contains(xml, '本地CMS') or find_contains(xml, 'Pick a site'):
            return True
    return False


CASES = [
    ('软件', 'http://47.96.82.41:5188/api.json'),
    ('欧歌免费', 'https://tv.nxog.top/m/111.php?ou=' + urllib.parse.quote('公众号欧歌app') + '&mz=index&jar=index&123&b=tv'),
    ('直播电视IPV4(负样本)', 'https://live.zbds.top/tv/iptv4.txt'),
    ('GG影视', 'https://raw.giteeusercontent.com/junge8888/little-second-brother-999/raw/master/aiwex.json'),
    ('装歌', 'https://tv.nxog.top/m/111.php'),
    ('新接口', 'https://www.iyouhun.com/tv/wex'),
]

BANNER_RE = re.compile(
    r'text="(已导入[^"]*|此配置已存在[^"]*|配置解析失败[^"]*|下载配置失败[^"]*|配置地址返回[^"]*|配置内容是网页[^"]*|地址为空[^"]*)"')


def find_edittext(xml):
    """The dialog's text field, by class — layout-independent."""
    best = None
    for chunk in xml.split('<node'):
        cls = re.search(r'class="([^"]*)"', chunk)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', chunk)
        if cls and 'EditText' in cls.group(1) and b:
            g = b.groups()
            c = ((int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2)
            if best is None or c[1] > best[1]:
                best = c  # the lowest EditText on the sheet is the input
    return best


def import_url(url, name, idx):
    # config page from VOD home
    cfg = top_bar_config_button(dump())
    tap(cfg, 3)
    xml = dump()
    if find_exact(xml, 'Import') is None:
        cfg = top_bar_config_button(dump())
        tap(cfg, 3)
        xml = dump()
        if find_exact(xml, 'Import') is None:
            return 'CONFIG_PAGE_FAIL'
    tap(find_exact(xml, 'Import'), 2.5)
    xml = dump()
    if find_exact(xml, 'Cancel') is None:
        return 'DIALOG_FAIL'
    field = find_edittext(dump())
    if field is None:
        return 'FIELD_LOST'
    tap(field, 1.5)
    sh('shell', 'input', 'text', url)
    time.sleep(1)
    xml = dump()
    ok_btn = find_exact(xml, 'Import')
    if ok_btn is None:
        return 'OK_LOST'
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
    # dismiss the dialog so the next case starts clean
    xml = dump()
    c = find_exact(xml, 'Cancel')
    if c is not None:
        tap(c, 2)
    return banner or 'NO_BANNER'


def main():
    wizard()
    # local fixture config first: sanity that the chain works end to end
    xml = dump()
    if not vod_tab():
        print('FAIL: VOD tab unreachable after wizard')
        return 1
    banner = import_url('http://10.0.2.2:8899/tvbox-local.json', '本地夹具', 0)
    print(f'[0] 本地夹具: {banner}')
    time.sleep(4)
    if not vod_tab():
        print('note: back to VOD after fixture import')
    xml = dump()
    grid = find_contains(xml, '电影测试01')
    print(f'[0] fixture grid visible: {grid is not None}')
    sh('shell', 'input', 'keyevent', '4')
    time.sleep(2)

    print('\n==== real endpoints ====')
    for idx, (name, url) in enumerate(CASES, 1):
        if not vod_tab():
            print(f'[{idx}] {name}: VOD tab unreachable')
            continue
        banner = import_url(url, name, idx)
        print(f'[{idx}] {name}: {banner}')
        time.sleep(3)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
