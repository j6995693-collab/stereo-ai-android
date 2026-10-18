# Stereo AI (Open-Source Sanitized Edition)

[中文](README.md) | [English](README.en.md)

An open-source personal AI assistant for Android: local-first, user-owned keys, replaceable
models, auditable tools, and extensible workflows. A WebView interface plus an on-device agent
engine puts chat, tool calling, file I/O, web research, scheduled tasks, voice calls, vision,
maps, and office-document workflows into a single phone.

This repository is the **sanitized open-source edition**: it contains no personal information,
API keys, tokens, device serial numbers, or private business data. Every key is applied for and
entered by the user.

## Why We Built This: No Capital Control Over Personal Agents

**I refuse to live in an era where personal agents are controlled by capital.**

A personal agent is not an ordinary app. It enters our files, calendars, memories, and workflows;
it calls tools, connects services, and makes judgments on our behalf. Whoever controls the
personal agent controls the gateway to our digital lives. If that gateway is held by a handful of
capital-backed platforms, users lose more than a product choice—they lose control over their own
data, memory, and actions.

Our position is clear:

**Our personal agents must be developed by ourselves, within the open-source community, under
legal regulation. They must not belong to any company, any capital bloc, or become a tool for
platforms to control users.**

This is not a slogan. It is the starting point of Stereo AI:

- Because we reject capital control, the agent runs on the user's own phone, not in the project's cloud.
- Because we reject black boxes, we open the code, tool boundaries, permission approvals, and build process.
- Because we reject lock-in, models, engines, skills, and workflows are all replaceable.
- Because we reject unconstrained "autonomy," we insist that personal agents operate under law and
  regulation, and remain auditable, accountable, and exitable.
- Because we reject turning users into traffic and billing targets, we insist on user-owned keys,
  data, and memory.

Stereo AI is an attempt. It is not perfect, but it aims to prove one thing:

**Personal agents do not have to be built on capital-controlled platforms. They can be built on
user-owned devices, open-source code, and community governance.**

---

## 1. Architecture

```
WebView (assets/index.html)
        │  HTTP 127.0.0.1:8765 (loopback, random-token auth)
        ▼
HttpServer ──► codex app-server (libcodex.so, JSON-RPC over stdio)
        │              │
        │              ├─ MCP tool layer 127.0.0.1:8767 (files/search/commands/MCP client)
        │              └─ ResponseProxy 127.0.0.1:8768 (Responses/Anthropic ↔ Chat Completions translation)
        ▼
Upstream model APIs (Alibaba DashScope / Zhipu / DeepSeek / Kimi / custom compatible endpoints)
```

- The engine binary is not distributed with this repository. At build time it is packaged into
  `lib/arm64-v8a/libcodex.so` through `CODEX_BIN`.
- All local ports bind only to `127.0.0.1`; the token is encrypted with Android Keystore.

---

## 2. Directory Structure

```
app/
  AndroidManifest.xml
  com/stereo/ai/            Java source (Activity/Service/HTTP/tool layer/scheduler)
  res/                      Icons and network configuration
  assets/
    index.html              Front-end interface
    agents_md.txt           System prompt and capability description injected into the model
    bundle/
      guides/               Workflow specifications: PPT / PDF / Word / AI maps
      skills/agent-reach/   Web-research skill (third-party, see section 10)
      tools/                Map coordinate gate (mapgate.sh / polydecode.awk)
      scan/                 Generic scheduled-search template and xlsx toolchain
      licenses/             Third-party licenses and NOTICE (shipped with the APK)
build_apk.sh                Gradle-free build script
```

---

## 3. Build

### 3.1 Requirements

| Dependency | Version / Notes |
|------------|-----------------|
| JDK | 8 or later (`javac`, `keytool`) |
| Android SDK | `build-tools/34.0.0`, `platforms/android-34` |
| codex engine | `codex-package-aarch64-unknown-linux-musl.tar.gz` ([openai/codex Releases](https://github.com/openai/codex/releases), pinned to `rust-v0.156.1`) |
| System | macOS / Linux (the script uses bash + zip + find) |

### 3.2 Build Commands

```bash
# 1. Download and verify the pinned Codex engine (macOS / Linux)
VER=rust-v0.156.1
ASSET=codex-package-aarch64-unknown-linux-musl.tar.gz
curl -LO "https://github.com/openai/codex/releases/download/$VER/$ASSET"
curl -LO "https://github.com/openai/codex/releases/download/$VER/codex-package_SHA256SUMS"
grep " $ASSET\$" codex-package_SHA256SUMS | shasum -a 256 -c -
mkdir -p codex-engine && tar -xzf "$ASSET" -C codex-engine

# 2. Build
export ANDROID_SDK="$HOME/android-build/sdk"        # your SDK root
export JAVA_HOME="$HOME/android-build/jdk/Contents/Home"
export CODEX_BIN="$PWD/codex-engine/bin/codex"

./build_apk.sh
```

Output: `build/StereoAI-public.apk` (self-signed).

Optional environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `OUTPUT_NAME` | `StereoAI-public.apk` | Output filename |
| `KEYSTORE` | `build/release.keystore` | Signing keystore; generated automatically if absent |
| `KEYSTORE_PASS` | `android` | Signing password |
| `BUILD_TOOLS_VER` | highest installed | build-tools version |
| `PLATFORM` | `android-34` | Compile platform |
| `DEBUG` | empty | Non-empty builds debug (`android:debuggable=true`) |

> The self-signed keystore is for local installation and testing only. For public releases or
> long-term upgrades, use your own release keystore and keep it safe. Changing the signing key
> means the new APK cannot overwrite the old one; users must uninstall first.

### 3.3 Install

```bash
adb install -r build/StereoAI-public.apk
```

Minimum Android 8.0 (API 26), target API 34, `arm64-v8a` only.

---

## 4. First Launch and API Key Configuration

Open the app → Settings (top right) → **Models & Keys**.

Four providers are built in. Choose one as the primary model; DashScope is recommended because
voice and vision also use it.

| Provider | Endpoint | Use | Sign-up |
|----------|----------|-----|---------|
| **Alibaba DashScope** | `dashscope.aliyuncs.com/compatible-mode/v1` | Chat/tool calls + **speech recognition + speech synthesis + vision + web search** | <https://bailian.console.aliyun.com/> |
| Zhipu GLM | `open.bigmodel.cn/api/anthropic` | Chat (Anthropic protocol) | <https://open.bigmodel.cn/> |
| DeepSeek | `api.deepseek.com/v1` | Chat | <https://platform.deepseek.com/> |
| Kimi | `api.moonshot.cn/v1` | Chat | <https://platform.moonshot.cn/> |
| Custom | Any OpenAI / Anthropic-compatible endpoint | Chat | Settings → "Add model provider" |

Steps:

1. Create an API key on the provider's platform.
2. Paste it into the provider's "Paste API key" field and save.
3. Tap "Fetch models" to load the model list, or add a model ID manually.
4. Select a model. **The model is fixed once a conversation starts**; switching only affects new
   sessions.

Key storage: AES-GCM encrypted and written to local configuration. If Android Keystore is
unavailable, the app **refuses to save** rather than writing plaintext to disk.

### 4.1 Keys Required per Feature

| Feature | Required key | Default model / endpoint |
|---------|--------------|--------------------------|
| Text chat, tool calls, sub-agents | Any chat provider | Default `qwen3.8-max` |
| Voice messages (speech-to-text) | DashScope | `qwen3-asr-flash` |
| Realtime voice calls (listen and speak) | DashScope | ASR `qwen3-asr-flash` + TTS `cosyvoice-v2` |
| Image understanding / screenshot recognition | DashScope | `qwen3-vl-plus` |
| Web search `web_search` | DashScope | DashScope built-in search model |
| Web fetch `web_fetch` | None | Direct HTTP fetch |
| AMap map generation | AMap platform keys ×3 | See 4.2 |
| Scheduled search tasks | Chat key + DashScope (search) | See section 7 |
| PPT / PDF / Word | No extra key | Desktop-side execution, see section 8 |

> Voice and vision **only use the DashScope key**, because the code calls DashScope's
> `qwen3-asr-flash`, `cosyvoice-v2`, and `qwen3-vl-plus` directly. Other providers handle text
> chat only.

### 4.2 AMap (AI Map Workflow)

Create an application in the [AMap Open Platform console](https://console.amap.com/dev/key/app).
You need **three values**:

| Value | Key type | Purpose |
|-------|----------|---------|
| JS API Key | Web (JS API) | Display maps in the browser/WebView |
| securityJsCode | Paired with the JS key | `window._AMapSecurityConfig` |
| REST API Key | Web service | Geocoding, POI, driving/walking routes |

Configuration options:

- Tell the AI directly: "Here are my AMap JS key / security code / REST key: …; use them for the map."
- Or replace the `<your … key>` placeholders in `app/assets/bundle/guides/AI地图制作.md` with your
  values (the source copy is packaged into the app at build time; the private copy can also be
  edited at runtime).

The map workflow includes a coordinate gate: the finished HTML must pass
`bundle/tools/mapgate.sh`, and the last line must be `MAPGATE=PASS`.

---

## 5. Features

| Capability | Description |
|------------|-------------|
| Multi-session chat | Parallel sessions, attachments, plan mode, background completion notifications |
| Tool calls | File read/write, command execution, web fetch, web search, image/PDF reading |
| Sub-agents | Independent subtasks run in parallel and return to the main session |
| Skills | `skill_list` / `skill_use`; custom skills can be written |
| Long-term memory | `memory_*` tools; empty by default and accumulated through use |
| MCP | Connect remote or local MCP servers from Settings |
| Pre-execution hooks | Run a script before a tool executes; can block the call |
| Voice | Voice-message transcription, realtime voice calls (hands-free) |
| Vision | Image understanding, screenshot review |
| File sharing | `send_file` pushes files to the system share sheet |
| Scheduled tasks | Run a user-defined search pipeline on weekdays |
| Workflows | PPT / PDF / Word / AI maps (specifications bundled in `bundle/guides/`) |

Tool boundaries: by default, reads and writes are limited to the app's private directory and
shared directory. Out-of-scope paths require user confirmation (denied after a 3-minute timeout).
Command execution is subject to the same approval rules.

---

## 6. Permissions

| Permission | Why it is needed | Risk / notes |
|------------|------------------|--------------|
| `INTERNET` | Call model APIs and fetch web pages | Data is sent to the model provider you configure |
| `RECORD_AUDIO` | Voice messages and voice calls | Captured only while you hold the record button |
| `MODIFY_AUDIO_SETTINGS` | Switch audio routing during calls | — |
| `VIBRATE` | Notification and call feedback | — |
| `READ/WRITE_CALENDAR` | Let the AI read/create calendar events | Called only when you ask |
| `ACCESS_FINE/COARSE_LOCATION` | Maps and location-aware tasks | Called only when you ask |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | Keep the agent and scheduled tasks running | Persistent notification is visible |
| `POST_NOTIFICATIONS` | Task-completion and scheduled-task notifications | — |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Trigger at 09:00 on weekdays | Can be revoked in system settings |
| `RECEIVE_BOOT_COMPLETED` | Restore the scheduler after reboot | — |
| `WAKE_LOCK` | Prevent long tasks from being interrupted | — |
| `MANAGE_EXTERNAL_STORAGE` | Read/write working files and xlsx databases under `/sdcard` | **High-risk permission**, not allowed on Google Play; this app is for sideloading. The app still works without it, but cannot write to shared storage |
| `READ/WRITE_EXTERNAL_STORAGE` | Legacy compatibility (≤ API 32/29) | — |

The app does not request contacts, SMS, call logs, camera, or background location.

---

## 7. Scheduled Search Tasks (User-Defined)

The app has a built-in scheduler: it runs once at **09:00 on weekdays**. The content is entirely
defined by the user.

### 7.1 Configuration

1. Open `files/work/scan_task.md` in the app's private directory. If it does not exist, the app
   copies it from `files/bundle/scan/TASK_TEMPLATE.md` on first use.
2. Fill in the template: topic, keyword groups, filter words, allowed sources, authoritative
   database path, and output columns.
3. Delete the `<!-- UNCONFIGURED -->` line.

The easiest approach: tell the AI, "Configure my scheduled task to search for topic XX every
Monday to Friday and write the results to /sdcard/XX/leads.xlsx." It will generate the file from
the template.

### 7.2 Behavior

- Not configured (file missing or still contains `UNCONFIGURED`) → no run, only a notification.
- At most one run per day; a missed 09:00 run is retried after 15 minutes, up to 3 times.
- The unattended run must not ask questions or wait for confirmation. It ends with `【任务完成】`.
- To change the trigger time, edit `HOUR` / `MINUTE` in `com/stereo/ai/ScanAlarm.java` and rebuild.
- Pipeline gates: fetch the original source → verify with `srccheck.sh` → deduplicate →
  `xlsx_append.sh` writes to the database and reads back `verified=K/N` → deliver with
  `send_file`. If any gate fails, stop and report.

Templates and tools live in `app/assets/bundle/scan/` and are implemented in pure toybox/awk; no
Python is required.

---

## 8. Office Document Workflows (Important Limits)

`bundle/guides/` contains four methodology documents:

| Specification | Phone | Desktop |
|---------------|-------|--------|
| AI map generation | ✅ Executable (includes coordinate gate) | ✅ |
| PPT_DESIGN | Read the specification and organize copy only | ✅ Generate `.pptx` (requires python-pptx + your template) |
| PDF production | ❌ | ✅ (python + PyMuPDF / weasyprint + OCR) |
| Word production | ❌ | ✅ (python-docx) |

There is no Python runtime on the phone. When a PPT/PDF/Word task is requested, the AI will
clearly state that it must be done on a desktop and will not falsely report completion.

The template paths and colors in `guides/PPT_DESIGN.md` are generic placeholders; replace them
with your own brand template.

---

## 9. Security and Privacy

- **Loopback services**: HTTP / MCP / proxy ports bind only to `127.0.0.1`. Except for the health
  check, page version, root page, and map self-check, every endpoint requires a random token.
- **Token**: generated on first launch, AES-GCM encrypted with Android Keystore, never logged,
  never leaves the device.
- **API keys**: encrypted at rest; Settings shows only a masked value; saving is refused if
  encryption fails.
- **Path approval**: tools accessing paths outside the working directory require user
  confirmation; timeout defaults to denial.
- **Hooks**: a pre-execution script that does not respond within 8 seconds is blocked.
- **No analytics SDK**: the app itself contains no third-party analytics SDK. The bundled codex
  engine is the upstream binary; its network behavior (including possible upstream diagnostic
  reporting) follows [openai/codex](https://github.com/openai/codex). Apart from that, requests go
  only to the model providers you configure and the sites you ask it to fetch.
- **Note**: conversations, attachments, and tool results are sent to the model provider you
  choose. Evaluate that provider's data policy yourself.

---

## 10. Upstream Components and Licenses

| Component | Source | Notes |
|-----------|--------|-------|
| codex app-server | [openai/codex](https://github.com/openai/codex) | Not distributed with this repository; downloaded at build time |
| agent-reach skill | [Panniantong/Agent-Reach](https://github.com/Panniantong/Agent-Reach) | Located at `app/assets/bundle/skills/agent-reach/`, MIT License; full text in that directory's `LICENSE` |
| AMap | AMap Open Platform | Use is subject to AMap's terms of service and quotas |

This project is released under Apache-2.0; see `LICENSE`. Third-party licenses are in `NOTICE`
and `app/assets/bundle/licenses/`. Check each third-party service's terms before use.

---

## 11. Known Limitations

- `arm64-v8a` only; emulators and other ABIs require build adjustments.
- Voice and vision are hard-wired to DashScope; switching providers does not switch them.
- Scheduled tasks depend on system alarms and background survival. Some vendors' battery
  optimizers kill background processes; whitelist the app manually.
- `MANAGE_EXTERNAL_STORAGE` cannot be published on Google Play; sideloading or enterprise
  distribution only.
- The map gate covers AMap output only. For ECharts and other export formats, see the "Boundaries"
  section of `bundle/guides/AI地图制作.md`.

---

## 12. License

- **This project**: Apache License 2.0; full text in [LICENSE](LICENSE).
- **OpenAI Codex**: Apache-2.0; see [NOTICE](NOTICE) and
  `app/assets/bundle/licenses/CODEX-NOTICE.txt`.
- **Agent-Reach**: MIT, Copyright (c) 2025 Agent Eyes; full text in
  `app/assets/bundle/skills/agent-reach/LICENSE`.

The APK's `assets/bundle/licenses/` directory ships these licenses and notices with the binary,
satisfying the attribution requirements for redistribution.
