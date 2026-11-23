#!/usr/bin/env python3
"""The app's slip-reading recipe, on the CLI (ocr-probe.yml): a Hebrew pass for the words and a digits-only English pass
for the numbers (Hebrew models turn digits into letters), merged line by line by position.

Usage: slip_merge.py <heb.tsv> <digits.tsv>  -> prints the merged text
"""
import re, sys


def words(tsv):
    out = []
    for line in open(tsv, encoding="utf-8").read().splitlines()[1:]:
        f = line.split("\t")
        if len(f) < 12 or f[0] != "5" or not f[11].strip():
            continue
        x, y, w, h, conf = int(f[6]), int(f[7]), int(f[8]), int(f[9]), float(f[10])
        out.append({"key": (f[2], f[3], f[4]), "x": x, "y": y, "w": w, "h": h, "conf": conf, "text": f[11].strip()})
    return out


FINAL_INSIDE = re.compile("[ךםןףץ](?=[א-ת])")


def garbage(t):
    """A Hebrew-pass token that is really a misread number or noise."""
    if FINAL_INSIDE.search(t):
        return True
    if re.search(r"[A-Za-z0-9]", t) and not re.fullmatch(r"[א-ת'׳\"״\-]+", t):
        return True
    return False


heb, dig = words(sys.argv[1]), words(sys.argv[2])
lines = {}
for w in heb:
    lines.setdefault(w["key"], []).append(w)
used = set()
rows = []
for key, ws in lines.items():
    top = min(w["y"] for w in ws); bottom = max(w["y"] + w["h"] for w in ws)
    text = [w["text"] for w in sorted(ws, key=lambda w: -w["x"]) if not garbage(w["text"])]
    h = bottom - top
    nums = [d for i, d in enumerate(dig) if d["conf"] > 50 and top - 0.3 * h <= d["y"] + d["h"] / 2 <= bottom + 0.3 * h]
    for d in nums:
        used.add(id(d))
    rows.append((top, " ".join(text + [d["text"] for d in sorted(nums, key=lambda d: -d["x"])])))
for d in dig:
    if id(d) not in used and d["conf"] > 50:
        rows.append((d["y"], d["text"]))
for _, t in sorted(rows):
    if t.strip():
        print(t)
