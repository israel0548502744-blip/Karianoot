#!/usr/bin/env python3
"""Merge the proofreader's shards: words every voice missed (leads), words one voice missed (likely the recognizer).

Usage: report.py <dir with proof_*.json> <out dir>   -> report.json + report.md
"""
import collections, glob, json, os, sys

src, out = sys.argv[1:3]
rows = sorted((r for f in glob.glob(os.path.join(src, "**", "proof_*.json"), recursive=True)
               for r in json.load(open(f, encoding="utf-8"))), key=lambda r: (r["sentence"], r["piece"]))
leads, single = [], collections.Counter()
for r in rows:
    takes = {s: t for s, t in r["takes"].items() if "missed" in t}
    if not takes:
        continue
    seen = collections.Counter(m["ref"] for t in takes.values() for m in t["missed"])
    for ref, n in seen.items():
        if n == len(takes):
            leads.append({"sentence": r["sentence"], "ref": ref, "text": r["text"], "ipa": r["ipa"],
                          "heard": {s: t["heard"] for s, t in takes.items()}})
        else:
            single[ref] += 1
errors = [r for r in rows if any("error" in t for t in r["takes"].values())]
summary = {"pieces": len(rows), "leads": len(leads), "single_voice_misses": sum(single.values()), "errors": len(errors)}
os.makedirs(out, exist_ok=True)
json.dump({"summary": summary, "leads": leads, "single": single.most_common(), "rows": rows},
          open(os.path.join(out, "report.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
with open(os.path.join(out, "report.md"), "w", encoding="utf-8") as f:
    f.write(f"# Proofread\n\n{json.dumps(summary)}\n\n## Missed by every voice\n\n")
    for l in leads:
        f.write(f"- **{l['ref']}** | {l['text']}\n  - ipa: {l['ipa']}\n")
        for s, h in l["heard"].items():
            f.write(f"  - {s}: {h}\n")
    f.write("\n## Missed by one voice\n\n" + "\n".join(f"- {w} x{n}" for w, n in single.most_common()) + "\n")
print(json.dumps(summary))
for l in leads:
    print("LEAD", l["ref"], "|", l["text"], "|", list(l["heard"].values())[0])
