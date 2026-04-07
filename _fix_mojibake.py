from pathlib import Path
import re

root = Path(r'd:\Develop\plaza\plaza-order\src\main\java')
files = list(root.rglob('*.java'))

bad_re = re.compile(r'[�锟鏢鐖鍏鎬鍥璁鍞鏈鎷寮闂閿璇缁鍒澶浠绔骞鏃]+')

def suspicious_score(text: str) -> int:
    return len(re.findall(r'[�锟鏢鐖鍏鎬鍥璁鍞鏈鎷寮闂閿璇缁鍒澶浠绔骞鏃]', text))

def chinese_score(text: str) -> int:
    return len(re.findall(r'[\u4e00-\u9fff]', text))

changed = []
for file in files:
    original = file.read_text(encoding='utf-8')
    lines = original.splitlines(keepends=True)
    new_lines = []
    file_changed = False
    for line in lines:
        if not bad_re.search(line):
            new_lines.append(line)
            continue
        try:
            candidate = line.encode('gb18030', errors='ignore').decode('utf-8', errors='ignore')
        except Exception:
            new_lines.append(line)
            continue
        orig_bad = suspicious_score(line)
        cand_bad = suspicious_score(candidate)
        orig_zh = chinese_score(line)
        cand_zh = chinese_score(candidate)
        if candidate and (cand_bad < orig_bad) and (cand_zh >= orig_zh):
            new_lines.append(candidate)
            if candidate != line:
                file_changed = True
        else:
            new_lines.append(line)
    if file_changed:
        file.write_text(''.join(new_lines), encoding='utf-8', newline='')
        changed.append(str(file))

print(f'changed={len(changed)}')
for path in changed:
    print(path)
