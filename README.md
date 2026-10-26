# Yara AI v2 - Android + Windows + optional backend (all free tools)

Malayalam / Manglish / English companion (also Hindi, Tamil, Arabic) with voice, memory, photo + screen understanding,
reminders, job search, coding/analytics help and interview practice.

## 1. Get the APK (free, no Android Studio)
1. Create a free GitHub account -> New repository -> upload **the contents of this folder** (so `app/`, `build.gradle.kts`, `.github/` are at the repo root).
2. Open the **Actions** tab -> **Build Yara APK** -> *Run workflow*. After ~5-8 minutes download the **Yara-APK** artifact (unzip -> `app-debug.apk`).
3. Copy the APK to your phone and install it (allow "install unknown apps").
4. If the build fails, open the failed step and send me the red error text - I will fix it. *(The Kotlin code could not be compiled in my sandbox, so the first build is the real test.)*

## 2. Get the Windows app
Same repo -> Actions -> **Build Yara for Windows** -> download `YaraAI.exe`. (Or: `pip install -r windows/requirements.txt` then `python windows/yara_desktop.py`.)

## 3. Add the free brain (2 minutes)
- **Gemini** (best: understands photos/screens): key at https://aistudio.google.com -> Settings tab -> provider *Gemini* -> paste key.
- **Groq** (fast, text only): key at https://console.groq.com -> provider *Groq / OpenAI-compatible*.
- Model names change often: if you see "Model name not found", type a current model name in Settings (default `gemini-flash-latest`).
- Free tiers have daily limits and the provider may use your messages to improve its models. Don't send secrets.

## What works
| Feature | How |
|---|---|
| Chat + personality, mixed Malayalam/English | LLM + system prompt |
| Voice in/out (5 languages) | Android recognizer + TTS (Windows: Google STT + Edge neural voices) |
| Long-term memory | Yara saves facts herself; view/delete in **Memory** tab |
| Camera / gallery image understanding | 📷 / 🖼 buttons (Gemini) |
| Screen understanding | 👁 Screen: Yara goes to background, reads the text of the app you are in, answers (spoken) |
| Phone actions (tap, type, scroll, open app) | Accessibility Service, **off by default**, "Allow screen actions" in Settings, risky taps (pay/delete...) skipped |
| Reminders | AlarmManager notifications (lost if phone reboots) |
| Jobs | Paste CV in Settings -> "find jobs for me" -> Remotive + Arbeitnow listings with links |
| Interview **practice** | 🎯 mode: mock interviewer, feedback, sample answers |
| Data analytics / coding / ML help | 📊 Work mode |
| Windows PC actions | open app/URL, paste text, scroll; **asks your permission each time** |
| Shared memory Android <-> Windows | optional backend (below) |

## Not included (on purpose / platform limits)
- Live answers to a real interviewer through an overlay - unfair in real interviews; use practice mode instead.
- Silent auto-apply on job portals - portals block bots and need your login; Yara finds jobs and drafts cover letters, you click apply.
- Always-on wake word ("Hey Yara") - needs a wake-word engine key; can be added later. Use **Hands-free conversation** meanwhile.
- Android does not allow secret background recording or screen capture. Everything happens when you press a button.

## Optional: backend for shared memory (free)
Runs the same brain on a server so the key stays off the phone and Android + Windows share memories.
- Env vars: `LLM_PROVIDER` (gemini|openai), `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL`, and **`YARA_ALLOWED_KEYS`** = comma-separated sync keys (each >= 12 characters, e.g. a long random password). Without it the server refuses chats.
- Run locally: `cd backend && pip install -r requirements.txt && uvicorn main:app --host 0.0.0.0 --port 8000`.
- Free hosting: Render (`backend/render.yaml`) - note its free disk is wiped on redeploy/sleep, so memories can reset. For permanent memory run it on your own laptop and expose it with a free Cloudflare Tunnel.
- In the apps: Settings -> Backend URL + the same Sync key.
- Use https for any non-local URL (the APK allows http only so LAN testing works).

## Privacy
Settings, chat, memory and keys are stored on your phone/PC. Text, photos and screen text you send go to the AI provider you chose (and to your backend if set). Yara never stores passwords/OTP/card-like numbers as memories.
