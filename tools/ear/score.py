"""An automatic "ear": does a speech-quality predictor agree with the user's picks?

Scores every clip in tools/ear/samples with UTMOS (naturalness MOS, MIT) and DNSMOS (MIT), then reports how often a clip
the user liked scores above one they rejected (AUC; 0.5 = chance). Writes scores.json.
"""
import glob, json, os, sys
import numpy as np, torch, librosa
from speechmos import dnsmos
sys.path.insert(0, "utmos")
from speechmos_utmos.utmos22.strong.model import UTMOS22Strong  # the cloned package, renamed to avoid the pip one

m = UTMOS22Strong(); m.load_state_dict(torch.load("utmos.pt", map_location="cpu", weights_only=True)); m.eval()
here = os.path.dirname(os.path.abspath(__file__))
picks = json.load(open(f"{here}/picks.json"))
res = {}
for p in sorted(glob.glob(f"{here}/samples/*.mp3")):
    k = os.path.basename(p)[:-4]
    w, _ = librosa.load(p, sr=16000, mono=True)
    win = [w[i:i + 160000] for i in range(0, max(1, len(w) - 16000), 160000)]
    with torch.no_grad(): u = [float(m(torch.from_numpy(x).unsqueeze(0), 16000)) for x in win]
    d = dnsmos.run((w / (np.abs(w).max() + 1e-9)).astype(np.float32), sr=16000)
    res[k] = {"pick": picks.get(k), "utmos": round(float(np.mean(u)), 3), "utmos_min": round(float(np.min(u)), 3),
              "dns_ovr": round(float(d["ovrl_mos"]), 3), "dns_sig": round(float(d["sig_mos"]), 3), "p808": round(float(d["p808_mos"]), 3)}
    print(k, res[k], flush=True)
json.dump(res, open("scores.json", "w"), indent=1)
for key in ("utmos", "utmos_min", "dns_ovr", "dns_sig", "p808"):
    g = [r[key] for r in res.values() if r["pick"]]; b = [r[key] for r in res.values() if r["pick"] == 0]
    auc = np.mean([(x > y) + 0.5 * (x == y) for x in g for y in b])
    print(f"AUC {key}: liked {len(g)} mean {np.mean(g):.2f}, rejected {len(b)} mean {np.mean(b):.2f}, AUC {auc:.2f}")
