#!/usr/bin/env python3
"""Turn build errors in a log into GitHub annotations (::error ...).

Annotations can be read through the public check-runs API, unlike raw logs.
usage: annotate.py <log> <repo root>
"""
import re
import sys

log, root = sys.argv[1], sys.argv[2].rstrip("/") + "/"
text = open(log, errors="replace").read()
out = []


def esc(s):
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def rel(path):
    path = path.replace("file://", "")
    return path[len(root):] if path.startswith(root) else path


# Kotlin: "e: file:///…/X.kt:12:5 message"
for m in re.finditer(r"^e: (file://)?(/\S+?\.kts?):(\d+):(\d+) (.+)$", text, re.M):
    out.append(f"::error file={rel(m.group(2))},line={m.group(3)},col={m.group(4)}::{esc(m.group(5))}")
# Swift / clang: "/…/X.swift:12:5: error: message"
for m in re.finditer(r"^\s*(/\S+?\.(?:swift|m|h)):(\d+):(\d+): error: (.+)$", text, re.M):
    out.append(f"::error file={rel(m.group(1))},line={m.group(2)},col={m.group(3)}::{esc(m.group(4))}")
# Gradle: "* What went wrong:" block
for m in re.finditer(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\Z)", text, re.S):
    out.append(f"::error title=Gradle::{esc(m.group(1).strip()[:3000])}")
# Tests: "FAILED" lines with the assertion that follows
for m in re.finditer(r"^(\S+ > \S+.*FAILED)\n((?:\s{4}.*\n){1,6})", text, re.M):
    out.append(f"::error title=Test::{esc((m.group(1) + chr(10) + m.group(2))[:2000])}")
# Asset catalogs (actool) and other tools: "…/X.xcassets: error: …", "/…: error: …" anywhere in a line
for m in re.finditer(r"^\s*(\S*\.(?:xcassets|svg|plist|ttf)\S*: (?:error|warning): .+)$", text, re.M):
    out.append(f"::error title=Assets::{esc(m.group(1)[:1000])}")
# xcodebuild / ld without a file position
for m in re.finditer(r"^\s*(ld: .+|error: .+|xcodebuild: error: .+)$", text, re.M):
    out.append(f"::error title=Xcode::{esc(m.group(1)[:1000])}")
if not out:
    tail = "\n".join(text.strip().splitlines()[-25:])
    out.append(f"::error title=Log tail::{esc(tail[-3500:])}")
seen = set()
for line in out:
    if line not in seen:
        seen.add(line)
        print(line)
    if len(seen) >= 45:
        break
