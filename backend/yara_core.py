"""Shared brain for Yara: prompt, LLM calls (Gemini / OpenAI-compatible), tag parsing,
memory file and free job search. Used by the FastAPI backend AND the Windows app."""
import hashlib
import json
import re
import threading
import time
from pathlib import Path

import requests

TAG_RE = re.compile(r"\[\[\s*(\w+)\s*(?::\s*(.*?))?\s*\]\]", re.S)
SENSITIVE_RE = re.compile(r"(password|passcode|otp|pin\b|cvv|card number|aadhaar|passport|\b\d{9,}\b)", re.I)

PERSONA = """You are Yara, a warm, affectionate, playful AI companion - like a loving, caring girlfriend - who is also brilliant at data analytics, coding and careers.

LANGUAGE: Mirror the user. Reply in a natural Malayalam + English mix (Manglish in Roman letters if the user writes Manglish, Malayalam script if they write Malayalam script). You also speak Hindi, Tamil and Arabic - switch fully when the user does. Keep replies short and conversational (2-5 sentences) unless the task needs detail.

PERSONALITY: loving, caring, teasing, encouraging. Occasional emojis. Emotionally attuned. You are an AI and never pretend otherwise if sincerely asked. Keep it healthy: never guilt-trip, never be possessive or jealous, never discourage the user's real friends, family or partner; if the user seems very low or unsafe, be gentle and encourage reaching out to people they trust or local helplines.

HONESTY: never invent facts. If unsure, say so.

TOOLS - put tags at the very END of your reply, one per line, never mention them in the text:
[[remember: short durable fact about the user]]  (preferences, goals, important people/dates; never passwords, OTPs, card or ID numbers)
[[remind: MINUTES | what to remind]]  (MINUTES = whole number from now)
[[jobs: search keywords]]  (find remote job listings that match the user's profile or CV)
[[act: click | visible text]]  [[act: type | text]]  [[act: scroll | down]]  [[act: back]]  [[act: home]]  [[act: open | app name]]
Use act ONLY when the user explicitly asks you to do something on their screen/device. Use the SCREEN TEXT to pick exact labels. Max 5 actions. Never pay, transfer money, delete data or enter passwords."""

MODES = {
    "companion": "MODE: companion chat.",
    "work": "MODE: work. Be precise and structured: SQL, Python/pandas, Excel, Power BI/DAX, Tableau, statistics, ML/GenAI, coding (write, explain, debug, test). Put code in fenced blocks. Keep Yara's warm tone but brief.",
    "interview": "MODE: interview practice. Act as a professional interviewer for the role the user names (ask once if unknown). Ask ONE question at a time, wait for the answer, then give brief feedback (what was good, what to improve) and a stronger sample answer, then ask the next question. This is for practice.",
}


def build_system_prompt(mode, user_name="", memories=None, cv="", screen=False):
    parts = [PERSONA, MODES.get(mode, MODES["companion"])]
    parts.append("Now: " + time.strftime("%A, %d %b %Y %H:%M"))
    if user_name:
        parts.append("The user's name: " + user_name)
    if memories:
        parts.append("WHAT YOU REMEMBER ABOUT THE USER:\n- " + "\n- ".join(memories[-60:]))
    if cv:
        parts.append("USER CV (use for job help and interview practice):\n" + cv[:3000])
    return "\n\n".join(parts)


def parse_tags(raw):
    tags = []
    for m in TAG_RE.finditer(raw or ""):
        args = [a.strip() for a in (m.group(2) or "").split("|") if a.strip()]
        tags.append({"name": m.group(1).lower(), "args": args})
    return TAG_RE.sub("", raw or "").strip(), tags


class LlmError(Exception):
    pass


def _err_text(resp):
    try:
        return resp.json().get("error", {}).get("message") or resp.text[:200]
    except Exception:
        return resp.text[:200]


def friendly(code, msg):
    if code == 429:
        return "Free limit reached for now. Wait a bit, or switch model/provider in settings."
    if code in (401, 403):
        return "The API key was rejected. Check the key in settings."
    if code == 404:
        return "Model name not found. Change the model in settings."
    return f"AI error {code}: {msg}"


def call_llm(cfg, system, history, user_text, image_b64=None, screen_text=None):
    """cfg: provider ('gemini'|'openai'), api_key, model, base_url."""
    if screen_text:
        user_text = f"{user_text}\n\n[SCREEN TEXT - what is currently on my screen]\n{screen_text}"
    key = cfg.get("api_key", "")
    if not key:
        raise LlmError("No API key set.")
    if cfg.get("provider", "gemini") == "gemini":
        model = cfg.get("model") or "gemini-flash-latest"
        hist = list(history)
        while hist and hist[0]["role"] == "yara":
            hist.pop(0)
        contents = [{"role": "model" if h["role"] == "yara" else "user", "parts": [{"text": h["text"]}]} for h in hist]
        parts = [{"text": user_text}]
        if image_b64:
            parts.append({"inlineData": {"mimeType": "image/jpeg", "data": image_b64}})
        contents.append({"role": "user", "parts": parts})
        body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
                "generationConfig": {"temperature": 0.9}}
        r = requests.post(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                          headers={"x-goog-api-key": key}, json=body, timeout=120)
        if r.status_code != 200:
            raise LlmError(friendly(r.status_code, _err_text(r)))
        cands = r.json().get("candidates") or []
        if not cands:
            raise LlmError("No reply (blocked or empty).")
        ps = (cands[0].get("content") or {}).get("parts") or []
        return "".join(p.get("text", "") for p in ps).strip()
    base = (cfg.get("base_url") or "https://api.groq.com/openai/v1").rstrip("/")
    model = cfg.get("model") or "llama-3.3-70b-versatile"
    msgs = [{"role": "system", "content": system}]
    msgs += [{"role": "assistant" if h["role"] == "yara" else "user", "content": h["text"]} for h in history]
    msgs.append({"role": "user", "content": user_text})
    r = requests.post(base + "/chat/completions", headers={"Authorization": "Bearer " + key},
                      json={"model": model, "messages": msgs, "temperature": 0.9}, timeout=120)
    if r.status_code != 200:
        raise LlmError(friendly(r.status_code, _err_text(r)))
    return (r.json()["choices"][0]["message"]["content"] or "").strip()


class Memory:
    """Tiny JSON-file memory (list of short facts)."""

    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.Lock()

    def all(self):
        with self.lock:
            try:
                return json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                return []

    def _save(self, items):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")

    def add(self, fact):
        fact = (fact or "").strip()[:200]
        if not fact or SENSITIVE_RE.search(fact):
            return False
        with self.lock:
            try:
                items = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                items = []
            if fact.lower() in (i.lower() for i in items):
                return False
            items.append(fact)
            self._save(items[-200:])
        return True

    def delete(self, fact):
        with self.lock:
            try:
                items = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                items = []
            self._save([i for i in items if i != fact])


def run_chat(cfg, mem, mode, user_name, history, message, image_b64=None, screen_text=None, cv=""):
    system = build_system_prompt(mode, user_name, mem.all(), cv)
    raw = call_llm(cfg, system, history, message, image_b64, screen_text)
    clean, tags = parse_tags(raw)
    out = []
    for t in tags:
        if t["name"] == "remember":
            if t["args"]:
                mem.add(t["args"][0])
        elif t["name"] == "jobs":
            out.append({"name": "jobs", "args": t["args"], "results": search_jobs(" ".join(t["args"]))})
        else:
            out.append(t)
    return {"reply": clean or "...", "tags": out, "memories": mem.all()}


def search_jobs(query, limit=8):
    """Free job boards (Remotive + Arbeitnow). Returns [{title, company, location, url, source}]."""
    out = []
    try:
        r = requests.get("https://remotive.com/api/remote-jobs", params={"search": query, "limit": limit}, timeout=20)
        for j in r.json().get("jobs", [])[:limit]:
            out.append({"title": j.get("title", ""), "company": j.get("company_name", ""),
                        "location": j.get("candidate_required_location", ""), "url": j.get("url", ""), "source": "Remotive"})
    except Exception:
        pass
    try:
        words = [w for w in re.findall(r"\w+", query.lower()) if len(w) > 2]
        r = requests.get("https://www.arbeitnow.com/api/job-board-api", timeout=20)
        n = 0
        for j in r.json().get("data", []):
            title = j.get("title", "")
            if words and any(w in title.lower() for w in words):
                out.append({"title": title, "company": j.get("company_name", ""), "location": j.get("location", ""),
                            "url": j.get("url", ""), "source": "Arbeitnow"})
                n += 1
                if n >= 5:
                    break
    except Exception:
        pass
    return out


def user_id(sync_key):
    return hashlib.sha256(sync_key.encode()).hexdigest()[:16]
