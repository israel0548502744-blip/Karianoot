#!/usr/bin/env python3
"""The automatic proofreader's second half: speak every piece with magpie-cli, transcribe it, flag the words it missed.

Usage: run.py <magpie-cli> <model.gguf> <pieces.jsonl> <asr dir> <out dir> <shard> <shards> <speaker,speaker,...>
Writes <out dir>/proof_<shard>.json: per piece and speaker what was heard and which reference words were not.

A word counts as missed when, after aligning reference and transcript word by word, it sits in a replaced or deleted
stretch whose letters are less than 60% alike. Stretches where the recognizer wrote digits are skipped (it writes
"3.40" for "שלוש ארבעים"). One voice mishearing a word is often the recognizer; every voice missing it is a lead.
"""
import difflib, json, os, re, subprocess, sys, time, unicodedata
import numpy as np
import soundfile as sf
from scipy.signal import resample_poly
from faster_whisper import WhisperModel

cli, gguf, pieces_path, asr_dir, out, shard, shards, speakers = sys.argv[1:9]
shard, shards = int(shard), int(shards)
speakers = [int(x) for x in speakers.split(",")]
pieces = [json.loads(l) for l in open(pieces_path, encoding="utf-8")]
mine = [p for i, p in enumerate(pieces) if i % shards == shard]
asr = WhisperModel(asr_dir, device="cpu", compute_type="int8")
os.makedirs(out, exist_ok=True)
FINALS = str.maketrans("ךםןףץ", "כמנפצ")


def words(s):
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if not unicodedata.category(c).startswith("M"))
    s = re.sub(r"[^א-ת0-9 ]+", " ", s.replace("-", " "))
    return [w.translate(FINALS) for w in s.split()]


def missed(ref, hyp):
    r, h = words(ref), words(hyp)
    bad = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=r, b=h, autojunk=False).get_opcodes():
        if op == "equal":
            continue
        hs = h[j1:j2]
        if any(re.search(r"\d", w) for w in hs):
            continue
        a, b = "".join(r[i1:i2]), "".join(hs)
        if op == "insert" or not a:
            continue
        sim = difflib.SequenceMatcher(a=a, b=b, autojunk=False).ratio()
        if sim < 0.6:
            bad.append({"ref": " ".join(r[i1:i2]), "heard": " ".join(hs), "sim": round(sim, 2)})
    return bad


rows = []
t_start = time.time()
for k, p in enumerate(mine):
    row = {k2: p[k2] for k2 in ("sentence", "piece", "source", "text", "spoken", "ipa")}
    row["takes"] = {}
    for spk in speakers:
        wav = os.path.join(out, f"s{p['sentence']}_{p['piece']}_spk{spk}.wav")
        r = subprocess.run([cli, "say", "--model", gguf, "--text", p["ipa"], "--lang", "he", "--speaker", str(spk),
                            "--seed", str(1000 + p["sentence"]), "--threads", str(os.cpu_count() or 4), "--output", wav],
                           capture_output=True, text=True, env=dict(os.environ, MAGPIE_PRIOR="0"))
        if r.returncode != 0 or not os.path.exists(wav):
            row["takes"][spk] = {"error": (r.stdout + r.stderr)[-300:]}
            continue
        a, sr = sf.read(wav, dtype="float32")
        if a.ndim > 1:
            a = a.mean(axis=1)
        segs, _ = asr.transcribe(resample_poly(a, 16000, sr).astype(np.float32), language="he", beam_size=5)
        heard = " ".join(x.text for x in segs).strip()
        row["takes"][spk] = {"heard": heard, "seconds": round(len(a) / sr, 2), "missed": missed(p["spoken"], heard)}
        os.remove(wav)
    rows.append(row)
    print(f"[{k + 1}/{len(mine)} {time.time() - t_start:.0f}s] " +
          " | ".join(f"{s}: {len(t.get('missed', []))}" for s, t in row["takes"].items()) + f" | {p['text']}", flush=True)
json.dump(rows, open(os.path.join(out, f"proof_{shard}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
