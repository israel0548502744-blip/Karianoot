#!/usr/bin/env python3
"""Hebrew Magpie TTS (notmax123/magpie_tts_multilingual_357m-he) -> one GGUF for magpie-tts.cpp.

The upstream converter (magpie-tts.cpp/scripts/convert_magpie_to_gguf.py) reads the base model's .nemo.
The Hebrew fine-tune is a Lightning checkpoint (state_dict) + hparams.yaml, and differs in three places:
  * a 16th text tokenizer "hebrew_chartokenizer" (one token per IPA symbol) appended after the base 15,
    so text_embedding rows are [base 3357 | hebrew | BOS | EOS];
  * 20 baked speakers instead of 5 (5-19 are the Hebrew voices);
  * it is run with temperature 0.7, top-k 80, CFG 2.5 and no attention prior (scripts/infer_hebrew.py).
This script reuses the upstream converter's functions and writes a Hebrew-only tokenizer block: language
"he" -> hebrew_chartokenizer. The other languages' embedding rows stay in the file, but their tokenizers and
G2P dictionaries are left out (no NeMo needed to convert, and 31 MB smaller).

Usage:
  python tools/magpie/convert_he.py --ckpt-dir ckpt --codec-dir nanocodec_extracted \
      --magpie-cpp magpie-tts.cpp --out magpie-he-f32.gguf --info-out convert_info.json
"""
import argparse
import json
import pathlib
import sys

import torch
import yaml

HEBREW = "hebrew_chartokenizer"
BASE_VOCAB = 3357          # tokens of the base model's 15 tokenizers
BASE_SPEAKERS = {"Aria": 0, "Jason": 1, "John": 2, "Leo": 3, "Sofia": 4}
# The base checkpoint's inference_parameters (used only for keys hparams.yaml does not carry).
BASE_INFERENCE = {
    "temperature": 0.6, "topk": 80, "cfg_scale": 2.5, "argmax_temperature": 0.01, "max_decoder_steps": 500,
    "eos_detection_method": "argmax_or_multinomial_any", "ignore_finished_sentence_tracking": True,
    "apply_attention_prior": True, "attention_prior_epsilon": 0.1, "attention_prior_lookahead_window": 5,
    "apply_prior_to_layers": [2, 3, 4, 5, 6, 7, 8, 9, 10], "estimate_alignment_from_layers": [4, 5, 8, 9],
    "start_prior_after_n_audio_steps": 0,
}
# What scripts/infer_hebrew.py runs with (NeMo ModelInferenceParameters defaults, --use_cfg, no prior).
HEBREW_INFERENCE = {"temperature": 0.7, "topk": 80, "cfg_scale": 2.5, "max_decoder_steps": 500,
                    "apply_attention_prior": False}


def hebrew_tokens(tcfg):
    """Token list of NeMo's BaseCharsTokenizer: [' '] + chars (+ "'") + punctuation + ['<pad>', '<oov>']."""
    if not str(tcfg.get("_target_", "")).endswith("BaseCharsTokenizer"):
        raise SystemExit(f"unexpected Hebrew tokenizer class: {tcfg.get('_target_')}")
    if tcfg.get("add_blank_at") is not None:
        raise SystemExit("add_blank_at is not supported")
    tokens = [" "] + list(str(tcfg["chars"]))
    if tcfg.get("apostrophe", True):
        tokens.append("'")
    punct = []
    if tcfg.get("punct", True):
        punct = tcfg.get("non_default_punct_list")
        if punct is None:
            raise SystemExit("the default NeMo punctuation list is not supported")
        tokens += [str(p) for p in punct]
    pad = len(tokens)
    tokens += ["<pad>", "<oov>"]
    return tokens, pad, [str(p) for p in punct]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt-dir", required=True, help="magpie_hebrew.ckpt, hparams.yaml, speaker_index.json")
    ap.add_argument("--codec-dir", required=True, help="extracted NanoCodec .nemo (model_config.yaml, model_weights.ckpt)")
    ap.add_argument("--magpie-cpp", required=True, help="magpie-tts.cpp checkout (its scripts/ is imported)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--info-out", help="write a JSON summary (symbols, speakers, defaults) here")
    args = ap.parse_args()

    sys.path.insert(0, str(pathlib.Path(args.magpie_cpp) / "scripts"))
    import convert_magpie_to_gguf as up
    import gguf

    ckpt_dir, codec_dir = pathlib.Path(args.ckpt_dir), pathlib.Path(args.codec_dir)
    hp = yaml.safe_load((ckpt_dir / "hparams.yaml").read_text(encoding="utf-8"))
    cfg = hp["cfg"] if "cfg" in hp else hp
    codec_cfg = yaml.safe_load((codec_dir / "model_config.yaml").read_text(encoding="utf-8"))

    # ---- tokenizer: Hebrew must be the last one, right before BOS/EOS ----
    names = list(cfg["text_tokenizers"].keys())
    print("text tokenizers:", names)
    if names[-1] != HEBREW:
        raise SystemExit(f"{HEBREW} is not the last tokenizer")
    tokens, pad_local, punct = hebrew_tokens(cfg["text_tokenizers"][HEBREW])

    print("loading checkpoints ...")
    sd = up.load_state_dict(ckpt_dir / "magpie_hebrew.ckpt")
    sd = sd.get("state_dict", sd)
    codec_sd = up.load_state_dict(codec_dir / "model_weights.ckpt")
    codec_sd = codec_sd.get("state_dict", codec_sd)

    rows, d_model = sd["text_embedding.weight"].shape
    offset = rows - 2 - len(tokens)
    print(f"text_embedding {rows} rows; hebrew block [{offset}, {offset + len(tokens)}), BOS {rows - 2}, EOS {rows - 1}")
    if offset != BASE_VOCAB:
        raise SystemExit(f"hebrew offset {offset} != base vocabulary {BASE_VOCAB}: unexpected embedding layout")
    vocab = offset + len(tokens)

    # ---- baked speakers ----
    baked = sd["baked_context_embedding.weight"]
    n_spk = baked.shape[0]
    baked_lens = [int(x) for x in sd["baked_context_embedding_len"]]
    if "_baked_embedding_T" not in sd:
        sd["_baked_embedding_T"] = torch.tensor(max(baked_lens))
        sd["_baked_embedding_D"] = torch.tensor(baked.shape[1] // max(baked_lens))
    baked_t, baked_d = int(sd["_baked_embedding_T"]), int(sd["_baked_embedding_D"])
    if baked.shape[1] != baked_t * baked_d or len(baked_lens) != n_spk:
        raise SystemExit(f"baked context {tuple(baked.shape)} does not match T={baked_t} D={baked_d} lens={len(baked_lens)}")
    speakers = dict(BASE_SPEAKERS)
    speakers.update({str(k): int(v) for k, v in json.loads((ckpt_dir / "speaker_index.json").read_text()).items()})
    if sorted(speakers.values()) != list(range(n_spk)):
        raise SystemExit(f"speaker names {speakers} do not cover the {n_spk} baked rows")
    print("speakers:", speakers)

    # ---- inference defaults ----
    inf = dict(BASE_INFERENCE)
    inf.update({k: v for k, v in (cfg.get("inference_parameters") or {}).items() if v is not None})
    if "prior_epsilon" in inf:
        inf["attention_prior_epsilon"] = inf["prior_epsilon"]
    if "lookahead_window_size" in inf:
        inf["attention_prior_lookahead_window"] = inf["lookahead_window_size"]
    print("checkpoint inference_parameters:", cfg.get("inference_parameters"))
    inf.update(HEBREW_INFERENCE)
    cfg["inference_parameters"] = inf

    # ---- write ----
    class KV(up.KV):
        """The upstream converter hard-codes the base model's name and text vocabulary size."""
        fixed = {"magpie.text.vocab_size": rows, "magpie.text.bos_id": rows - 2, "magpie.text.eos_id": rows - 1}

        def u32(self, key, val):
            super().u32(key, self.fixed.get(key, val))

        def array(self, key, val):
            if len(val):                           # GGUF cannot type an empty array (the G2P name lists)
                super().array(key, val)

        def string(self, key, val):
            super().string(key, "magpie-tts-multilingual-357m-he" if key == "general.name" else val)

    num_groups = int(codec_cfg["vector_quantizer"]["num_groups"])
    fsq_levels, fsq_base = up.extract_fsq_buffers(codec_sd, num_groups)

    w = gguf.GGUFWriter(args.out, "magpie-tts")
    kv = KV(w)
    kv.string("general.license", "NVIDIA Open Model License")
    kv.string("general.source.url", "https://huggingface.co/notmax123/magpie_tts_multilingual_357m-he")
    up.write_magpie_hparams(kv, cfg, speakers, baked_lens, baked_t, baked_d)
    up.write_codec_hparams(kv, codec_cfg, fsq_levels, fsq_base)
    tok = {
        "vocab_size": vocab, "bos": vocab, "eos": vocab + 1,
        "tokens": [""] * offset + tokens,          # base rows keep their ids; their strings are not needed
        "tokenizer_names": [HEBREW],
        "offsets": {HEBREW: offset}, "num_tokens": {HEBREW: len(tokens)}, "pad_ids": {HEBREW: offset + pad_local},
        "language_map": {"he": [HEBREW]},
        "tokenizer_configs": {HEBREW: {"class": "BaseCharsTokenizer", "pad_with_space": bool(
            cfg["text_tokenizers"][HEBREW].get("pad_with_space", False)), "pad_id_local": pad_local, "space_id_local": 0}},
        "g2p_dicts": {}, "heteronyms": {},
    }
    up.write_tokenizer(kv, w, tok, strict=True)
    n_magpie, _ = up.convert_magpie_tensors(w, sd, "f32", strict=False)
    n_codec = up.convert_codec_tensors(w, codec_sd, "f32", strict=False)
    print(f"tensors: magpie {n_magpie} (base model: 237), codec {n_codec} (290)")
    w.write_header_to_file()
    w.write_kv_data_to_file()
    w.write_tensors_to_file()
    w.close()
    size = pathlib.Path(args.out).stat().st_size
    print(f"wrote {args.out}: {size / 1e6:.1f} MB")

    symbols = [t for t in tokens[1:pad_local]]
    info = {
        "language": "he", "tokenizer": HEBREW, "symbols": symbols, "symbols_joined": "".join(symbols),
        "punctuation": punct, "text_vocab": rows, "hebrew_offset": offset, "speakers": speakers,
        "sample_rate": int(codec_cfg["sample_rate"]),
        "defaults": {k: inf[k] for k in ("temperature", "topk", "cfg_scale", "max_decoder_steps", "apply_attention_prior")},
    }
    print(json.dumps(info, ensure_ascii=False))
    if args.info_out:
        pathlib.Path(args.info_out).write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
