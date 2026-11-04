"""Objective quality check: synthesize with several settings, transcribe with a Hebrew ASR
model, and print the character error rate (lower = clearer speech).

Usage: python tools/quality.py <models_dir> <out_dir>
"""
import os, sys, json, unicodedata
import numpy as np, soundfile as sf
from scipy.signal import resample_poly
from blue_onnx import load_text_to_speech, load_voice_style, limit_peak
from renikud_onnx import G2P
from faster_whisper import WhisperModel

models, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
SENT = [
    "ערב טוב לכולם, היום יש לנו שלושה משחקים מעולים.",
    "ריאל מדריד שיחקה מצוין, אבל בסוף המשחק הכול השתנה.",
    "יחס גבוה, סיכון נמוך, וזה בדיוק מה שאנחנו אוהבים.",
    "תישארו עד הסוף, ואל תשכחו להצטרף לקבוצה שלנו.",
]
g2p = G2P(os.path.join(models, "renikud/model_int8.onnx"))
asr = WhisperModel("ivrit-ai/whisper-large-v3-turbo-ct2", device="cpu", compute_type="int8")

def norm(s):
    s = "".join(c for c in unicodedata.normalize("NFD", s) if "א" <= c <= "ת" or c == " ")
    return " ".join(s.split())

def cer(ref, hyp):
    r, h = norm(ref).replace(" ", ""), norm(hyp).replace(" ", "")
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))

VARIANTS = [
    ("v3_Roi_cfg4_steps5 (app now)", "blue_v3", "Roi", 5, 4.0),
    ("v3_Roi_nocfg_steps8", "blue_v3", "Roi", 8, 1.0),
    ("v3_Roi_nocfg_steps16", "blue_v3", "Roi", 16, 1.0),
    ("v3_Rotem_cfg4_steps5 (app now)", "blue_v3", "Rotem", 5, 4.0),
    ("v3_Rotem_nocfg_steps8", "blue_v3", "Rotem", 8, 1.0),
    ("25_female_cfg4_steps5 (user: great)", "blue_25", "female", 5, 4.0),
    ("25_female_cfg4_steps10", "blue_25", "female", 10, 4.0),
    ("25_male6209_cfg4_steps10", "blue_25", "libri_male_6209", 10, 4.0),
]
engines = {}
summary = []
for name, bundle, voice, steps, cfg in VARIANTS:
    if bundle not in engines:
        engines[bundle] = load_text_to_speech(os.path.join(models, bundle))
    tts = engines[bundle]
    style = load_voice_style([os.path.join(models, bundle, "voices", voice + ".json")])
    errs = []
    for i, s in enumerate(SENT):
        np.random.seed(i)
        wav, _ = tts(g2p.phonemize(s), lang="he", style=style, total_step=steps, cfg_scale=cfg, text_is_phonemes=True)
        wav = limit_peak(wav[0])
        path = os.path.join(out, f"{name.split(' ')[0]}_{i}.wav")
        sf.write(path, wav, tts.sample_rate)
        audio16 = resample_poly(wav, 160, 441).astype(np.float32)  # 44.1 kHz -> 16 kHz
        segs, _ = asr.transcribe(audio16, language="he", beam_size=5)
        hyp = " ".join(x.text for x in segs)
        errs.append(cer(s, hyp))
        print(f"{name} [{i}] cer={errs[-1]:.3f} | {hyp}")
    summary.append((name, float(np.mean(errs))))
print("\n==== SUMMARY (character error rate, lower is clearer)")
for n, e in summary:
    print(f"QUALITY {e:.3f}  {n}")
