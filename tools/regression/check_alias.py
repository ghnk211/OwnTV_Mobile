import re

data = open(r'D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\tools\regression\pb.txt',
            encoding='utf-8', errors='ignore').read()
print('tvbox key count in od dump:', len(re.findall(r't\s+v\s+b\s+o\s+x', data)))
# 电影 UTF-8 = e7 94 b5 e5 bd b1 → od octal: 347 224 265 345 275 261
alias_ok = bool(re.search(r'347\s+224\s+265\s+345\s+275\s+261', data))
print('alias (电影 utf-8 bytes) present:', alias_ok)
# also the two-digit ascii form of "1": the typeId key ends with 1
type_ok = bool(re.search(r't\s+v\s+b\s+o\s+x\s+:.*?1', data))
print('typeId key present:', type_ok)
