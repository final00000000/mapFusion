"""Kotlin 注释/字面量与成员定位辅助，仅标准库，不是完整 Kotlin AST。

模板字符串按完整字面量屏蔽，不分析其中表达式；复杂语法仍需要编译和人工核对。
成员目标包括主构造属性、类型体/顶层属性及枚举项，排除普通函数和初始化块的局部变量。
"""
from __future__ import annotations

import re
from dataclasses import dataclass

TOKEN = re.compile(r'`[^`]*`|\w+|===|!==|==|!=|>=|<=|\+\+|--|\?\.|\?:|!!|->|::|\.\.<|\.\.|\+=|-=|\*=|/=|%=|[^\s]')
IDENTIFIER = re.compile(r'(?:[^\W\d]\w*|`[^`]+`)\Z')


@dataclass(frozen=True)
class MemberTarget:
    """一个需注释的源码目标；仅存位置、种类和名字，不存初始化值或凭据。"""
    position: int
    kind: str
    name: str


def _comment_end(source: str, start: int) -> int:
    """跳过行注释或可嵌套块注释，未闭合时返回文件末尾。"""
    if source.startswith('//', start):
        end = source.find('\n', start)
        return len(source) if end < 0 else end
    depth = 1
    index = start + 2
    while index < len(source) and depth:
        if source.startswith('/*', index):
            depth += 1
            index += 2
        elif source.startswith('*/', index):
            depth -= 1
            index += 2
        else:
            index += 1
    return index


def _template_end(source: str, start: int) -> int:
    """跳过 ${...}，处理表达式中的嵌套花括号、引号和注释。"""
    depth = 1
    index = start
    while index < len(source) and depth:
        if source.startswith(('//', '/*'), index):
            index = _comment_end(source, index)
        elif source[index] in ('"', "'"):
            index = _quoted_end(source, index)
        else:
            if source[index] == '{':
                depth += 1
            elif source[index] == '}':
                depth -= 1
            index += 1
    return index


def _quoted_end(source: str, start: int) -> int:
    """跳过字符、普通/原始字符串；模板内引号不会误结束外层字面量。"""
    raw = source.startswith('"""', start)
    quote = '"""' if raw else source[start]
    index = start + len(quote)
    while index < len(source):
        if not raw and source[index] == '\\':
            index += 2
        elif quote != "'" and source.startswith('${', index):
            index = _template_end(source, index + 2)
        elif source.startswith(quote, index):
            return index + len(quote)
        else:
            index += 1
    return index


def source_parts(source: str):
    """按顺序提供代码 token、完整字面量和注释的原始区间，保留初始化值以便比对。"""
    index = 0
    while index < len(source):
        if source[index].isspace():
            index += 1
            continue
        start = index
        if source.startswith(('//', '/*'), index):
            index = _comment_end(source, index)
            yield 'comment', start, index
        elif source[index] in ('"', "'"):
            index = _quoted_end(source, index)
            yield 'literal', start, index
        else:
            match = TOKEN.match(source, index)
            if match is None:
                raise ValueError('Unrecognized source token')
            index = match.end()
            yield 'code', start, index


def code_tokens(source: str) -> tuple[str, ...]:
    """保留字面量/运算符，仅排除注释和 token 间空白；用于注释改动辅助比对，非语义证明。"""
    return tuple(source[start:end] for kind, start, end in source_parts(source) if kind != 'comment')


def mask_source(source: str) -> tuple[str, list[tuple[int, int, str]]]:
    """屏蔽注释及整个字面量，保持位置/换行，另外记录闭合 KDoc。"""
    masked = list(source)
    docs = []
    for kind, start, end in source_parts(source):
        if kind == 'code':
            continue
        if kind == 'comment' and source.startswith('/**', start) and source[start:end].endswith('*/'):
            docs.append((start, end, source[start:end]))
        for position in range(start, min(end, len(source))):
            if source[position] not in '\r\n':
                masked[position] = ' '
    return ''.join(masked), docs


def _pairs(tokens: list[tuple[str, int]]) -> dict[int, int]:
    """配对圆/方/花括号；无效语法交给编译器，不以此定位器代替语法验收。"""
    stack = []
    result = {}
    closing = {')': '(', ']': '[', '}': '{'}
    for index, (text, _) in enumerate(tokens):
        if text in ('(', '[', '{'):
            stack.append((text, index))
        elif text in closing and stack and stack[-1][0] == closing[text]:
            _, opening = stack.pop()
            result[opening] = index
            result[index] = opening
    return result


def _type_regions(tokens: list[tuple[str, int]], pairs: dict[int, int]):
    """定位类型体和主构造圆括号，不把无类型体的 data class/对象绑定到后续声明。"""
    bodies = {}
    constructors = set()
    stops = {'class', 'interface', 'object', 'fun', 'val', 'var', 'data', 'enum', 'companion',
             'package', 'import', 'typealias', ';', '=', '}'}
    for index, (kind, _) in enumerate(tokens):
        if kind not in ('class', 'interface', 'object') or (index and tokens[index - 1][0] == '::'):
            continue
        enum = kind == 'class' and index > 0 and tokens[index - 1][0] == 'enum'
        cursor = index + 1
        supertype = False
        primary_seen = False
        while cursor < len(tokens):
            text = tokens[cursor][0]
            if text == '{':
                bodies[cursor] = 'enum' if enum else 'type'
                break
            if text in stops:
                break
            if text == '<':
                depth = 1
                cursor += 1
                while cursor < len(tokens) and depth:
                    depth += (tokens[cursor][0] == '<') - (tokens[cursor][0] == '>')
                    cursor += 1
                continue
            if text == ':':
                supertype = True
            if text == '(':
                end = pairs.get(cursor)
                if end is None:
                    break
                if kind == 'class' and not supertype and not primary_seen:
                    constructors.add(cursor)
                    primary_seen = True
                cursor = end + 1
                continue
            cursor += 1
    return bodies, constructors


def _skip_annotation(tokens: list[tuple[str, int]], pairs: dict[int, int], index: int) -> int:
    """跳过枚举项注解的名称及参数，防止把注解参数中的逗号识别成新枚举项。"""
    index += 1
    if index < len(tokens):
        index += 1
    while index + 1 < len(tokens) and tokens[index][0] in ('.', ':'):
        index += 2
    if index < len(tokens) and tokens[index][0] == '(':
        index = pairs.get(index, index) + 1
    return index


def member_targets(masked: str) -> list[MemberTarget]:
    """定位声明属性和枚举项，排除函数、访问器、属性初始化表达式中的普通局部 val/var。"""
    tokens = [(match[0], match.start()) for match in TOKEN.finditer(masked)]
    pairs = _pairs(tokens)
    bodies, constructors = _type_regions(tokens, pairs)
    targets = []
    for opening, kind in list(bodies.items()):
        if kind != 'enum':
            continue
        cursor = opening + 1
        end = pairs.get(opening, len(tokens))
        expect_entry = True
        while cursor < end:
            text, position = tokens[cursor]
            if text == ';':
                break
            if text == '@' and expect_entry:
                cursor = _skip_annotation(tokens, pairs, cursor)
                continue
            if text in ('(', '[', '{'):
                if text == '{':
                    bodies[cursor] = 'type'  # 枚举项自己的匿名类型体也可声明属性。
                cursor = pairs.get(cursor, cursor) + 1
                continue
            if text == ',':
                expect_entry = True
            elif expect_entry and IDENTIFIER.fullmatch(text):
                targets.append(MemberTarget(position, 'enum-entry', text.strip('`')))
                expect_entry = False
            cursor += 1
    braces = []
    parentheses = []
    squares = []
    paren_brace_depth = {}
    square_brace_depth = {}
    for index, (text, position) in enumerate(tokens):
        if text in ('val', 'var'):
            constructor_property = bool(parentheses and parentheses[-1] in constructors and
                                        len(braces) == paren_brace_depth.get(parentheses[-1]))
            type_body = bool(braces and braces[-1] in bodies)
            expression_inside_body = bool(
                (parentheses and paren_brace_depth[parentheses[-1]] >= len(braces)) or
                (squares and square_brace_depth[squares[-1]] >= len(braces)))
            member_property = (type_body and not expression_inside_body) or (not braces and not parentheses and not squares)
            if (constructor_property or member_property) and index + 1 < len(tokens):
                name = tokens[index + 1][0]
                if IDENTIFIER.fullmatch(name):
                    targets.append(MemberTarget(position, 'property', name.strip('`')))
        if text == '{':
            braces.append(index)
        elif text == '}' and braces:
            braces.pop()
        elif text == '(':
            paren_brace_depth[index] = len(braces)
            parentheses.append(index)
        elif text == ')' and parentheses:
            parentheses.pop()
        elif text == '[':
            square_brace_depth[index] = len(braces)
            squares.append(index)
        elif text == ']' and squares:
            squares.pop()
    return sorted(targets, key=lambda target: target.position)
