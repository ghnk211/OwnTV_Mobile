import io

P = r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\real_source_v3.py'
s = io.open(P, encoding='utf-8').read()

old = '''    field = find_edittext(xml)
    if field is None:
        return 'FIELD_LOST'
    tap(field, 1.5)
    sh('shell', 'input', 'text', url)
    time.sleep(1)'''
new = '''    field = find_edittext(xml)
    if field is None:
        return 'FIELD_LOST'
    tap(field, 1.5)
    # the soft keyboard shifts the dialog: re-locate the field at its new position
    xml2 = dump()
    field2 = find_edittext(xml2)
    if field2 is not None and field2 != field:
        tap(field2, 1)
    sh('shell', 'input', 'text', url)
    time.sleep(1)'''
assert old in s, 'tap block'
s = s.replace(old, new)
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('retap fix installed')
