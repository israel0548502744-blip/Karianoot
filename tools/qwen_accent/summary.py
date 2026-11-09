"""Merges the per-job score files into scores.json and prints a table per variant. Usage: summary.py <dir> <out_dir>"""
import glob, json, os, sys
from collections import defaultdict

rows = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], "**", "scores_*.json"), recursive=True)): rows += json.load(open(f, encoding="utf8"))


def agg(rs):
    n = sum(r["r"]["n"] for r in rs); miss = sum(r["r"]["missing"] for r in rs); heard = max(1, n - miss)
    soft = [r["r"]["p_trill"] for r in rs if r["r"]["p_trill"] is not None]
    return {"takes": len(rs), "resh": n,
            "rolled_pct": round(100 * sum(r["r"]["trill"] for r in rs) / heard, 1),
            "uvular_pct": round(100 * sum(r["r"]["uvular"] for r in rs) / heard, 1),
            "fric_pct": round(100 * sum(r["r"]["fric"] for r in rs) / heard, 1),
            "approx_pct": round(100 * sum(r["r"]["approx"] for r in rs) / heard, 1),
            "missing_pct": round(100 * miss / max(1, n), 1),
            "p_trill": round(sum(soft) / len(soft), 3) if soft else None,
            "cer_pct": round(100 * sum(r["cer"] for r in rs) / len(rs), 1),
            "broken": sum(r["broken"] for r in rs)}


by_var, by_vv = defaultdict(list), defaultdict(list)
for r in rows:
    by_var[r["variant"]].append(r); by_vv[r["variant"] + " / " + r["voice"]].append(r)
summary = {"by_variant": {k: agg(v) for k, v in sorted(by_var.items())}, "by_variant_voice": {k: agg(v) for k, v in sorted(by_vv.items())}}
os.makedirs(sys.argv[2], exist_ok=True)
json.dump({"summary": summary, "files": rows}, open(os.path.join(sys.argv[2], "scores.json"), "w", encoding="utf8"), ensure_ascii=False, indent=1)
cols = ["takes", "resh", "rolled_pct", "uvular_pct", "fric_pct", "approx_pct", "missing_pct", "p_trill", "cer_pct", "broken"]
lines = []
for title, d in [("Per variant (all voices)", summary["by_variant"]), ("Per variant and voice", summary["by_variant_voice"])]:
    lines += [f"## {title}", "| variant | " + " | ".join(cols) + " |", "|---|" + "---|" * len(cols)]
    lines += [f"| {k} | " + " | ".join(str(v[c]) for c in cols) + " |" for k, v in d.items()]
open(os.path.join(sys.argv[2], "summary.md"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
