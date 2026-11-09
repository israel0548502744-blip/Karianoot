#!/usr/bin/env python3
"""Synthesize the test passage with magpie-cli for each GGUF and voice, time it, transcribe and score.

Usage: run_eval.py <magpie-cli> <passage.json> <out dir> <asr dir> [nemo wav dir]
Environment: PLAN = "label=file.gguf:spk,spk[:prior]; ..."  (":prior" runs with the attention prior on)
Writes <out dir>/<label>/spk<N>_<piece>.wav and <out dir>/eval.json.
"""
import json, os, re, subprocess, sys, time, unicodedata
import numpy as np
import soundfile as sf
from scipy.signal import resample_poly
from faster_whisper import WhisperModel

cli, passage, out, asr_dir = sys.argv[1:5]
nemo_dir = sys.argv[5] if len(sys.argv) > 5 else None
pieces = json.load(open(passage, encoding="utf-8"))["pieces"]
asr = WhisperModel(asr_dir, device="cpu", compute_type="int8")
THREADS = os.cpu_count() or 4


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


def score(path, ref):
    wav, sr = sf.read(path, dtype="float32")
    if wav.ndim > 1:
        wav = wav.mean(axis=1)
    a16 = resample_poly(wav, 16000, sr).astype(np.float32)
    segs, _ = asr.transcribe(a16, language="he", beam_size=5)
    hyp = " ".join(x.text for x in segs).strip()
    return {"seconds": round(len(wav) / sr, 2), "cer": round(cer(ref, hyp), 4), "peak": round(float(np.abs(wav).max()), 3),
            "heard": hyp}


runs = []
for item in filter(None, (x.strip() for x in os.environ["PLAN"].split(";"))):
    label, rest = item.split("=", 1)
    parts = rest.split(":")
    gguf, speakers, prior = parts[0], [int(x) for x in parts[1].split(",")], len(parts) > 2 and parts[2] == "prior"
    os.makedirs(os.path.join(out, label), exist_ok=True)
    env = dict(os.environ, MAGPIE_PRIOR="1" if prior else "0")
    for spk in speakers:
        for i, p in enumerate(pieces):
            wav = os.path.join(out, label, f"spk{spk}_{i}.wav")
            t0 = time.time()
            r = subprocess.run([cli, "say", "--model", gguf, "--text", p["ipa"], "--lang", "he", "--speaker", str(spk),
                                "--seed", str(1234 + i), "--threads", str(THREADS), "--output", wav],
                               capture_output=True, text=True, env=env)
            wall = time.time() - t0
            log = r.stdout + r.stderr
            if r.returncode != 0 or not os.path.exists(wav):
                print(f"FAILED {label} spk{spk} piece{i}:\n{log[-2000:]}")
                runs.append({"label": label, "speaker": spk, "piece": i, "error": log[-500:]})
                continue
            m = re.search(r"load ([\d.]+) s, synthesis ([\d.]+) s", log)
            st = re.search(r"synthesize: (\d+) decoder steps, (\d+) frames kept .* in ([\d.]+) ms", log)
            row = {"label": label, "gguf": os.path.basename(gguf), "prior": prior, "speaker": spk, "piece": i,
                   "load_s": float(m.group(1)) if m else None, "synth_s": float(m.group(2)) if m else round(wall, 2),
                   "decode_s": round(float(st.group(3)) / 1000, 2) if st else None,
                   "steps": int(st.group(1)) if st else None,
                   "skipped": re.findall(r"skipped unknown symbols '(.*)'", log)}
            row.update(score(wav, p["text"]))
            row["work_per_audio_s"] = round(row["synth_s"] / max(row["seconds"], 0.01), 2)
            runs.append(row)
            print(f"{label} spk{spk} [{i}] {row['seconds']}s audio, synth {row['synth_s']}s "
                  f"(x{row['work_per_audio_s']}), cer {row['cer']:.3f} | {row['heard']}", flush=True)

nemo = []
if nemo_dir and os.path.isdir(nemo_dir):
    wanted = sorted({r["speaker"] for r in runs})
    for spk in wanted:
        for i, p in enumerate(pieces):
            path = os.path.join(nemo_dir, f"predicted_audio_{(spk - 5) * len(pieces) + i}.wav")
            if spk >= 5 and os.path.exists(path):
                row = {"label": "nemo", "speaker": spk, "piece": i}
                row.update(score(path, p["text"]))
                nemo.append(row)
                print(f"nemo spk{spk} [{i}] {row['seconds']}s, cer {row['cer']:.3f} | {row['heard']}", flush=True)

summary = {}
for r in runs + nemo:
    if "cer" not in r:
        continue
    s = summary.setdefault(r["label"], {"cer_by_speaker": {}, "audio_s": 0.0, "synth_s": 0.0})
    s["cer_by_speaker"].setdefault(str(r["speaker"]), []).append(r["cer"])
    s["audio_s"] += r["seconds"]
    s["synth_s"] += r.get("synth_s", 0.0)
for label, s in summary.items():
    s["cer_by_speaker"] = {k: round(float(np.mean(v)), 4) for k, v in s["cer_by_speaker"].items()}
    s["cer_mean"] = round(float(np.mean(list(s["cer_by_speaker"].values()))), 4)
    s["work_per_audio_s"] = round(s["synth_s"] / s["audio_s"], 2) if s["synth_s"] else None
    s["audio_s"], s["synth_s"] = round(s["audio_s"], 1), round(s["synth_s"], 1)
    print("SUMMARY", label, json.dumps(s))
json.dump({"threads": THREADS, "summary": summary, "runs": runs, "nemo": nemo},
          open(os.path.join(out, "eval.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
if any("error" in r for r in runs):
    sys.exit(1)
