"""Scores every <voice>__<variant>__<piece>.wav in a folder:
- ר realization: a phoneme recognizer (wav2vec2 xlsr-53 espeak, Apache-2.0) aligned to the expected IPA; what came
  out where ר (ʁ) is expected: trill/tap (r, ɾ = the "Argentinian" r), uvular (ʁ, ʀ), fricative (χ, x, ɣ, h), approximant (ɹ).
- CER with the ivrit-ai Hebrew whisper; TakeCheck (the app's rule for a broken take).

Usage: python score.py <wav_dir> <plan.json> <scores_out.json>
"""
import glob, json, math, os, sys, unicodedata
import numpy as np, soundfile as sf, torch
from scipy.signal import resample_poly

TRILL, UVU, FRIC, APPROX = "rɾɽ", "ʁʀ", "χxɣhħɦʕ", "ɹɻ"
RISH = TRILL + UVU + FRIC + APPROX
VOW = "aeiouɛɔəɪʊæɑɐøyɨʌœɯɒɜ"


def klass(tok):
    c = tok[0]
    return "trill" if c in TRILL else "uvular" if c in UVU else "fric" if c in FRIC else "approx" if c in APPROX else None


def expected(ipa):
    return [("ɡ" if c == "g" else c) for c in ipa if unicodedata.category(c).startswith("L") and c not in "ˈˌː"]


def cost(e, t):
    c = "ɡ" if t[0] == "g" else t[0]
    if e == "ʁ":
        return 0.1 if c in TRILL + UVU + APPROX else 0.4 if c in FRIC else 1.0
    if e == c: return 0.0
    if e == "χ" and c in "xħh": return 0.0
    if e in VOW and c in VOW: return 0.5
    return 1.0


def align(exp, rec, gap=0.7):
    """Edit-distance alignment; returns for each expected symbol the index of the recognized token (or -1)."""
    n, m = len(exp), len(rec)
    d = np.zeros((n + 1, m + 1)); d[:, 0] = np.arange(n + 1) * gap; d[0, :] = np.arange(m + 1) * gap
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i, j] = min(d[i - 1, j] + gap, d[i, j - 1] + gap, d[i - 1, j - 1] + cost(exp[i - 1], rec[j - 1]))
    out, i, j = [-1] * n, n, m
    while i > 0 and j > 0:
        if abs(d[i, j] - (d[i - 1, j - 1] + cost(exp[i - 1], rec[j - 1]))) < 1e-9: out[i - 1] = j - 1; i -= 1; j -= 1
        elif abs(d[i, j] - (d[i - 1, j] + gap)) < 1e-9: i -= 1
        else: j -= 1
    return out


def take_check(w, sr, ipa_len):
    """Port of TakeCheck.measure (Kotlin)."""
    h = sr // 100; n = len(w) // h
    if n < 2 or ipa_len <= 0: return 18.5, 0.0
    e = np.sqrt((w[: n * h].astype(np.float64).reshape(n, h) ** 2).mean(1)); peak = e.max()
    if peak < 1e-6: return 0.0, 0.0
    loud = 20 * np.log10(e / peak + 1e-12) > -38
    idx = np.where(loud)[0]; first, last = idx[0], idx[-1]
    longest = run = 0
    for i in range(first, last + 1):
        if not loud[i]:
            run += 1; longest = max(longest, run)
        else: run = 0
    return ipa_len / ((last - first + 1) / 100.0), longest / 100.0


def norm(s):
    s = "".join(c for c in unicodedata.normalize("NFD", s) if "א" <= c <= "ת" or c == " ")
    for a, b in zip("ךםןףץ", "כמנפצ"): s = s.replace(a, b)
    return " ".join(s.split())


def cer(ref, hyp):
    r, h = norm(ref).replace(" ", ""), norm(hyp).replace(" ", "")
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1])); prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def main():
    wav_dir, plan, out_path = sys.argv[1], json.load(open(sys.argv[2], encoding="utf8")), sys.argv[3]
    from transformers import Wav2Vec2ForCTC
    from huggingface_hub import hf_hub_download
    from faster_whisper import WhisperModel
    PR = "facebook/wav2vec2-xlsr-53-espeak-cv-ft"
    vocab = json.load(open(hf_hub_download(PR, "vocab.json"), encoding="utf8"))
    id2tok = {v: k for k, v in vocab.items()}
    model = Wav2Vec2ForCTC.from_pretrained(PR).eval()
    blank = vocab.get("<pad>", 0)
    ids = lambda chars: [i for t, i in vocab.items() if not t.startswith("<") and t and t[0] in chars]
    trill_ids, rish_ids = ids(TRILL), ids(RISH)
    print("r-like tokens:", sorted(t for t in vocab if t and t[0] in RISH), flush=True)
    asr = WhisperModel("ivrit-ai/whisper-large-v3-turbo-ct2", device="cpu", compute_type="int8")
    rows = []
    for path in sorted(glob.glob(os.path.join(wav_dir, "*__*__*.wav"))):
        name = os.path.basename(path)[:-4]
        voice, variant, pid = name.split("__")
        piece = plan["pieces"][pid]
        w, sr = sf.read(path, dtype="float32")
        if w.ndim > 1: w = w.mean(1)
        g = math.gcd(sr, 16000); a16 = resample_poly(w, 16000 // g, sr // g).astype(np.float32)
        ipa_said = plan["variants"].get(variant, {}).get(pid, piece["ipa"])
        rate, pause = take_check(w, sr, len(ipa_said))
        row = {"file": name, "voice": voice, "variant": variant, "piece": pid, "dur": round(len(w) / sr, 2),
               "rate": round(rate, 1), "pause": round(pause, 2), "broken": bool(rate < 11 or rate > 30 or pause >= 1.2)}
        x = torch.from_numpy((a16 - a16.mean()) / math.sqrt(a16.var() + 1e-7))[None]
        with torch.no_grad(): p = model(x).logits[0].softmax(-1).numpy()
        best = p.argmax(-1); toks = []   # (token, first frame, last frame)
        for f, i in enumerate(best):
            if f > 0 and i == best[f - 1]:
                if toks and i != blank: toks[-1][2] = f
                continue
            if i != blank and not id2tok[i].startswith("<") and id2tok[i].strip(): toks.append([id2tok[i], f, f])
        rec = [t[0] for t in toks]; exp = expected(piece["ipa"]); al = align(exp, rec)
        r = {"n": 0, "trill": 0, "uvular": 0, "fric": 0, "approx": 0, "missing": 0}; soft = []; said = []
        for e, j in zip(exp, al):
            if e != "ʁ": continue
            r["n"] += 1
            k = klass(rec[j]) if j >= 0 else None
            r[k or "missing"] += 1; said.append(rec[j] if j >= 0 else "-")
            if k:
                seg = p[toks[j][1]: toks[j][2] + 1]
                soft.append(float(seg[:, trill_ids].sum() / max(1e-9, seg[:, rish_ids].sum())))
        r["p_trill"] = round(float(np.mean(soft)), 3) if soft else None
        row["r"] = r; row["r_said"] = " ".join(said); row["phones"] = " ".join(rec)
        segs, _ = asr.transcribe(a16, language="he", beam_size=5)
        row["hyp"] = " ".join(s.text for s in segs).strip(); row["cer"] = round(cer(piece["text"], row["hyp"]), 3)
        rows.append(row)
        print(f"{name}: {row['dur']}s rate={row['rate']} pause={row['pause']} broken={row['broken']} cer={row['cer']} r={r} [{row['r_said']}] | {row['hyp']}", flush=True)
    json.dump(rows, open(out_path, "w", encoding="utf8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
