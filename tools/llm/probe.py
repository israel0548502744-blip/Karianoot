#!/usr/bin/env python3
"""Each model in models.txt writes tips-video scripts for a few match lists; timed. Writes out/report.md + out/<model>.json.

Usage: probe.py <llama-server> <models.txt> <out dir>
"""
import json, os, subprocess, sys, time, urllib.request
from huggingface_hub import hf_hub_download

server, models_txt, out = sys.argv[1:4]
os.makedirs(out, exist_ok=True)

SYSTEM = ("אתה כותב תסריטים קצרים לסרטוני טיפים להימורי כדורגל בטיקטוק, בעברית טבעית ומדוברת של ישראלים. "
          "כתוב טקסט לקריינות בלבד: בלי כותרות, בלי אימוג'י, בלי רשימות, בלי סימני * או #. משפטים קצרים וברורים. "
          "פתיחה שתופסת בשנייה הראשונה, משפט נימוק אחד לכל משחק, וסגירה שמבקשת לעקוב. "
          "יחסים כותבים במספרים כמו 2.10. אל תמציא נתונים שלא קיבלת. עד 120 מילים.")
JOBS = [
    {"משחקים": [{"משחק": "ארסנל נגד צ'לסי", "הימור": "ניצחון ארסנל", "יחס": "2.10", "נימוק": "ארסנל ניצחה 4 מ-5 האחרונים בבית"},
                {"משחק": "ריאל מדריד נגד ברצלונה", "הימור": "שתי הקבוצות מבקיעות", "יחס": "1.75"}], "סגנון": "מתלהב"},
    {"משחקים": [{"משחק": "מכבי חיפה נגד הפועל באר שבע", "הימור": "תיקו", "יחס": "3.40", "נימוק": "שלושת המפגשים האחרונים נגמרו בתיקו"}],
     "סגנון": "פרשן רגוע ומקצועי"},
    {"משחקים": [{"משחק": "באיירן מינכן נגד דורטמונד", "הימור": "מעל 2.5 שערים", "יחס": "1.60"},
                {"משחק": "אינטר נגד מילאן", "הימור": "ניצחון אינטר", "יחס": "2.05"},
                {"משחק": "פ.ס.ז' נגד מרסיי", "הימור": "פ.ס.ז' בהפרש 2+", "יחס": "2.40"}], "סגנון": "טופס כפול, יחס כולל"},
]

def ask(port, msg, think_off):
    body = {"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": msg}],
            "temperature": 0.7, "top_p": 0.9, "max_tokens": 450}
    if think_off:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    r = json.loads(urllib.request.urlopen(req, timeout=900).read())
    dt = time.time() - t0
    text = r["choices"][0]["message"]["content"]
    toks = r.get("usage", {}).get("completion_tokens", 0)
    return text, dt, toks

report = ["# Script writer probe\n"]
for line in open(models_txt, encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
        continue
    repo, fname, lic, sha = line.split()[:4]
    path = hf_hub_download(repo, fname, revision=sha)
    size = os.path.getsize(path) / 2**30
    port = 8190
    p = subprocess.Popen([server, "-m", path, "--port", str(port), "-t", "4", "-c", "4096", "--jinja", "--no-webui"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(240):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2); break
            except Exception:
                time.sleep(1)
        report.append(f"\n## {repo} / {fname} ({size:.2f} GB, licence {lic})\n")
        rows = []
        for i, job in enumerate(JOBS):
            msg = "כתוב תסריט לסרטון לפי הנתונים האלה:\n" + json.dumps(job, ensure_ascii=False, indent=1)
            try:
                text, dt, toks = ask(port, msg, think_off=True)
            except Exception as e:
                text, dt, toks = f"ERROR {e}", 0, 0
            # Thinking models may still emit <think>…</think>.
            if "</think>" in text:
                text = text.split("</think>", 1)[1].strip()
            rows.append({"job": i, "seconds": round(dt, 1), "tokens": toks, "tok_per_s": round(toks / dt, 1) if dt else 0, "text": text})
            report.append(f"\n### {i + 1}. {job['סגנון']} — {dt:.0f} s, {toks} tokens ({toks / max(dt, 0.1):.1f}/s)\n\n{text}\n")
            print(repo, i, round(dt, 1), toks, flush=True)
        json.dump(rows, open(os.path.join(out, repo.replace("/", "_") + ".json"), "w"), ensure_ascii=False, indent=1)
    finally:
        p.terminate(); p.wait()
open(os.path.join(out, "report.md"), "w", encoding="utf-8").write("\n".join(report))
