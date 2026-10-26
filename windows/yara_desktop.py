"""Yara for Windows - desktop companion (Tkinter). Same brain as the Android app.

Features: chat, voice in/out (Malayalam/English/Hindi/Tamil/Arabic), screenshot understanding,
confirmation-gated PC actions (open apps/URLs, type, hotkeys, list folders), reminders, job search,
memory (local file, or shared with Android through the backend)."""
import base64
import ctypes
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import messagebox, scrolledtext

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
import requests  # noqa: E402
import yara_core as core  # noqa: E402

try:
    import speech_recognition as sr
except Exception:
    sr = None
try:
    import edge_tts
    import asyncio
except Exception:
    edge_tts = None
try:
    from PIL import ImageGrab
except Exception:
    ImageGrab = None
try:
    import pyautogui
    import pyperclip
except Exception:
    pyautogui = None
    pyperclip = None

APP_DIR = Path(os.getenv("APPDATA", str(Path.home()))) / "YaraAI"
APP_DIR.mkdir(parents=True, exist_ok=True)
CFG_FILE = APP_DIR / "config.json"
DEFAULT_CFG = {"provider": "gemini", "api_key": "", "model": "", "base_url": "", "user_name": "", "cv": "",
               "backend_url": "", "sync_key": "", "speech_lang": "ml-IN", "voice_reply": True, "mode": "companion"}
VOICES = {"ml": "ml-IN-SobhanaNeural", "hi": "hi-IN-SwaraNeural", "ta": "ta-IN-PallaviNeural",
          "ar": "ar-SA-ZariyahNeural", "en": "en-IN-NeerjaNeural"}


def load_cfg():
    cfg = dict(DEFAULT_CFG)
    try:
        cfg.update(json.loads(CFG_FILE.read_text(encoding="utf-8")))
    except Exception:
        pass
    return cfg


def save_cfg(cfg):
    CFG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=1), encoding="utf-8")


def script_lang(text):
    counts = {"ml": 0, "hi": 0, "ta": 0, "ar": 0}
    for ch in text:
        o = ord(ch)
        if 0x0D00 <= o <= 0x0D7F: counts["ml"] += 1
        elif 0x0900 <= o <= 0x097F: counts["hi"] += 1
        elif 0x0B80 <= o <= 0x0BFF: counts["ta"] += 1
        elif 0x0600 <= o <= 0x06FF: counts["ar"] += 1
    best = max(counts, key=counts.get)
    return best if counts[best] >= 3 else "en"


def speakable(text):
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"```[\s\S]*?```", " code omitted ", text)
    text = re.sub(r"[*_#`>~]", "", text)
    return re.sub(r"[\U0001F000-\U0001FFFF\u2600-\u27BF\uFE0F]", "", text).strip()


def play_mp3(path):
    mci = ctypes.windll.winmm.mciSendStringW
    mci("close yara", None, 0, 0)
    mci(f'open "{path}" type mpegvideo alias yara', None, 0, 0)
    mci("play yara wait", None, 0, 0)
    mci("close yara", None, 0, 0)


class YaraApp:
    def __init__(self, root):
        self.root = root
        self.cfg = load_cfg()
        self.mem = core.Memory(APP_DIR / "memory.json")
        self.history = []
        self.use_screen = tk.BooleanVar(value=False)
        self.mode = tk.StringVar(value=self.cfg.get("mode", "companion"))
        root.title("Yara AI")
        root.geometry("560x720")
        root.configure(bg="#07080D")
        top = tk.Frame(root, bg="#07080D"); top.pack(fill="x", padx=8, pady=4)
        tk.Label(top, text="YARA", fg="white", bg="#07080D", font=("Segoe UI", 18, "bold")).pack(side="left")
        for label, val in (("Companion", "companion"), ("Work & code", "work"), ("Interview practice", "interview")):
            tk.Radiobutton(top, text=label, value=val, variable=self.mode, fg="white", bg="#07080D",
                           selectcolor="#3A1620", activebackground="#07080D", activeforeground="white").pack(side="left", padx=4)
        tk.Button(top, text="Settings", command=self.settings).pack(side="right")
        tk.Button(top, text="Memory", command=self.show_memory).pack(side="right", padx=4)
        self.log = scrolledtext.ScrolledText(root, wrap="word", bg="#11131B", fg="white", font=("Segoe UI", 11), state="disabled")
        self.log.pack(fill="both", expand=True, padx=8, pady=4)
        self.log.tag_config("me", foreground="#FF9DB0", justify="right")
        self.log.tag_config("yara", foreground="#FFFFFF")
        self.log.tag_config("note", foreground="#9AA0B5")
        self.log.tag_config("link", foreground="#6CB6FF", underline=True)
        bar = tk.Frame(root, bg="#07080D"); bar.pack(fill="x", padx=8)
        if sr: tk.Button(bar, text="🎤 Talk", command=self.listen).pack(side="left")
        if ImageGrab: tk.Checkbutton(bar, text="👁 Look at my screen", variable=self.use_screen, fg="white", bg="#07080D",
                                     selectcolor="#3A1620", activebackground="#07080D").pack(side="left", padx=6)
        tk.Checkbutton(bar, text="🔊 Voice", variable=tk.BooleanVar(value=self.cfg["voice_reply"]),
                       command=self.toggle_voice, fg="white", bg="#07080D", selectcolor="#3A1620",
                       activebackground="#07080D").pack(side="left")
        row = tk.Frame(root, bg="#07080D"); row.pack(fill="x", padx=8, pady=6)
        self.entry = tk.Entry(row, font=("Segoe UI", 12)); self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Return>", lambda e: self.send())
        tk.Button(row, text="Send", command=self.send).pack(side="left", padx=4)
        self.say("Hi ❤️ ഞാൻ Yara ആണ്. എന്താണ് വിശേഷം?", "yara")
        if not self.cfg["api_key"] and not self.cfg["backend_url"]:
            self.say("First open Settings and paste a free Gemini or Groq API key.", "note")

    # ---- UI helpers
    def say(self, text, tag="yara"):
        self.log.configure(state="normal")
        for part in re.split(r"(https?://\S+)", text + "\n\n"):
            if part.startswith("http"):
                start = self.log.index("end-1c")
                self.log.insert("end", part, ("link",))
                url = part
                tagname = f"l{start}"
                self.log.tag_add(tagname, start, "end-1c")
                self.log.tag_bind(tagname, "<Button-1>", lambda e, u=url: webbrowser.open(u))
            else:
                self.log.insert("end", part, (tag,))
        self.log.configure(state="disabled"); self.log.see("end")

    def toggle_voice(self):
        self.cfg["voice_reply"] = not self.cfg["voice_reply"]; save_cfg(self.cfg)

    def ui(self, fn, *a):
        self.root.after(0, lambda: fn(*a))

    # ---- settings / memory windows
    def settings(self):
        w = tk.Toplevel(self.root); w.title("Settings"); w.geometry("460x560")
        fields = {}
        def add(label, key, secret=False, height=1):
            tk.Label(w, text=label).pack(anchor="w", padx=8)
            if height == 1:
                e = tk.Entry(w, show="*" if secret else ""); e.insert(0, self.cfg.get(key, "")); e.pack(fill="x", padx=8)
            else:
                e = tk.Text(w, height=height); e.insert("1.0", self.cfg.get(key, "")); e.pack(fill="x", padx=8)
            fields[key] = e
        tk.Label(w, text="Provider: gemini  or  openai (Groq etc.)").pack(anchor="w", padx=8)
        add("Provider", "provider"); add("API key (free: aistudio.google.com / console.groq.com)", "api_key", True)
        add("Model (blank = default)", "model"); add("Base URL (openai-compatible only)", "base_url")
        add("Your name", "user_name"); add("Speech language (ml-IN, en-IN, hi-IN, ta-IN, ar-SA)", "speech_lang")
        add("Backend URL (optional, shares memory with Android)", "backend_url"); add("Sync key", "sync_key", True)
        add("CV text (for jobs / interview practice)", "cv", height=6)
        def ok():
            for k, e in fields.items():
                self.cfg[k] = (e.get("1.0", "end") if isinstance(e, tk.Text) else e.get()).strip()
            save_cfg(self.cfg); w.destroy()
        tk.Button(w, text="Save", command=ok).pack(pady=8)

    def show_memory(self):
        w = tk.Toplevel(self.root); w.title("Yara remembers"); w.geometry("420x420")
        lb = tk.Listbox(w); lb.pack(fill="both", expand=True, padx=8, pady=8)
        for m in self.mem.all(): lb.insert("end", m)
        def delete():
            sel = lb.curselection()
            if sel:
                self.mem.delete(lb.get(sel[0])); lb.delete(sel[0])
        tk.Button(w, text="Delete selected", command=delete).pack(pady=4)

    # ---- chat
    def listen(self):
        def work():
            r = sr.Recognizer()
            try:
                with sr.Microphone() as src:
                    self.ui(self.say, "🎤 Listening…", "note")
                    r.adjust_for_ambient_noise(src, 0.3)
                    audio = r.listen(src, timeout=8, phrase_time_limit=20)
                text = r.recognize_google(audio, language=self.cfg["speech_lang"])
                self.ui(lambda: (self.entry.delete(0, "end"), self.entry.insert(0, text), self.send()))
            except Exception as e:
                self.ui(self.say, f"(couldn't hear: {e})", "note")
        threading.Thread(target=work, daemon=True).start()

    def send(self):
        text = self.entry.get().strip()
        if not text: return
        self.entry.delete(0, "end")
        self.say(text, "me")
        screen = self.use_screen.get(); self.use_screen.set(False)
        threading.Thread(target=self.think, args=(text, screen), daemon=True).start()

    def grab_screenshot(self):
        self.ui(self.root.withdraw); time.sleep(0.7)
        img = ImageGrab.grab()
        self.ui(self.root.deiconify)
        img.thumbnail((1600, 1600))
        buf = io.BytesIO(); img.convert("RGB").save(buf, "JPEG", quality=80)
        return base64.b64encode(buf.getvalue()).decode()

    def think(self, text, screen):
        try:
            image = self.grab_screenshot() if screen else None
            hist = self.history[-14:]
            if self.cfg["backend_url"]:
                r = requests.post(self.cfg["backend_url"].rstrip("/") + "/chat", headers={"X-Sync-Key": self.cfg["sync_key"]},
                                  json={"message": text, "mode": self.mode.get(), "user_name": self.cfg["user_name"], "cv": self.cfg["cv"],
                                        "image_b64": image, "history": hist}, timeout=120)
                if r.status_code != 200:
                    raise core.LlmError(r.json().get("detail", r.text[:200]) if r.headers.get("content-type", "").startswith("application/json") else r.text[:200])
                res = r.json()
            else:
                cfg = {"provider": self.cfg["provider"], "api_key": self.cfg["api_key"], "model": self.cfg["model"], "base_url": self.cfg["base_url"]}
                res = core.run_chat(cfg, self.mem, self.mode.get(), self.cfg["user_name"], hist, text, image, None, self.cfg["cv"])
            reply = res["reply"]
            self.history += [{"role": "user", "text": text}, {"role": "yara", "text": reply}]
            self.ui(self.say, reply, "yara")
            if self.cfg["voice_reply"]: threading.Thread(target=self.speak, args=(reply,), daemon=True).start()
            self.handle_tags(res.get("tags", []))
        except Exception as e:
            self.ui(self.say, f"⚠️ {e}", "note")

    # ---- voice out (Microsoft Edge neural voices, free)
    def speak(self, text):
        if not edge_tts: return
        t = speakable(text)[:3000]
        if not t: return
        try:
            path = os.path.join(tempfile.gettempdir(), "yara_tts.mp3")
            asyncio.run(edge_tts.Communicate(t, VOICES[script_lang(t)]).save(path))
            play_mp3(path)
        except Exception:
            pass

    # ---- tools
    def handle_tags(self, tags):
        acts = []
        for t in tags:
            n, a = t["name"], t.get("args", [])
            if n == "remind" and len(a) >= 1 and a[0].isdigit():
                mins, what = int(a[0]), (a[1] if len(a) > 1 else "Reminder")
                threading.Timer(mins * 60, lambda w=what: self.ui(lambda: messagebox.showinfo("Yara ⏰", w))).start()
                self.ui(self.say, f"⏰ Reminder set in {mins} min: {what} (only while Yara is open)", "note")
            elif n == "jobs":
                jobs = t.get("results") or core.search_jobs(" ".join(a))
                lines = [f"• {j['title']} - {j['company']} ({j['location']})\n  {j['url']}" for j in jobs] or ["No jobs found."]
                self.ui(self.say, "🔎 Jobs - click a link to open and apply yourself:\n" + "\n".join(lines), "note")
            elif n == "act":
                acts.append(a)
        if acts: self.ui(self.run_actions, acts)

    def run_actions(self, acts):
        if not pyautogui:
            self.say("Install pyautogui + pyperclip to let me act on your PC.", "note"); return
        desc = "\n".join("• " + " ".join(a) for a in acts[:5])
        if not messagebox.askyesno("Yara wants to do this on your PC", desc + "\n\nAllow?"):
            self.say("Okay, I won't do it.", "note"); return
        for a in acts[:5]:
            kind = a[0].lower() if a else ""
            arg = a[1] if len(a) > 1 else ""
            try:
                if kind == "open":
                    if re.search(r"[&|<>^%\r\n]", arg):
                        self.say(f"Skipped unsafe app name: {arg}", "note"); continue
                    if arg.startswith("http"): webbrowser.open(arg)
                    else: subprocess.Popen(["cmd", "/c", "start", "", arg], shell=False)
                elif kind == "type" and pyperclip:
                    pyperclip.copy(arg); pyautogui.hotkey("ctrl", "v")
                elif kind == "scroll":
                    pyautogui.scroll(-600 if arg.lower() != "up" else 600)
                elif kind == "back":
                    pyautogui.hotkey("alt", "left")
                elif kind == "home":
                    pyautogui.hotkey("win", "d")
                time.sleep(1)
            except Exception as e:
                self.say(f"Couldn't do {kind}: {e}", "note")


if __name__ == "__main__":
    root = tk.Tk()
    YaraApp(root)
    root.mainloop()
