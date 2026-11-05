#!/usr/bin/env python3
"""Cheap static sanity checks for Kotlin sources (no compiler needed): bracket balance and
that every `import com.droidlink.*` points at a symbol declared in the project."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "app" / "src"
decl = re.compile(r"\b(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)")
files = list(root.rglob("*.kt"))
symbols = set()
for f in files:
    text = f.read_text()
    symbols.update(decl.findall(text))

def strip(src: str) -> str:
    """Remove strings, char literals and comments with a small state machine (order matters:
    '//' inside a string such as "droidlink://" is not a comment)."""
    out, i, n = [], 0, len(src)
    while i < n:
        two = src[i:i + 2]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif src[i] == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif src[i] == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif two == "//":
            while i < n and src[i] != "\n":
                i += 1
        elif two == "/*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(src[i])
            i += 1
    return "".join(out)

errors = 0
for f in files:
    text = f.read_text()
    code = strip(text)
    for o, c in ("()", "[]", "{}"):
        if code.count(o) != code.count(c):
            print(f"{f.relative_to(root)}: unbalanced {o}{c} ({code.count(o)} vs {code.count(c)})")
            errors += 1
    for m in re.finditer(r"^import (com\.droidlink\.[\w.]+)$", text, re.M):
        name = m.group(1).split(".")[-1]
        if name not in symbols and name != "R" and name != "BuildConfig":
            print(f"{f.relative_to(root)}: import {m.group(1)} not found")
            errors += 1
print(f"checked {len(files)} files, {errors} problem(s)")
sys.exit(1 if errors else 0)
