"""Can Blue make a new voice from a recording, fully on ONNX (so it can run on the phone)?

Reference recordings here are made by the models themselves (no personal audio in CI).
Two paths are scored by intelligibility (Hebrew ASR character error) and by how close the
pitch of the new voice is to the reference:
  A. Blue v2 bundle end to end (codec_encoder -> style_encoder / duration_style_encoder).
  B. Blue 2.5 using v2's codec_encoder latents + 2.5's own reference_encoder and
     z_ref duration predictor.

Usage: python tools/clone_probe.py <models_dir> <out_dir>
"""
import os, sys, json, unicodedata
import numpy as np, soundfile as sf, librosa, onnxruntime as ort
from scipy.signal import resample_poly
from blue_onnx import load_text_to_speech, load_voice_style, limit_peak, Style
from blue_onnx.style import VoiceStyleExtractor, _linear_mel_features, _trim_reference_latents
from renikud_onnx import G2P
from faster_whisper import WhisperModel

models, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
g2p = G2P(os.path.join(models, "renikud/model_int8.onnx"))
asr = WhisperModel("ivrit-ai/whisper-large-v3-turbo-ct2", device="cpu", compute_type="int8")
REF_TEXT = ("ערב טוב לכולם, ברוכים הבאים לתוכנית הכדורגל הגדולה של השבוע. היום יש לנו משחקים מטורפים, "
            "ואני הולך לספר לכם בדיוק על מה כדאי לשים את הכסף. תישארו איתי עד הסוף.")
TEST = ["ריאל מדריד שיחקה מצוין, אבל בסוף המשחק הכול השתנה.", "יחס גבוה, סיכון נמוך, וזה בדיוק מה שאנחנו אוהבים."]

def norm(s):
    s = "".join(c for c in unicodedata.normalize("NFD", s) if "א" <= c <= "ת" or c == " ")
    return " ".join(s.split())
def cer(ref, hyp):
    r, h = norm(ref).replace(" ", ""), norm(hyp).replace(" ", "")
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1])); prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))
def f0(wav, sr):
    f, v, _ = librosa.pyin(librosa.resample(wav, orig_sr=sr, target_sr=16000), fmin=60, fmax=400, sr=16000)
    f = f[~np.isnan(f)]
    return float(np.median(f)) if len(f) else 0.0
def score(wav, sr, text):
    segs, _ = asr.transcribe(resample_poly(wav, 160, 441).astype(np.float32), language="he", beam_size=5)
    return cer(text, " ".join(x.text for x in segs))

t25 = load_text_to_speech(os.path.join(models, "blue_25"))
v2dir = os.path.join(models, "blue_v2")
t2 = load_text_to_speech(v2dir)
ext = VoiceStyleExtractor(onnx_dir=v2dir, config=os.path.join(v2dir, "tts.json"))
refenc = ort.InferenceSession(os.path.join(models, "blue_25/reference_encoder.onnx"))
dpz = ort.InferenceSession(os.path.join(models, "blue_25/duration_predictor.onnx"))
print("ref_enc inputs", [(i.name, i.shape) for i in refenc.get_inputs()])
print("codec inputs", [(i.name, i.shape) for i in ext.codec_encoder.get_inputs()], [(o.name, o.shape) for o in ext.codec_encoder.get_outputs()])

results = []
for ref_voice in ["libri_male_6209", "libri_female_1088"]:
    st = load_voice_style([os.path.join(models, "blue_25/voices", ref_voice + ".json")])
    np.random.seed(0)
    ref, _ = t25(g2p.phonemize(REF_TEXT), lang="he", style=st, total_step=10, text_is_phonemes=True, silence_duration=0.2)
    ref = limit_peak(ref[0]); refp = os.path.join(out, f"ref_{ref_voice}.wav"); sf.write(refp, ref, 44100)
    f_ref = f0(ref, 44100)

    # A: v2 end to end
    sA = ext.from_wav(refp)
    # B: 2.5 with v2 codec latents
    z = _trim_reference_latents(ext._z_ref(refp))
    mask = np.ones((1, 1, z.shape[2]), np.float32)
    ttl25 = refenc.run(None, {refenc.get_inputs()[0].name: z, refenc.get_inputs()[1].name: mask})[0].astype(np.float32)
    for name, tts, style, dp_override in [("A_v2", t2, sA, None), ("B_25", t25, Style(ttl25, st.dp), z)]:
        errs, fs = [], []
        for i, s in enumerate(TEST):
            ipa = g2p.phonemize(s)
            np.random.seed(i)
            if dp_override is not None:
                # duration from the reference latents instead of a style_dp vector
                ids, tmask = tts.text_processor([ipa], ["he"])
                d = dpz.run(None, {"text_ids": ids, "z_ref": dp_override, "text_mask": tmask, "ref_mask": mask})[0]
                orig = tts.dp_ort.run
                tts.dp_ort.run = lambda *_a, _d=d, **_k: [_d]
                try:
                    wav, _ = tts(ipa, lang="he", style=style, total_step=10, text_is_phonemes=True)
                finally:
                    tts.dp_ort.run = orig
            else:
                wav, _ = tts(ipa, lang="he", style=style, total_step=10, text_is_phonemes=True)
            wav = limit_peak(wav[0]); sf.write(os.path.join(out, f"{name}_{ref_voice}_{i}.wav"), wav, 44100)
            errs.append(score(wav, 44100, s)); fs.append(f0(wav, 44100))
        results.append((name, ref_voice, float(np.mean(errs)), f_ref, float(np.mean(fs))))
        print(f"CLONE {name} ref={ref_voice} cer={np.mean(errs):.3f} f0_ref={f_ref:.0f}Hz f0_clone={np.mean(fs):.0f}Hz")
print("\n==== SUMMARY")
for r in results: print("CLONE %-5s %-18s cer=%.3f  pitch ref %.0f Hz -> clone %.0f Hz" % r)
