"""Full-precision reference and merged checkpoint for the accent probe.
1. Qwen3-TTS-12Hz-1.7B-Base + the Hebrew LoRA (merged), Python, F32: renders the pieces with the app's voice prints.
2. Saves the merged weights as a checkpoint folder that qwentts.cpp's convert.py turns into GGUF (for Q8_0 / BF16).

Usage: python full.py <voices,comma> <spk_dir> <out_dir> <checkpoint_dir>
"""
import gc, glob, json, os, shutil, sys, time
import numpy as np, soundfile as sf, torch
from peft import PeftModel
from qwen_tts import Qwen3TTSModel
from huggingface_hub import snapshot_download
from safetensors import safe_open
from safetensors.torch import save_file

voices, spk_dir, out, ck = sys.argv[1].split(","), sys.argv[2], sys.argv[3], sys.argv[4]
plan = json.load(open("plan.json", encoding="utf8"))
torch.set_num_threads(4)
BASE = "Qwen/Qwen3-TTS-12Hz-1.7B-Base"
tts = Qwen3TTSModel.from_pretrained(BASE, device_map="cpu", dtype=torch.float32)
pm = PeftModel.from_pretrained(tts.model.talker, "notmax123/QwenTTS-he-1.7B")
print("LORA", {k: getattr(c, a, None) for k, c in pm.peft_config.items() for a in ["r"]}, pm.peft_config, flush=True)
tts.model.talker = pm.merge_and_unload()
tts.model.eval()
for v in voices:
    spk = torch.from_numpy(np.fromfile(f"{spk_dir}/q_{v}.spk", dtype="<f4").copy())
    for pid in ["P0", "P1", "R"]:
        ipa = plan["pieces"][pid]["ipa"]; t0 = time.time()
        try:
            wavs, sr = tts.generate_voice_clone(
                text=ipa, language="Auto", non_streaming_mode=True, do_sample=False, subtalker_dosample=False, repetition_penalty=1.0,
                max_new_tokens=min(40 + 2 * len(ipa), 1500),
                voice_clone_prompt=dict(ref_code=[None], ref_spk_embedding=[spk], x_vector_only_mode=[True], icl_mode=[False]))
            sf.write(f"{out}/{v}__py__{pid}.wav", np.asarray(wavs[0], np.float32), sr)
            print("PY", v, pid, f"{len(wavs[0]) / sr:.1f}s audio in {time.time() - t0:.0f}s", flush=True)
        except Exception as e:
            print("PYFAIL", v, pid, repr(e)[:400], flush=True)

# Merged weights -> checkpoint folder (bf16, to fit the runner's memory; freed from the model as they are copied).
snap = snapshot_download(BASE, allow_patterns=["*.json", "*.txt", "model.safetensors"])
keep = {}
for n, p in tts.model.named_parameters():
    if n.startswith(("talker.", "speaker_encoder.")): keep[n] = p.data.to(torch.bfloat16).contiguous()
    p.data = torch.empty(0)
for n, b in tts.model.named_buffers():
    if n.startswith(("talker.", "speaker_encoder.")) and n not in keep: keep[n] = b.data.clone()
del tts, pm; gc.collect()
with safe_open(os.path.join(snap, "model.safetensors"), framework="pt") as f:
    keys = list(f.keys()); k0 = next(k for k in keys if k.endswith("q_proj.weight"))
    print("ORIG dtype", f.get_tensor(k0).dtype, "keys", len(keys), "merged", len(keep), flush=True)
    for k in keys:
        if k not in keep and k.startswith(("talker.", "speaker_encoder.")):
            print("FILL from base:", k); keep[k] = f.get_tensor(k)
    extra = [k for k in keep if k not in keys]
    print("EXTRA keys dropped:", extra[:20], len(extra))
    for k in extra: del keep[k]
    d = (f.get_tensor(k0).float() - keep[k0].float()).abs().max().item()
    print("LoRA changed", k0, "by max", d, flush=True)
os.makedirs(ck, exist_ok=True)
for p in glob.glob(os.path.join(snap, "*")):
    if os.path.isfile(p) and not p.endswith(".safetensors"): shutil.copy(p, ck)
save_file(keep, os.path.join(ck, "model.safetensors"))
print("SAVED", ck, os.listdir(ck), flush=True)
