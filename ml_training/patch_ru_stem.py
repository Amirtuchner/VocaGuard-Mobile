# -*- coding: utf-8 -*-
"""Make Russian keyword matching stem/word-prefix based (Russian inflects via
suffixes), while keeping Hebrew/English whole-word. Idempotent."""
p = "/opt/vocaguard/scam_detector.py"
s = open(p, encoding="utf-8").read()

if "is_ru = table is SCAM_KEYWORDS_RU" in s:
    print("already patched; skipping")
    raise SystemExit(0)

a_old = ("    for table in ALL_KEYWORD_TABLES:\n"
         "        for kw, weight in table:")
a_new = ("    for table in ALL_KEYWORD_TABLES:\n"
         "        is_ru = table is SCAM_KEYWORDS_RU\n"
         "        for kw, weight in table:")

b_old = ('            # Standard word-boundary match\n'
         '            found = f" {kw_s} " in normed_text')
b_new = ('            # Russian is heavily inflected (suffixes) -> match on word-\n'
         '            # prefix so a stem like "биржев" matches "биржевой". Hebrew/\n'
         '            # English stay whole-word to avoid false sub-word matches.\n'
         '            found = (f" {kw_s}" in normed_text) if is_ru else (f" {kw_s} " in normed_text)')

assert a_old in s, "anchor A not found"
assert b_old in s, "anchor B not found"
s = s.replace(a_old, a_new, 1).replace(b_old, b_new, 1)
open(p, "w", encoding="utf-8").write(s)
print("patched: Russian stem/prefix matching enabled")
