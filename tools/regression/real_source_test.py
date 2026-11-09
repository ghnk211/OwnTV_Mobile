#!/usr/bin/env python
"""Import a set of real TVbox endpoints from the collected list and verify the
improvement items against the ecosystem: import banner (held), parse result,
site count, grid content. One URL is an IPTV list on purpose (negative test)."""
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


def type_url(url):
    """The URL dialog's text field; keyboard shift means re-dump for the OK."""
    tap((540, 1971), 1.5)
    sh('shell', 'input', 'text', url)
    time.sleep(1)
    xml = dump()
    return find_exact(xml, 'Import')


CASES = [
    ('软件', 'http://47.96.82.41:5188/api.json'),
    ('欧歌免费', 'https://tv.nxog.top/m/111.php?ou=' + urllib.parse.quote('公众号欧歌app') + '&mz=index&jar=index&123&b=tv'),
    ('直播电视IPV4(负样本)', 'https://live.zbds.top/tv/iptv4.txt'),
    ('GG影视', 'https://raw.giteeusercontent.com/junge8888/little-second-brother-999/raw/master/aiwex.json'),
    ('装歌', 'https://tv.nxog.top/m/111.php'),
    ('新接口', 'https://www.iyouhun.com/tv/wex'),
]


def main():
    results = []
    sh('shell', 'am', 'force-stop', 'tv.own.owntv.mobile')
    time.sleep(2)
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(15)
    # reach the VOD home: its signature is the site picker / the empty hint
    xml = dump()
    on_vod = find_contains(xml, 'Pick a site') or find_contains(xml, '本地CMS') or find_contains(xml, 'No usable site')
    for _ in range(6):
        if on_vod:
            break
        tap((866, 2226), 6)
        xml = dump()
        on_vod = find_contains(xml, 'Pick a site') or find_contains(xml, '本地CMS') or find_contains(xml, 'No usable site')
    if not on_vod:
        print('FAIL: VOD home not reachable')
        return 1
    # config page button (top-right second icon)
    tap((964, 343), 3)
    xml = dump()
    if find_exact(xml, 'Import') is None:
        tap((964, 343), 3)
        xml = dump()
        if find_exact(xml, 'Import') is None:
            print('FAIL: config page not reachable')
            return 1

    for idx, (name, url) in enumerate(CASES, 1):
        imp = find_exact(dump(), 'Import')
        tap(imp, 2.5)
        xml = dump()
        if find_exact(xml, 'Cancel') is None:
            print(f'[{idx}] {name}: dialog did not open')
            results.append((name, 'DIALOG_FAIL', ''))
            continue
        ok_btn = type_url(url)
        if ok_btn is None:
            print(f'[{idx}] {name}: OK button lost after typing')
            results.append((name, 'TYPE_FAIL', ''))
            sh('shell', 'input', 'keyevent', '4')
            time.sleep(1.5)
            continue
        tap(ok_btn, 2)
        # import runs: URL fetch + parse; real endpoints take a while — poll the banner
        banner = None
        for _ in range(14):
            xml = dump()
            m = re.search(r'text="(已导入[^"]*|此配置已存在[^"]*|配置解析失败[^"]*|下载配置失败[^"]*|配置地址返回[^"]*|配置内容是网页[^"]*|配置内容是网页[^"]*)"', xml)
            if m:
                banner = m.group(1)
                break
            time.sleep(2)
        screenshot(f'shot_real_{idx}.png')
        # count sites listed ( Imported sources rows are site names, count source rows)
        xml = dump()
        import_rows = len(re.findall(r'text="[^"]*"', xml))
        spider_state = 'spider' in xml
        results.append((name, banner or 'NO_BANNER', f'rows={import_rows}'))
        print(f'[{idx}] {name}: banner={banner or "NO_BANNER"}')
        # close any leftover dialog
        if find_exact(xml, 'Cancel') is not None:
            tap(find_exact(xml, 'Cancel'), 2)
        time.sleep(2)

    print('\n==== summary ====')
    for name, banner, extra in results:
        print(f'{name:24} | {banner[:60]:60} | {extra}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
