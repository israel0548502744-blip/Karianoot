#!/usr/bin/env python3
"""Smoke test of the real APK on an Android emulator (emulator.yml): install, open, walk every tab and dialog,
then (optionally) download the pronunciation engine + Blue 2.5 through the app and make one narration.
Any crash (FATAL EXCEPTION / app process gone / ANR) fails the run. Screenshots go to out/.

Usage: emu_smoke.py <apk> <out dir> [--narrate]
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

apk, out = sys.argv[1], sys.argv[2]
narrate = "--narrate" in sys.argv
PKG = "com.karianoot.app"
os.makedirs(out, exist_ok=True)
steps = []


def adb(*a, check=True, timeout=120):
    r = subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(a)}: {r.stdout}{r.stderr}")
    return r.stdout


def shot(name):
    with open(os.path.join(out, f"{len(steps):02d}_{name}.png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    x = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    try:
        root = ET.fromstring(x[x.index("<"):])
    except Exception:
        return []
    res = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            res.append(((n.get("text") or "") + "|" + (n.get("content-desc") or ""), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return res


def find(label, wait=15):
    t0 = time.time()
    while time.time() - t0 < wait:
        for txt, c in nodes():
            if label in txt:
                return c
        time.sleep(1)
    return None


def tap(label, wait=15):
    c = find(label, wait)
    if not c:
        raise RuntimeError(f"not on screen: {label}")
    adb("shell", "input", "tap", str(c[0]), str(c[1]))
    time.sleep(1.5)


def alive():
    return adb("shell", "pidof", PKG, check=False).strip() != ""


def step(name, fn):
    t0 = time.time()
    try:
        fn(); ok = alive(); err = "" if ok else "the app process is gone (crash)"
    except Exception as e:
        ok, err = False, str(e)[:300]
    shot(name)
    steps.append((name, ok, round(time.time() - t0, 1), err))
    print(("OK  " if ok else "BAD ") + f"{name} ({steps[-1][2]}s) {err}", flush=True)
    return ok


adb("logcat", "-c", check=False)
print(adb("install", "-r", "-g", apk, timeout=300).strip())
step("open", lambda: (adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity"), time.sleep(6)))
step("tab_library", lambda: tap("ההקלטות"))
step("tab_models", lambda: tap("מודלים"))
step("tab_settings", lambda: tap("הגדרות"))
step("check_updates", lambda: (tap("בדיקת עדכונים"), time.sleep(5)))
step("pronounce_dialog", lambda: (tap("תיקוני הגייה"), adb("shell", "input", "keyevent", "4"), time.sleep(1)))
step("tab_studio", lambda: tap("סטודיו"))
step("templates_dialog", lambda: (tap("תבניות"), adb("shell", "input", "keyevent", "4"), time.sleep(1)))
step("rotate_and_back", lambda: (adb("shell", "settings", "put", "system", "user_rotation", "1"), time.sleep(3),
                                 adb("shell", "settings", "put", "system", "user_rotation", "0"), time.sleep(3)))
step("background_and_back", lambda: (adb("shell", "input", "keyevent", "3"), time.sleep(2),
                                     adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"), time.sleep(4)))

if narrate:
    def download():
        tap("מודלים")
        for _ in range(2):           # the pronunciation engine, then Blue 2.5 (both have a "הורד" button)
            tap("הורד", wait=20)
        t0 = time.time()
        while time.time() - t0 < 1500:
            if sum("מותקן" in t for t, _ in nodes()) >= 2:
                return
            time.sleep(15)
        raise RuntimeError("downloads did not finish in 25 minutes")
    def make():
        tap("סטודיו")
        c = find("כתבו או הדביקו", wait=10) or find("תווים", wait=5)
        if not c: raise RuntimeError("no text box")
        adb("shell", "input", "tap", str(c[0]), str(c[1] - 150))
        time.sleep(1)
        adb("shell", "input", "text", "shalom")     # ASCII through adb; the app reads it out through the G2P path
        adb("shell", "input", "keyevent", "4")
        tap("צור קריינות")
        t0 = time.time()
        while time.time() - t0 < 900:
            if not alive():
                raise RuntimeError("crashed while narrating")
            if find("נוצר ב-", wait=2):
                return
            time.sleep(10)
        raise RuntimeError("no narration after 15 minutes")
    step("download_models", download) and step("narrate", make)

log = adb("logcat", "-d", "-b", "crash", check=False) + adb("logcat", "-d", "*:E", check=False)
open(os.path.join(out, "logcat_errors.txt"), "w").write(log)
fatal = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or ("ANR in" in l and PKG in l)]
for l in fatal: print("CRASH", l)
bad = [s for s in steps if not s[1]]
print(f"{len(steps)} steps, {len(bad)} failed, {len(fatal)} crash lines")
sys.exit(1 if bad or fatal else 0)
