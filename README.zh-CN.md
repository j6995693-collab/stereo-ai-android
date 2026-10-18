# Stereo AI（开源脱敏版）

[中文](README.zh-CN.md) | [English](README.md)

Android 上的开源个人 AI 助手：本地优先、密钥自持、模型可换、工具可审、工作流可扩展。
一个 WebView 界面 + 本机 Agent 引擎，把「对话、工具调用、文件读写、联网检索、定时任务、
语音通话、视觉识别、地图与办公文档工作流」装进一部手机。

本仓库是**脱敏后的开源版本**：不含任何个人信息、API Key、Token、设备序列号或私有业务数据。
所有密钥都由使用者自己申请并在 App 内填写。

**状态：** v3.0.1，早期参考实现。Android 8.0+，`arm64-v8a`，仅侧载。无账号、无项目方云端、不内置任何 Key。

## 快速开始

1. 安装 JDK 8+、Android SDK 34，下载固定版本的 Codex 引擎（见第 3 节）。
2. 运行 `./build_apk.sh`。
3. `adb install -r build/StereoAI-public.apk`。
4. 打开 **设置 → 模型与密钥**，填入你自己的 API Key。

## 设计初衷：拒绝资本控制个人 Agent

**我不希望活在一个由资本控制个人 Agent 的时代。**

个人 Agent 不是普通 App。它会进入我们的文件、日程、记忆和工作流，替我们调用工具、连接服务、做出判断。谁控制了个人 Agent，谁就控制了个人数字生活的入口。如果这个入口被少数资本和平台封闭掌握，用户失去的将不只是一个产品选择，而是对自己数据、记忆和行为的支配权。

所以，我们的立场很明确：

**我们的个人 Agent，一定要在法律监管下，由开源社区自己开发。它不能属于任何一家公司，不能属于任何一个资本阵营，不能成为平台控制用户的工具。**

这不是一句口号，而是 Stereo AI 的全部起点：

- 因为拒绝资本控制，我们把 Agent 放在用户自己的手机上，而不是项目方的云里；
- 因为拒绝黑箱，我们把代码、工具边界、权限审批和构建过程开放出来；
- 因为拒绝锁定，我们让模型、引擎、技能和工作流都可以替换；
- 因为拒绝无约束的“自治”，我们主张个人 Agent 必须接受法律与监管，必须可审计、可追责、可退出；
- 因为拒绝把用户变成流量和计费对象，我们坚持密钥自持、数据自持、记忆自持。

Stereo AI 是一次尝试。它不完美，但它要证明一件事：

**个人 Agent 可以不建立在资本控制的平台上，而可以建立在用户自有设备、开源代码和社区共治之上。**

---

## 1. 架构

```
WebView(assets/index.html)
        │  HTTP 127.0.0.1:8765（本机回环，随机令牌鉴权）
        ▼
HttpServer ──► codex app-server（libcodex.so，JSON-RPC over stdio）
        │              │
        │              ├─ MCP 工具层 127.0.0.1:8767（文件/搜索/命令/MCP 客户端）
        │              └─ ResponseProxy 127.0.0.1:8768（Responses/Anthropic ↔ Chat Completions 转换）
        ▼
上游模型 API（阿里百炼 DashScope / 智谱 / DeepSeek / Kimi / 自定义兼容端点）
```

- 引擎二进制不随仓库分发，构建时通过 `CODEX_BIN` 打包进 `lib/arm64-v8a/libcodex.so`。
- 所有本机端口只监听 `127.0.0.1`，令牌由 Android Keystore 加密保存。

---

## 2. 目录结构

```
app/
  AndroidManifest.xml
  com/stereo/ai/            Java 源码（Activity/Service/HTTP/工具层/定时器）
  res/                      图标与网络配置
  assets/
    index.html              前端界面
    agents_md.txt           注入给模型的系统提示与能力说明
    bundle/
      guides/               工作流规范：PPT / PDF / Word / AI 地图
      skills/agent-reach/   联网检索技能（第三方，见第 10 节）
      tools/                地图坐标门禁（mapgate.sh / polydecode.awk）
      scan/                 定时搜索通用模板与 xlsx 工具链
      licenses/             第三方许可证与 NOTICE（随 APK 分发）
build_apk.sh                无 Gradle 构建脚本
```

---

## 3. 构建

### 3.1 环境要求

| 依赖 | 版本 / 说明 |
|------|-------------|
| JDK | 8 或以上（需 `javac`、`keytool`） |
| Android SDK | `build-tools/34.0.0`、`platforms/android-34` |
| codex 引擎 | `codex-package-aarch64-unknown-linux-musl.tar.gz`（[openai/codex Releases](https://github.com/openai/codex/releases)，固定 `rust-v0.156.1`） |
| 系统 | macOS / Linux（脚本使用 bash + zip + find） |

### 3.2 构建命令

```bash
# 1. 下载并校验固定版本的 Codex 引擎（macOS / Linux）
VER=rust-v0.156.1
ASSET=codex-package-aarch64-unknown-linux-musl.tar.gz
curl -LO "https://github.com/openai/codex/releases/download/$VER/$ASSET"
curl -LO "https://github.com/openai/codex/releases/download/$VER/codex-package_SHA256SUMS"
grep " $ASSET\$" codex-package_SHA256SUMS | shasum -a 256 -c -
mkdir -p codex-engine && tar -xzf "$ASSET" -C codex-engine

# 2. 构建
export ANDROID_SDK="$HOME/android-build/sdk"        # 你的 SDK 根目录
export JAVA_HOME="$HOME/android-build/jdk/Contents/Home"
export CODEX_BIN="$PWD/codex-engine/bin/codex"

./build_apk.sh
```

产物：`build/StereoAI-public.apk`（自签名）。

可选环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OUTPUT_NAME` | `StereoAI-public.apk` | 输出文件名 |
| `KEYSTORE` | `build/release.keystore` | 签名库，不存在则自动生成 |
| `KEYSTORE_PASS` | `android` | 签名密码 |
| `BUILD_TOOLS_VER` | 自动取最高版本 | build-tools 版本 |
| `PLATFORM` | `android-34` | 编译用平台 |
| `DEBUG` | 空 | 非空则编 debug（`android:debuggable=true`） |

> 自签名仅供本地安装测试。若要发布或长期升级，请换成自己的 release keystore
> 并妥善保管——换签名后覆盖安装会失败，需先卸载。

### 3.3 安装

```bash
adb install -r build/StereoAI-public.apk
```

最低 Android 8.0（API 26），目标 API 34，仅支持 `arm64-v8a`。

---

## 4. 首次启动与 API Key 配置

打开 App → 右上角设置 → **模型与密钥**。

内置四个供应商，任选其一作为主模型；推荐阿里百炼（DashScope），因为语音与视觉也走它。

| 供应商 | 接口地址 | 用途 | 申请入口 |
|--------|----------|------|----------|
| **阿里百炼 DashScope** | `dashscope.aliyuncs.com/compatible-mode/v1` | 对话/工具调用 + **语音识别 + 语音合成 + 视觉识别 + 联网搜索** | <https://bailian.console.aliyun.com/> |
| 智谱 GLM | `open.bigmodel.cn/api/anthropic` | 对话（Anthropic 协议） | <https://open.bigmodel.cn/> |
| DeepSeek | `api.deepseek.com/v1` | 对话 | <https://platform.deepseek.com/> |
| Kimi | `api.moonshot.cn/v1` | 对话 | <https://platform.moonshot.cn/> |
| 自定义 | 任意 OpenAI / Anthropic 兼容端点 | 对话 | 设置页「＋ 添加模型供应商」 |

填写步骤：

1. 在对应平台创建 API Key。
2. 设置页粘贴到该供应商的「粘贴 API Key」输入框，保存。
3. 点「拉取模型」获取模型列表，或手动添加模型 ID。
4. 选一个模型。**对话开始后模型固定**，切换只对新会话生效。

Key 的保存方式：AES-GCM 加密后写入本地配置；Android Keystore 不可用时会**拒绝保存**
而不是明文落盘。

### 4.1 各功能需要的 Key

| 功能 | 必需 Key | 默认模型 / 接口 |
|------|----------|-----------------|
| 文字对话、工具调用、子 Agent | 任一对话供应商 | 默认 `qwen3.8-max` |
| 语音消息（录音转文字） | DashScope | `qwen3-asr-flash` |
| 实时语音通话（听说） | DashScope | ASR `qwen3-asr-flash` + TTS `cosyvoice-v2` |
| 图片理解 / 截图识别 | DashScope | `qwen3-vl-plus` |
| 联网搜索 `web_search` | DashScope | 百炼内置搜索模型 |
| 网页抓取 `web_fetch` | 无 | 直接 HTTP 抓取 |
| 高德地图生成 | 高德开放平台 Key ×3 | 见 4.2 |
| 定时搜索任务 | 对话 Key + DashScope（搜索） | 见第 7 节 |
| PPT / PDF / Word | 无额外 Key | 需在桌面端执行，见第 8 节 |

> 语音和视觉**只认 DashScope Key**，因为代码里固定调用百炼的 `qwen3-asr-flash`、
> `cosyvoice-v2`、`qwen3-vl-plus`。其他供应商只负责文字对话。

### 4.2 高德地图（AI 地图工作流）

在 [高德开放平台控制台](https://console.amap.com/dev/key/app) 创建应用后，需要 **三个值**：

| 值 | Key 类型 | 用途 |
|----|----------|------|
| JS API Key | Web端（JS API） | 浏览器/WebView 里显示地图 |
| 安全密钥 securityJsCode | 与 JS Key 配套 | `window._AMapSecurityConfig` |
| REST API Key | Web服务 | 地理编码、POI、驾车/步行路线 |

配置方式（任选）：

- 直接告诉 AI：「这是我的高德 JS Key / 安全密钥 / REST Key：…，用它做地图」；
- 或把 `app/assets/bundle/guides/AI地图制作.md` 里的 `<你的 … Key>` 占位符换成你的值
  （源码里的文件会在构建时打进 App；运行时也可改私有目录副本）。

地图工作流自带坐标门禁：成品 HTML 必须跑 `bundle/tools/mapgate.sh`，
末行 `MAPGATE=PASS` 才算通过。

---

## 5. 功能清单

| 能力 | 说明 |
|------|------|
| 多会话对话 | 会话并行、附件、计划模式、后台完成通知 |
| 工具调用 | 读写文件、命令执行、网页抓取、联网搜索、图片/PDF 读取 |
| 子 Agent | 独立子任务并行，结果回主会话 |
| 技能 | `skill_list` / `skill_use`；可写入自定义技能 |
| 长期记忆 | `memory_*` 工具，默认空白，由使用中积累 |
| MCP | 设置页可接入远程或本地 MCP 服务器 |
| 执行前钩子 | 指定工具执行前调用脚本，可拦截 |
| 语音 | 语音消息转写、实时语音通话（免提） |
| 视觉 | 图片理解、截图复核 |
| 文件分享 | `send_file` 推送到系统分享面板 |
| 定时任务 | 工作日定时执行用户自定义的搜索流水线 |
| 工作流 | PPT / PDF / Word / AI 地图（规范内置于 `bundle/guides/`） |

工具执行边界：默认只允许在 App 私有目录与共享目录内读写；越界路径需要用户确认
（3 分钟超时即拒绝）。命令执行同样受审批约束。

---

## 6. 权限说明（逐条）

| 权限 | 为什么需要 | 风险 / 说明 |
|------|-----------|-------------|
| `INTERNET` | 调用模型 API、抓网页 | 数据会发给你配置的模型供应商 |
| `RECORD_AUDIO` | 语音消息与语音通话 | 仅在你按下录音时采集 |
| `MODIFY_AUDIO_SETTINGS` | 通话时切换音频路由 | — |
| `VIBRATE` | 通知与通话反馈 | — |
| `READ/WRITE_CALENDAR` | 让 AI 读取/创建日程 | 仅在你要求时调用 |
| `ACCESS_FINE/COARSE_LOCATION` | 地图与本地化任务 | 仅在你要求时调用 |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | 后台保持 Agent 与定时任务运行 | 常驻通知可见 |
| `POST_NOTIFICATIONS` | 任务完成与定时任务通知 | — |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | 工作日 09:00 准点触发 | 可在系统设置里撤销 |
| `RECEIVE_BOOT_COMPLETED` | 重启后恢复定时器 | — |
| `WAKE_LOCK` | 长任务不中断 | — |
| `MANAGE_EXTERNAL_STORAGE` | 读写 `/sdcard` 下的工作文件与 xlsx 库 | **高风险权限**，Google Play 不允许；本 App 仅供侧载。不给也能用，只是写不了共享目录 |
| `READ/WRITE_EXTERNAL_STORAGE` | 旧系统兼容（≤ API 32/29） | — |

App 不申请通讯录、短信、通话记录、相机、后台定位等权限。

---

## 7. 定时搜索任务（自定义）

App 内置定时器：**工作日 09:00** 自动执行一次。执行内容完全由使用者定义。

### 7.1 配置

1. 打开 App 私有目录下的 `files/work/scan_task.md`（不存在时首次会自动从
   `files/bundle/scan/TASK_TEMPLATE.md` 复制）。
2. 按模板填写：主题、关键词组、过滤词、允许的信息源、权威库路径、输出列。
3. 删除文件里的 `<!-- UNCONFIGURED -->` 一行。

最省事的做法：直接对 AI 说「帮我把定时任务配成每周一到周五搜 XX 主题，
结果写进 /sdcard/XX/线索库.xlsx」，它会按模板生成文件。

### 7.2 行为

- 未配置（文件不存在 / 仍带 `UNCONFIGURED`）→ 不执行，只发一条通知。
- 同一天只跑一次；错过 09:00 会在 15 分钟后补跑，最多 3 次。
- 无人值守的那一轮禁止提问与等待确认，完成后以 `【任务完成】` 收尾。
- 改触发时间：`com/stereo/ai/ScanAlarm.java` 的 `HOUR` / `MINUTE`，重新构建。
- 流水线门禁：原文取证 → `srccheck.sh` 核对 → 去重 → `xlsx_append.sh` 入库并回读
  `verified=K/N` → `send_file` 交付。任何一门不过即停止并报告。

模板与工具链在 `app/assets/bundle/scan/`，纯 toybox/awk 实现，无需 Python。

---

## 8. 办公文档工作流（重要限制）

`bundle/guides/` 内置四套方法论：

| 规范 | 手机端 | 桌面端 |
|------|--------|--------|
| AI 地图制作 | ✅ 可执行（含坐标门禁） | ✅ |
| PPT_DESIGN | 只能读规范、整理文案 | ✅ 生成 `.pptx`（需 python-pptx + 你的模板） |
| PDF 制作 | ❌ | ✅（python + PyMuPDF / weasyprint + OCR） |
| Word 制作 | ❌ | ✅（python-docx） |

手机端没有 Python 运行时。命中 PPT/PDF/Word 任务时，AI 会明确告知需在桌面端执行，
不会虚报完成。

`guides/PPT_DESIGN.md` 中的模板路径与配色是通用占位，替换成你自己的品牌模板即可。

---

## 9. 安全与隐私

- **回环服务**：HTTP / MCP / 代理端口只绑定 `127.0.0.1`；除健康检查、页面版本、
  根页面、地图自检外，所有接口都要求随机令牌。
- **令牌**：首次启动生成，Android Keystore AES-GCM 加密存储，不写日志、不出设备。
- **API Key**：加密存储；设置页只显示掩码；加密失败时拒绝保存。
- **路径审批**：工具访问工作目录之外的路径需要用户确认，超时默认拒绝。
- **钩子**：执行前脚本 8 秒内无响应即拦截。
- **无统计 SDK**：App 自身不包含第三方统计 SDK。内置的 codex 引擎是上游原版二进制，
  其网络行为（包括可能的上游诊断上报）以 [openai/codex](https://github.com/openai/codex)
  的实现为准；除此之外，请求只发往你配置的模型供应商与你要求抓取的网站。
- **注意**：对话内容、附件与工具结果会发送给所选模型供应商，请自行评估其数据政策。

---

## 10. 上游组件与许可

| 组件 | 来源 | 说明 |
|------|------|------|
| codex app-server | [openai/codex](https://github.com/openai/codex) | 不随仓库分发，构建时自行下载 |
| agent-reach 技能 | [Panniantong/Agent-Reach](https://github.com/Panniantong/Agent-Reach) | 位于 `app/assets/bundle/skills/agent-reach/`，MIT License，全文见该目录 `LICENSE` |
| 高德地图 | 高德开放平台 | 使用需遵守其服务条款与配额 |

本项目以 Apache-2.0 发布，见 `LICENSE`；第三方组件许可见 `NOTICE` 与
`app/assets/bundle/licenses/`。使用前请自行确认各第三方服务的条款。

---

## 11. 已知限制

- 仅 `arm64-v8a`；模拟器与其他 ABI 需自行调整构建。
- 语音与视觉固定走 DashScope，换供应商不会自动切换。
- 定时任务依赖系统闹钟与后台存活；部分厂商的省电策略会杀掉后台，需手动加白名单。
- `MANAGE_EXTERNAL_STORAGE` 无法上架 Google Play，仅适合侧载或企业分发。
- 地图门禁只针对高德成品；ECharts 等其他导出格式的判定见
  `bundle/guides/AI地图制作.md` 的「边界」一节。

---

## 12. 许可证

- **本项目**：Apache License 2.0，全文见 [LICENSE](LICENSE)。
- **OpenAI Codex**：Apache-2.0，NOTICE 见 [NOTICE](NOTICE) 与
  `app/assets/bundle/licenses/CODEX-NOTICE.txt`。
- **Agent-Reach**：MIT，Copyright (c) 2025 Agent Eyes，全文见
  `app/assets/bundle/skills/agent-reach/LICENSE`。

构建产物 APK 的 `assets/bundle/licenses/` 内随包携带上述许可证与 NOTICE，
满足再分发时的声明保留要求。
