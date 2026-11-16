#!/usr/bin/env python3
"""Pre-launch check: every model file the app downloads (Catalog.kt) exists at its pinned URL, has the pinned size and,
where pinned, the pinned sha256. Prints each file's sha256 so unpinned ones can be pinned.

Usage: check_downloads.py <Catalog.kt> <out.json>
"""
import hashlib, json, re, sys, time, urllib.request

src = open(sys.argv[1], encoding="utf-8").read()
const = dict(re.findall(r'const val (\w+) = "([^"]+)"', src))
blocks = re.split(r"\n    val (\w+) = ModelInfo\(", src)
results, bad = [], 0
for name, body in zip(blocks[1::2], blocks[2::2]):
    body = body[: body.find("\n    )\n")] if "\n    )\n" in body else body
    repo = (re.search(r'\brepo = "([^"]+)"', body) or [None, ""])[1]
    rev = (re.search(r'\brevision = "([^"]+)"', body) or [None, ""])[1]
    for m in re.finditer(r'ModelFile\(([^()]*(?:\([^()]*\)[^()]*)*)\)', body):
        args = m.group(1)
        path = re.match(r'\s*"?([^",]+)"?', args).group(1).strip()
        path = const.get(path, path)
        size = int(re.search(r",\s*([\d_]+)", args).group(1).replace("_", ""))
        f_repo = (re.search(r'repo = "([^"]+)"', args) or [None, repo])[1]
        f_rev = (re.search(r'revision = "([^"]+)"', args) or [None, rev])[1]
        url = re.search(r'url = "([^"]+)"', args)
        url = url.group(1).replace("$MAGPIE_FILE", const.get("MAGPIE_FILE", "")) if url else \
            f"https://huggingface.co/{f_repo}/resolve/{f_rev}/{path.split('@')[-1]}"
        sha_pin = (re.search(r'sha256 = "([0-9a-f]{64})"', args) or [None, None])[1]
        row = {"model": name, "path": path, "url": url, "size_pinned": size, "sha_pinned": sha_pin}
        t0 = time.time()
        try:
            h, n = hashlib.sha256(), 0
            req = urllib.request.Request(url, headers={"User-Agent": "karianoot-release-check"})
            with urllib.request.urlopen(req, timeout=120) as r:
                for b in iter(lambda: r.read(1 << 20), b""):
                    h.update(b); n += len(b)
            row.update(size=n, sha256=h.hexdigest(), seconds=round(time.time() - t0, 1))
            row["ok"] = n == size and (sha_pin is None or sha_pin == row["sha256"])
        except Exception as e:
            row.update(ok=False, error=repr(e)[:300])
        bad += not row["ok"]
        results.append(row)
        print(("OK  " if row["ok"] else "BAD ") + f"{name:<8} {path:<40} {row.get('size')} / {size} "
              f"sha {row.get('sha256', '-')[:16]} {'(pinned)' if sha_pin else '(not pinned)'} {row.get('error', '')}", flush=True)
json.dump({"bad": bad, "files": results}, open(sys.argv[2], "w"), indent=1)
print(f"{len(results)} files, {bad} bad")
sys.exit(1 if bad else 0)
