#!/usr/bin/env python3
"""
Finds a Kotlin mistake that crashes at run time: a property declared after a class's init block
but used while that init block runs (directly, or through the functions it calls). Kotlin sets
properties in the order they are written, so such a property is still null (or 0) then.

Usage: python3 tools/check_init_order.py app/src/main/java/...   (exit 1 when something is found)
"""
import os
import re
import sys

PROP = re.compile(r'^    (?:private |protected |internal |override |lateinit )*(?:val|var) ([A-Za-z_][A-Za-z0-9_]*)\b(?!.*\bget\(\))[^\n]*=', re.M)
FUN = re.compile(r'^    (?:private |protected |internal |override |open )*fun (?:<[^>]*> )?([A-Za-z_][A-Za-z0-9_]*)\s*\(', re.M)
INIT = re.compile(r'^    init \{', re.M)


def block(src, start):
    """the text from the first { at or after start to its matching }"""
    i = src.index('{', start)
    depth = 0
    for j in range(i, len(src)):
        c = src[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return src[i:]


DEFERRED = re.compile(r'(setOn\w+Listener\s*\{|addTextChangedListener\(|\.post\s*\{|postDelayed\(\{|postOnAnimation\s*\{|\bon[A-Z]\w*\s*=\s*\{|object\s*:\s*[\w.]+(\([^)]*\))?\s*\{)')


def drop_deferred(text):
    """takes out code that runs later (click listeners, posted work, callbacks), not during init"""
    out, i = [], 0
    for m in DEFERRED.finditer(text):
        if m.start() < i:
            continue
        out.append(text[i:m.start()])
        j = text.index('{', m.start()) if '{' in text[m.start():m.end() + 1] or True else m.end()
        try:
            b = block(text, m.start())
            i = text.index('{', m.start()) + len(b)
        except ValueError:
            i = m.end()
    out.append(text[i:])
    return ''.join(out)


def strip(text):
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    text = re.sub(r'//[^\n]*', '', text)
    return drop_deferred(text)


CLASS = re.compile(r'^(?:[a-z]+ )*(?:class|object) ([A-Za-z_][A-Za-z0-9_]*)', re.M)


def body_start(src, at):
    """the { that opens a class body: the first one outside the constructor's parentheses"""
    depth = 0
    for j in range(at, len(src)):
        c = src[j]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
        elif c == '{' and depth == 0:
            return j
        elif c == '\n' and depth == 0 and src[j + 1:j + 2] not in (' ', ')', ':') and j > at + 1:
            # a class without a body
            nxt = src[j + 1:j + 40]
            if not nxt.startswith(('{', ' ', ')')):
                return -1
    return -1


def check(path):
    whole = open(path, encoding='utf-8').read()
    problems = []
    for c in CLASS.finditer(whole):
        b = body_start(whole, c.end())
        if b < 0:
            continue
        src = block(whole, b)
        problems += check_class(path, whole, src, b)
    return problems


def check_class(path, whole, src, base):
    problems = []
    for m in INIT.finditer(src):
        init_at = m.start()
        body = strip(block(src, m.end() - 1))
        funs = {f.group(1): f.start() for f in FUN.finditer(src)}
        # functions reached from the init block (a few calls deep)
        reached, todo, texts = set(), [body], [body]
        for _ in range(4):
            nxt = []
            for t in todo:
                for name, at in funs.items():
                    if name not in reached and re.search(r'\b' + name + r'\s*\(', t):
                        reached.add(name)
                        bt = strip(block(src, at))
                        nxt.append(bt)
                        texts.append(bt)
            todo = nxt
        used = '\n'.join(texts)
        for p in PROP.finditer(src, init_at):
            name = p.group(1)
            if re.search(r'\b' + name + r'\b', used):
                line = whole.count('\n', 0, base + p.start()) + 1
                problems.append(f'{path}:{line}: "{name}" is declared after the init block at line '
                                f'{whole.count(chr(10), 0, base + init_at) + 1} but used while it runs')
        break
    return problems


def main():
    found = []
    for root in sys.argv[1:]:
        for dirpath, _, files in os.walk(root):
            for f in files:
                if f.endswith('.kt'):
                    found += check(os.path.join(dirpath, f))
    for p in found:
        print(p)
    sys.exit(1 if found else 0)


if __name__ == '__main__':
    main()
