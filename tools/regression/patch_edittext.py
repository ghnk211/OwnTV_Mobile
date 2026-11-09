import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\real_source_suite.py'
s = io.open(P, encoding='utf-8').read()

helper = '''def find_edittext(xml):
    """The dialog's text field, by class — layout-independent."""
    best = None
    for chunk in xml.split('<node'):
        cls = re.search(r'class="([^"]*)"', chunk)
        b = re.search(r'bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"', chunk)
        if cls and 'EditText' in cls.group(1) and b:
            g = b.groups()
            c = ((int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2)
            if best is None or c[1] > best[1]:
                best = c  # the lowest EditText on the sheet is the input
    return best


def import_url(url, name, idx):'''
old_fn = '''def import_url(url, name, idx):'''
assert old_fn in s
s = s.replace(old_fn, helper, 1)

old_body = '''    tap((540, 1971), 1.5)
    sh('shell', 'input', 'text', url)
    time.sleep(1)
    xml = dump()
    ok_btn = find_exact(xml, 'Import')
    if ok_btn is None:
        return 'OK_LOST'
    tap(ok_btn, 2)'''
new_body = '''    field = find_edittext(dump())
    if field is None:
        return 'FIELD_LOST'
    tap(field, 1.5)
    sh('shell', 'input', 'text', url)
    time.sleep(1)
    xml = dump()
    ok_btn = find_exact(xml, 'Import')
    if ok_btn is None:
        return 'OK_LOST'
    tap(ok_btn, 2)'''
assert old_body in s, 'body'
s = s.replace(old_body, new_body)
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('edittext helper installed')
