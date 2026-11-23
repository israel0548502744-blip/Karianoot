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


def screen():
    """(width, height) of the emulator's screen in pixels."""
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size", check=False))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def tap_scrolled(label, tries=10):
    """Like tap, but scrolls down (a long screen) until [label] shows. The swipe is relative to the screen size."""
    w, h = screen()
    for _ in range(tries):
        c = find(label, wait=3)
        if c:
            adb("shell", "input", "tap", str(c[0]), str(c[1])); time.sleep(1.5); return
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.62)), str(w // 2), str(int(h * 0.3)), "400"); time.sleep(1)
    raise RuntimeError(f"not on screen even after scrolling: {label}")


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
step("check_updates", lambda: (tap_scrolled("בדיקת עדכונים"), time.sleep(5)))
step("pronounce_dialog", lambda: (tap_scrolled("תיקוני הגייה"), adb("shell", "input", "keyevent", "4"), time.sleep(1)))
step("tab_studio_effects", lambda: (tap("סטודיו"), tap_scrolled("אפקט / הפסקה"), adb("shell", "input", "keyevent", "4"), time.sleep(1)))
step("templates_dialog", lambda: (tap_scrolled("תבניות ופתיחים"), adb("shell", "input", "keyevent", "4"), time.sleep(1)))
step("tab_studio", lambda: tap("סטודיו"))
step("rotate_and_back", lambda: (adb("shell", "settings", "put", "system", "user_rotation", "1"), time.sleep(3),
                                 adb("shell", "settings", "put", "system", "user_rotation", "0"), time.sleep(3)))
step("background_and_back", lambda: (adb("shell", "input", "keyevent", "3"), time.sleep(2),
                                     adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"), time.sleep(4)))

if narrate:
    def download():
        tap("מודלים")
        tap("הורד", wait=20)          # the first model on the list: the pronunciation engine (312 MB)
        t0 = time.time()
        while time.time() - t0 < 1500:
            if any("מותקן" in t for t, _ in nodes()):
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
    # Narrating is not tried here: the emulator runs the arm64 engines through a translator that lacks some ARMv8.1
    # instructions (SIGILL "Undefined instruction" in ndk_translation, Nov 20). The engines are tested on the JVM / host.
    step("download_models", download)

log = adb("logcat", "-d", "-b", "crash", check=False) + adb("logcat", "-d", "*:E", check=False)
open(os.path.join(out, "logcat_errors.txt"), "w").write(log)
fatal = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or ("ANR in" in l and PKG in l)]
for l in fatal: print("CRASH", l)
bad = [s for s in steps if not s[1]]
print(f"{len(steps)} steps, {len(bad)} failed, {len(fatal)} crash lines")
sys.exit(1 if bad or fatal else 0)
