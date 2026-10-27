#!/usr/bin/env python3
"""Measure the live server scam engine (scam_detector.detect_scam) against the
14k labeled dataset. Binary: label 0 = legitimate, 1-13 = scam. Reports
precision/recall/F1/false-positive-rate + per-scam-type recall. Run on the server."""
import csv, importlib.util, sys, time

spec = importlib.util.spec_from_file_location("scam_detector", "/opt/vocaguard/scam_detector.py")
sd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sd)
detect_scam = sd.detect_scam

CLASS_NAMES = ["Legitimate","IRS","Tech Support","Bank Fraud","Lottery","Social Security",
               "Robocall","Phishing","Insurance","Investment Scam","Donation Fraud",
               "Romance/Pig-Butchering","Delivery/Package","Job/Recruitment"]

csv_path = sys.argv[1]
TP = FP = TN = FN = 0
tot = {}; caught = {}
n = 0; t0 = time.time()
with open(csv_path, newline="", encoding="utf-8") as f:
    rd = csv.reader(f)
    next(rd, None)
    for row in rd:
        if len(row) < 2:
            continue
        text = row[0]
        try:
            label = int(row[1])
        except ValueError:
            continue
        if label < 0 or label > 13:
            continue
        actual = (label != 0)
        try:
            pred = bool(detect_scam(text)[0])
        except Exception:
            pred = False
        if actual and pred: TP += 1
        elif actual and not pred: FN += 1
        elif (not actual) and pred: FP += 1
        else: TN += 1
        if actual:
            tot[label] = tot.get(label, 0) + 1
            if pred:
                caught[label] = caught.get(label, 0) + 1
        n += 1
el = time.time() - t0
prec = TP / (TP + FP) if TP + FP else 0
rec = TP / (TP + FN) if TP + FN else 0
f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
fpr = FP / (FP + TN) if FP + TN else 0
acc = (TP + TN) / n if n else 0
print("=" * 56)
print("VocaGuard server scam engine — text accuracy (synthetic set)")
print("=" * 56)
print(f"Samples: {n}  (scam={TP+FN}, legitimate={TN+FP})")
print(f"Confusion: TP={TP} FP={FP} TN={TN} FN={FN}")
print(f"Accuracy : {acc*100:.2f}%")
print(f"Precision: {prec*100:.2f}%   (of flagged calls, share that were real scams)")
print(f"Recall   : {rec*100:.2f}%   (of scam calls, share we caught)")
print(f"F1       : {f1*100:.2f}%")
print(f"False-positive rate: {fpr*100:.2f}%   (legit calls wrongly flagged)")
print(f"Scoring speed: {el/n*1000:.3f} ms/sample ({el:.1f}s for {n} samples)")
print("\nPer-scam-type recall:")
for t in sorted(tot):
    c = caught.get(t, 0)
    nm = CLASS_NAMES[t] if t < len(CLASS_NAMES) else str(t)
    print(f"  {t:>2} {nm:<24} {c:>4}/{tot[t]:<4} = {c/tot[t]*100:5.1f}%")
