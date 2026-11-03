#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Per-language detection check for the server keyword engine (detect_scam).
Small CURATED set of natural scam/legit phrases per language (not a large corpus)
— characterizes multilingual keyword coverage honestly. Run on the server."""
import importlib.util, sys

spec = importlib.util.spec_from_file_location("scam_detector", "/opt/vocaguard/scam_detector.py")
sd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sd)
detect_scam = sd.detect_scam

# (text, is_scam_expected)
SETS = {
  "English": [
    ("This is the IRS, there is a warrant for your arrest, pay immediately with gift cards", True),
    ("Your bank account was compromised, give me the verification code from the SMS", True),
    ("Congratulations, you won a prize, call back within 24 hours to claim it", True),
    ("Microsoft technical support, your computer is infected, allow remote access now", True),
    ("Social security suspended due to suspicious activity, confirm your details to reactivate", True),
    ("Hi, this is the dental clinic, your appointment is confirmed for tomorrow morning", False),
    ("This is the building manager about the maintenance work next week", False),
  ],
  "Hebrew": [
    ("שלום, מדובר מביטוח לאומי, קיים נגדך צו עיכוב יציאה מהארץ ועליך לשלם מיד", True),
    ("מהבנק הפועלים, זוהתה פעילות חשודה בחשבונך, מסור לי את קוד האימות מההודעה", True),
    ("זכית בפרס גדול, חייג חזרה תוך עשרים וארבע שעות כדי לממש אותו", True),
    ("זהו צו מעצר נגדך, עליך להעביר תשלום דחוף אחרת תיעצר היום", True),
    ("שירות התמיכה הטכני, המחשב שלך נגוע בווירוס, אשר גישה מרחוק מיד", True),
    ("הוגשה נגדך תביעה משפטית וכתב אישום, שלם עכשיו כדי להימנע ממעצר", True),
    ("שלום, זו המרפאה, התור שלך נקבע למחר בבוקר בשעה תשע", False),
    ("מדבר מנהל הבניין בנוגע לעבודות התחזוקה בשבוע הבא", False),
  ],
  "Russian": [
    ("Это служба безопасности банка, обнаружена подозрительная операция, назовите код из смс", True),
    ("На ваше имя оформлен кредит, срочно переведите деньги на безопасный счет", True),
    ("Вы выиграли приз, перезвоните в течение суток чтобы получить", True),
    ("Техническая поддержка, ваш компьютер заражен, разрешите удаленный доступ", True),
    ("Полиция, против вас возбуждено дело, оплатите штраф немедленно", True),
    ("Здравствуйте, это из поликлиники, ваша запись подтверждена на завтра", False),
    ("Звоню из управляющей компании по поводу ремонта на следующей неделе", False),
  ],
  "Arabic": [
    ("هذا اتصال من البنك، تم رصد نشاط مشبوه على حسابك، زودنا برمز التحقق", True),
    ("لقد ربحت جائزة كبيرة، اتصل خلال أربع وعشرين ساعة للمطالبة بها", True),
    ("هذه مذكرة اعتقال بحقك، يجب دفع المبلغ فورا وإلا سيتم اعتقالك", True),
    ("الدعم الفني، جهازك مصاب بفيروس، اسمح بالوصول عن بعد الآن", True),
    ("مرحبا، هذه العيادة، موعدك مؤكد غدا في الصباح", False),
    ("أتصل من إدارة المبنى بخصوص أعمال الصيانة الأسبوع القادم", False),
  ],
}

print("Per-language detection (server keyword engine, curated set)")
print("=" * 60)
for lang, rows in SETS.items():
    sc_tot = sum(1 for _, e in rows if e)
    lg_tot = sum(1 for _, e in rows if not e)
    sc_hit = 0; fp = 0
    misses = []
    for text, exp in rows:
        try:
            pred = bool(detect_scam(text)[0])
        except Exception:
            pred = False
        if exp and pred: sc_hit += 1
        elif exp and not pred: misses.append(text[:40])
        elif (not exp) and pred: fp += 1
    rec = sc_hit / sc_tot * 100 if sc_tot else 0
    print(f"\n{lang}:")
    print(f"  scam recall:        {sc_hit}/{sc_tot} = {rec:.0f}%")
    print(f"  legit false-alarms: {fp}/{lg_tot}")
    for m in misses:
        print(f"    missed: {m}")
