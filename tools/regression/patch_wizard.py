import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\real_source_suite.py'
s = io.open(P, encoding='utf-8').read()

start = s.find('def wizard():')
end = s.find('def vod_tab():')
assert start > 0 and end > start

new_wizard = '''def wizard():
    """First-run wizard: welcome -> disclaimer -> profile(name) -> playlist skip."""
    sh('shell', 'am', 'start', '-n', 'tv.own.owntv.mobile/tv.own.owntv.mobile.MainActivity')
    time.sleep(12)
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


'''

s = s[:start] + new_wizard + s[end:]
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('wizard rewritten')
