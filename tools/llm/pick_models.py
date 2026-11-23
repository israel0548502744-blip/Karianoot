#!/usr/bin/env python3
"""Prints "<repo> <file> <licence>" for each candidate GGUF, only when the repo's licence is commercially usable."""
from huggingface_hub import HfApi

OK = {"apache-2.0", "mit", "bsd-3-clause", "bsd-2-clause", "cc-by-4.0"}
api = HfApi()
wanted = [
    # (search author, must contain, prefer file containing)
    ("Qwen", "Qwen3-4B-Instruct-2507-GGUF", "Q4_K_M"),
    ("unsloth", "Qwen3-4B-Instruct-2507-GGUF", "Q4_K_M"),
    ("Qwen", "Qwen3-1.7B-GGUF", "Q8_0"),
    ("unsloth", "Qwen3-1.7B-GGUF", "Q4_K_M"),
    ("dicta-il", "1.7B", "Q4_K_M"),
    ("dicta-il", "DictaLM-3.0", "Q4_K_M"),
]
seen = set()
for author, need, quant in wanted:
    try:
        models = list(api.list_models(author=author, search=need.split("-GGUF")[0], limit=40))
    except Exception as e:
        print("#", author, need, "ERROR", e); continue
    for m in models:
        if need not in m.id or "gguf" not in m.id.lower() or m.id in seen:
            continue
        try:
            info = api.model_info(m.id, files_metadata=True)
        except Exception as e:
            print("#", m.id, "ERROR", e); continue
        lic = (info.card_data or {}).get("license") if info.card_data else None
        files = [s for s in info.siblings if s.rfilename.endswith(".gguf") and quant.lower() in s.rfilename.lower()]
        print("#", m.id, "licence", lic, "files", [f"{s.rfilename} {(s.size or 0)//2**20}MB" for s in files][:3])
        if str(lic).lower() in OK and files and (files[0].size or 0) < 3.2 * 2**30:
            seen.add(m.id)
            print(m.id, files[0].rfilename, lic, info.sha)
        if len([x for x in seen if need.split("-GGUF")[0] in x]) >= 1:
            break
