#!/usr/bin/env python3
"""The app's slip-reading recipe on the CLI (ocr-probe.yml), as SlipReader.kt does it with TessBaseAPI.setRectangle:
a Hebrew pass for the words; every word it could not read as Hebrew (a number turned into letters, a lone letter for
"1" / "X") is read again alone, digits only, from its own box.

Usage: slip_merge.py <image> <heb.tsv> <tessdata dir>  -> prints the text
"""
import re, subprocess, sys, tempfile
from PIL import Image

img, tsv, tessdata = sys.argv[1:4]
FINAL_INSIDE = re.compile("[ךםןףץ](?=[א-ת])")


def needs_digits(t):
    if FINAL_INSIDE.search(t) or (len(t) == 1 and t.isalpha()):
        return True
    return bool(re.search(r"[A-Za-z0-9]", t)) and not re.fullmatch(r"[א-ת'׳\"״\-]+", t)


im = Image.open(img)
lines = {}
for row in open(tsv, encoding="utf-8").read().splitlines()[1:]:
    f = row.split("\t")
    if len(f) < 12 or f[0] != "5" or not f[11].strip():
        continue
    x, y, w, h = map(int, f[6:10])
    t = f[11].strip()
    if needs_digits(t):
        pad = max(4, h // 4)
        with tempfile.NamedTemporaryFile(suffix=".png") as crop:
            im.crop((max(0, x - pad), max(0, y - pad), x + w + pad, y + h + pad)).save(crop.name)
            out = subprocess.run(["tesseract", crop.name, "-", "--tessdata-dir", tessdata, "-l", "eng", "--psm", "7",
                                  "-c", "tessedit_char_whitelist=0123456789.,X"], capture_output=True, text=True).stdout.strip()
        t = out if re.fullmatch(r"[0-9.,X]+", out or "") else ""
    if t:
        lines.setdefault((f[2], f[3], f[4]), []).append((x, y, t))
for key, ws in sorted(lines.items(), key=lambda kv: min(w[1] for w in kv[1])):
    print(" ".join(t for _, _, t in sorted(ws, key=lambda w: -w[0])))
