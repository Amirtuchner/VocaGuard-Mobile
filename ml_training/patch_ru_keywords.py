# -*- coding: utf-8 -*-
"""Add Israel-targeting Russian scam phrases (mined from 7 real calls, 2026-10-29)
to SCAM_KEYWORDS_RU in the deployed scam_detector.py. Idempotent + UTF-8 safe."""
p = "/opt/vocaguard/scam_detector.py"
s = open(p, encoding="utf-8").read()
MARKER = "# --- Israel-targeting RU scams, mined from real calls 2026-10-29 ---"
if MARKER in s:
    print("already patched; skipping")
    raise SystemExit(0)

anchor = "SCAM_KEYWORDS_RU = ["
i = s.index(anchor) + len(anchor)

block = "\n    " + MARKER + "\n" + "\n".join([
    # HIGH-SIGNAL (weight=2) — specific scam phrases from the real calls
    '    ("вернуть деньги", 2), ("вернуть вам деньги", 2),',
    '    ("возврат подоходного налога", 2), ("возврат налога", 2),',
    '    ("накладная", 2), ("код получения", 2),',
    '    ("скину ссылку", 2), ("скину вам ссылку", 2), ("перейдите по ссылке", 2),',
    '    ("введите пароль", 2), ("поменяем пароль", 2),',
    '    ("доступ к аккаунт", 2), ("арендовала ваш аккаунт", 2), ("арендовали ваш аккаунт", 2),',
    '    ("криптоэволюционн", 2), ("государственный проект", 2), ("бонус в размере", 2),',
    '    ("акций и валют", 2), ("торгуете на бирже", 2),',
    # MEDIUM-SIGNAL (weight=1) — partners that push combos over threshold
    '    ("торговый счет", 1), ("торговый счёт", 1), ("биржев", 1), ("биржа", 1),',
    '    ("вас обманули", 1), ("регуляц", 1),',
    '    ("посылк", 1), ("почтовое отделение", 1), ("сортировочн", 1),',
    '    ("личный кабинет", 1), ("шаблонный", 1), ("пассивный доход", 1),',
    '    ("подписать договор", 1), ("комиссионн", 1), ("выплат", 1),',
    '    ("есть телеграм", 1), ("переверните", 1), ("продиктуйте", 1), ("продиктует", 1),',
])
s2 = s[:i] + block + s[i:]
open(p, "w", encoding="utf-8").write(s2)
print("patched: added Israel-RU scam phrases to SCAM_KEYWORDS_RU")
