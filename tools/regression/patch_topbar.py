import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\real_source_suite.py'
s = io.open(P, encoding='utf-8').read()

helper = '''def top_bar_config_button(xml):
    """VOD home top bar: the rightmost clickable Button above y=400 is the config entry
    (device-dependent layout — Pixel 7 puts it at (964,343), Pixel 6 at (981,258))."""
    btns = []
    for chunk in xml.split('<node'):
        cl = re.search(r'clickable="true"', chunk)
        b = re.search(r'bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"', chunk)
        cls = re.search(r'class="[^"]*Button"', chunk)
        if cl and b and cls:
            c = centre(b.group(1))
            if c[1] < 400:
                btns.append(c)
    btns.sort(key=lambda p: -p[0])
    return btns[0] if btns else None


def find_contains(xml, needle):'''
old_fn = '''def find_contains(xml, needle):'''
assert old_fn in s
s = s.replace(old_fn, helper, 1)

# import_url: dynamic config button
old_imp = '''    sh('shell', 'input', 'tap', '964', '343')
    time.sleep(3)
    xml = dump()
    if find_exact(xml, 'Import') is None:
        sh('shell', 'input', 'tap', '964', '343')
        time.sleep(3)
        xml = dump()
        if find_exact(xml, 'Import') is None:
            return 'CONFIG_PAGE_FAIL'
    tap(find_exact(xml, 'Import'), 2.5)'''
new_imp = '''    cfg = top_bar_config_button(dump())
    tap(cfg, 3)
    xml = dump()
    if find_exact(xml, 'Import') is None:
        cfg = top_bar_config_button(dump())
        tap(cfg, 3)
        xml = dump()
        if find_exact(xml, 'Import') is None:
            return 'CONFIG_PAGE_FAIL'
    tap(find_exact(xml, 'Import'), 2.5)'''
assert old_imp in s, 'import_url block'
s = s.replace(old_imp, new_imp)

# main(): fixture import uses import_url now
old_main = """    banner = import_url('http://10.0.2.2:8899/tvbox-local.json', '本地夹具', 0)"""
assert old_main in s

# vod_tab/search taps: search icon also dynamic
old_srch = """    sh('shell', 'input', 'tap', '840', '343')
    time.sleep(2.5)"""
if old_srch in s:
    s = s.replace(old_srch, """    btns = top_bar_config_button(dump())
    # second rightmost = search; simpler: tap left of config by ~120px
    if btns:
        sh('shell', 'input', 'tap', str(btns[0][0] - 120), str(btns[0][1]))
    time.sleep(2.5)""")
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('suite patched for dynamic top bar')
