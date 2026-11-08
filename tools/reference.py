"""Builds the reference ("golden") data the Kotlin engine is tested against.

Runs the upstream Python packages (blue-onnx, renikud-plus) on fixed Hebrew sentences
with fixed noise, and saves what came out: phonemes, token ids, durations and audio.
The Kotlin port must reproduce the same phonemes exactly and the same audio closely.

Usage: python tools/reference.py <models_dir> <out_dir>
"""
import json
import os
import sys
import unicodedata

import numpy as np
import onnxruntime as ort
import soundfile as sf

models, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

SENTENCES = [
    "שלום, זהו מודל דיבור בעברית.",
    "הערב מכבי תל אביב פוגשת את הפועל, ואני ממליץ על ניצחון של הבית.",
    "ריאל מדריד שיחקה מצוין, אבל בסוף המשחק הכול השתנה!",
    "מה דעתכם? כתבו לי בתגובות, ותצטרפו לקבוצה שלנו.",
    "יחס גבוה, סיכון נמוך, וזה בדיוק מה שאנחנו אוהבים.",
    "הקבוצה הצעירה ניצחה בזכות שער בדקה האחרונה.",
]

# ---- graph inputs and outputs --------------------------------------------------
io = {}
for root, _, files in os.walk(models):
    for f in sorted(files):
        if f.endswith(".onnx"):
            p = os.path.join(root, f)
            s = ort.InferenceSession(p, providers=["CPUExecutionProvider"])
            rel = os.path.relpath(p, models)
            io[rel] = {
                "inputs": [[i.name, i.type, i.shape] for i in s.get_inputs()],
                "outputs": [[o.name, o.type, o.shape] for o in s.get_outputs()],
                "meta_keys": sorted(s.get_modelmeta().custom_metadata_map.keys()),
            }
            del s
json.dump(io, open(os.path.join(out, "graphs.json"), "w"), indent=1, default=str)
print(json.dumps(io, indent=1, default=str))

# ---- G2P ----------------------------------------------------------------------
from renikud_onnx import G2P

g2p = G2P(os.path.join(models, "renikud/model_int8.onnx"))
g2p_out = []
NUMBER_SENTENCES = [  # numbers are read as words before the model (hebrew-num2words)
    "היחס הוא 2.5, ואם ריאל מנצחת 3-1 זה כבר 7.20.",
    "המשחק מתחיל בשעה 21:45, ויש 70% סיכוי לשני שערים לפחות.",
]
for s in SENTENCES + NUMBER_SENTENCES:
    g2p_out.append({"text": s, "ipa": g2p.phonemize(s)})
    print(s, "->", g2p_out[-1]["ipa"])
json.dump(g2p_out, open(os.path.join(out, "g2p.json"), "w"), ensure_ascii=False, indent=1)

# ---- Blue ---------------------------------------------------------------------
import blue_onnx
from blue_onnx import load_text_to_speech, load_voice_style


def fix_v3(tts):
    """Blue V3: de-normalize with stats.npz before the 144-channel vocoder (model card).
    blue_onnx skips this when the vocoder is not 24-channel, which ruins V3."""
    import numpy as _np
    if tts._latent_mean is None or tts._vocoder_in_channels == tts.ldim:
        return tts
    def prep(xt):
        return ((xt / tts._normalizer_scale) * tts._latent_std + tts._latent_mean).astype(_np.float32)
    tts._prepare_vocoder_latent = prep
    return tts

class RecordedNoise:
    """Replaces np.random.randn so the noise can be saved and replayed in Kotlin."""
    def __init__(self):
        self.rng = np.random.default_rng(1234)
        self.calls = []
    def __call__(self, *shape):
        a = self.rng.standard_normal(shape).astype(np.float32)
        self.calls.append(a)
        return a

for bundle, voices in [("blue_v3", ["Roi", "Rotem"]), ("blue_25", ["female"])]:
    d = os.path.join(models, bundle)
    tts = fix_v3(load_text_to_speech(d, renikud_path=os.path.join(models, "renikud/model_int8.onnx")))
    for v in voices:
        style = load_voice_style([os.path.join(d, "voices", v + ".json")])
        for i, s in enumerate(SENTENCES[:3]):
            ipa = g2p.phonemize(s)
            noise = RecordedNoise()
            orig = np.random.randn
            np.random.randn = noise
            try:
                ids, mask = tts.text_processor([ipa], ["he"])
                # Blue V3 has guidance inside the graph (model card): no external CFG.
                wav, dur = tts._infer([ipa], ["he"], style, total_step=5, speed=1.0, cfg_scale=1.0 if bundle == "blue_v3" else 4.0)
            finally:
                np.random.randn = orig
            tag = f"{bundle}_{v}_{i}"
            np.save(os.path.join(out, tag + "_noise.npy"), noise.calls[0])
            np.save(os.path.join(out, tag + "_wav.npy"), wav.astype(np.float32))
            json.dump({"ipa": ipa, "ids": ids[0].tolist(), "dur": float(np.asarray(dur).reshape(-1)[0])},
                      open(os.path.join(out, tag + ".json"), "w"), ensure_ascii=False)
            sf.write(os.path.join(out, tag + ".wav"), blue_onnx.limit_peak(wav[0]), tts.sample_rate)
            print(tag, "dur", float(np.asarray(dur).reshape(-1)[0]), "samples", wav.shape)


# ---- Voice from a recording (VoiceCloner.kt) ---------------------------------------
# Reference audio is one of the golden outputs above, so no personal audio is involved.
import onnxruntime as _ort
from blue_onnx.style import _linear_mel_features, _trim_reference_latents
ref = np.load(os.path.join(out, "blue_25_female_1_wav.npy"))[0].astype(np.float32)
np.save(os.path.join(out, "clone_input.npy"), ref)
mel = _linear_mel_features(ref, sample_rate=44100, n_fft=2048, win_length=2048, hop_length=512, n_mels=228)
np.save(os.path.join(out, "clone_mel.npy"), mel)
frames = (mel.shape[2] // 6) * 6
codec = _ort.InferenceSession(os.path.join(models, "clone/codec_encoder.onnx"))
z = codec.run(None, {"mel": mel[:, :, :frames]})[0].astype(np.float32)
z = _trim_reference_latents(z)
np.save(os.path.join(out, "clone_z.npy"), z)
refenc = _ort.InferenceSession(os.path.join(models, "blue_25/reference_encoder.onnx"))
mask = np.ones((1, 1, z.shape[2]), np.float32)
ttl = refenc.run(None, {"z_ref": z, "mask": mask})[0].astype(np.float32)
np.save(os.path.join(out, "clone_ttl.npy"), ttl)
dpz = _ort.InferenceSession(os.path.join(models, "blue_25/duration_predictor.onnx"))
tts25 = fix_v3(load_text_to_speech(os.path.join(models, "blue_25")))
ipa = g2p.phonemize(SENTENCES[2])
ids, tmask = tts25.text_processor([ipa], ["he"])
dur = float(dpz.run(None, {"text_ids": ids, "z_ref": z, "text_mask": tmask, "ref_mask": mask})[0].reshape(-1)[0])
json.dump({"ipa": ipa, "dur": dur}, open(os.path.join(out, "clone.json"), "w"), ensure_ascii=False)
print("clone: mel", mel.shape, "z", z.shape, "ttl", ttl.shape, "dur", dur)
