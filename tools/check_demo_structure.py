"""检查 Demo 生产代码中文 KDoc 和文件规模（Python 3.10+，仅标准库）。

python tools/check_demo_structure.py
范围仅 app 的 main/maps/amapNavi/baiduNavi/debug Kotlin 源码；不扫描 SDK、测试或生成文件。
这是轻量词法辅助，不是 Kotlin AST：主构造参数由类 KDoc 覆盖，显式 constructor、
普通/扩展/override/局部方法及对象声明单独检查。复杂模板和语法仍需编译及人工核对。
文件限额防止重新形成巨型宿主；不能靠压行证明职责拆分或注释质量。
"""
from __future__ import annotations

import bisect
import re
from pathlib import Path

SOURCE_SETS = ('main', 'maps', 'amapNavi', 'baiduNavi', 'debug')
MAX_LINES = 400
CHINESE = re.compile(r'[\u3400-\u9fff]')
DECLARATION = re.compile(r'\b(class|interface|object|constructor|fun)\b')
MODIFIERS = re.compile(r'\b(?:public|private|protected|internal|data|sealed|enum|annotation|'
                       r'companion|open|abstract|final|override|inline|infix|operator|tailrec|'
                       r'suspend|external|expect|actual|value)\b')
ANNOTATION = re.compile(r'@[\w.:]+\s*(?:\([^)]*\))?')


def mask_source(source: str) -> tuple[str, list[tuple[int, int, str]]]:
    """按原始位置屏蔽字符串和注释，保留换行及独立 KDoc 的位置。"""
    masked = list(source)
    docs = []
    index = 0
    while index < len(source):
        start = index
        if source.startswith('//', index):
            end = source.find('\n', index)
            index = len(source) if end < 0 else end
        elif source.startswith('/*', index):
            depth = 1
            index += 2
            while index < len(source) and depth:
                if source.startswith('/*', index):
                    depth += 1
                    index += 2
                elif source.startswith('*/', index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            if source.startswith('/**', start) and depth == 0:
                docs.append((start, index, source[start:index]))
        elif source.startswith('"""', index):
            end = source.find('"""', index + 3)
            index = len(source) if end < 0 else end + 3
        elif source[index] in ('"', "'"):
            quote = source[index]
            index += 1
            while index < len(source):
                if source[index] == '\\':
                    index += 2
                elif source[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
        else:
            index += 1
            continue
        for position in range(start, min(index, len(source))):
            if source[position] not in '\r\n':
                masked[position] = ' '
    return ''.join(masked), docs


def documented_prefix(prefix: str, kind: str) -> bool:
    """允许注解及声明修饰符；禁止跨越方法体借用上一条 KDoc。"""
    prefix = ANNOTATION.sub('', prefix)
    prefix = MODIFIERS.sub('', prefix).strip()
    if kind == 'interface' and prefix == 'fun':
        return True
    if not prefix:
        return True
    # 显式主构造声明同样由紧邻的类说明覆盖，次构造方法仍须独立说明。
    return kind == 'constructor' and bool(re.fullmatch(r'class\s+\w+', prefix))


def check_source(source: str, max_lines: int = MAX_LINES) -> tuple[int, list[tuple[int, str]]]:
    """返回声明数量和行号/规则，不输出源码、坐标或凭据。"""
    masked, docs = mask_source(source)
    ends = [end for _, end, _ in docs]
    findings = []
    declarations = 0
    for match in DECLARATION.finditer(masked):
        kind = match[1]
        if kind == 'class' and masked[:match.start()].rstrip().endswith('::'):
            continue  # Foo::class 不是类声明。
        if kind == 'fun' and re.match(r'\s+interface\b', masked[match.end():]):
            continue  # fun interface 只在 interface 处计数。
        declarations += 1
        line = source.count('\n', 0, match.start()) + 1
        doc_index = bisect.bisect_right(ends, match.start()) - 1
        if doc_index < 0:
            findings.append((line, f'missing-chinese-kdoc:{kind}'))
            continue
        _, end, text = docs[doc_index]
        if not CHINESE.search(text) or not documented_prefix(masked[end:match.start()], kind):
            findings.append((line, f'missing-chinese-kdoc:{kind}'))
    line_count = source.count('\n') + (0 if not source or source.endswith('\n') else 1)
    if line_count > max_lines:
        findings.append((1, f'file-too-large:{line_count}>{max_lines}'))
    return declarations, findings


def production_files(root: Path) -> list[Path]:
    """只枚举声明的 Demo 生产 source set，排除测试及生成目录。"""
    files = []
    for source_set in SOURCE_SETS:
        folder = root / 'app' / 'src' / source_set / 'kotlin'
        files.extend(folder.rglob('*.kt'))
    return sorted(files)


def main() -> int:
    """检查完整生产目录；缺失输入或任何未覆盖声明都以非零退出。"""
    root = Path(__file__).resolve().parent.parent
    files = production_files(root)
    if not files:
        print('Demo structure check failed: no production Kotlin files.')
        return 2
    total = 0
    errors = 0
    for path in files:
        count, findings = check_source(path.read_text(encoding='utf-8-sig'))
        total += count
        for line, rule in findings:
            print(f'{path.relative_to(root).as_posix()}:{line}: {rule}')
            errors += 1
    if errors:
        print(f'Demo structure check failed: {errors} findings.')
        return 1
    print(f'Demo structure check passed: {len(files)} files, {total} declarations, max {MAX_LINES} lines/file.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
