# 从 GitHub 重建这份项目

仓库：https://github.com/Otto-Z/local-interpreter （私有）

## 一句话结论

**代码可以完全重建**（已实测：全新克隆 + 安装依赖 + 启动服务，VAD / ASR / 翻译 / 助教全部正常）。
但有三类东西不在仓库里，删之前请先处理：

| 类别 | 位置 | 大小 | 会丢什么 | 怎么办 |
|---|---|---|---|---|
| 应用数据 | `~/Library/Application Support/本地同传/` | ~380MB | **课程记录、录音、课件、术语表、设置里的 API Key** | 备份，见下文 |
| SenseVoice 模型 | `data/models/sensevoice/` | 228MB | 无（首次使用自动下载） | 可删除 |
| 构建/打包产物 | `.venv/`、`dist/`、`android/app/build`、`android/app/.cxx`、`android/.gradle`、`android/vendor/llama.cpp/build-q2test` | 约 9GB | 无（可重新生成） | 可删除 |

---

## 一、绝对要先备份的应用数据

应用运行时数据**不在项目目录里**，而是在：

```
~/Library/Application Support/本地同传/
```

里面包含：

- `history.db` / `history.db-wal` / `history.db-shm` — 课程、课次、识别原文、翻译、摘要、术语表、助教问答（当前有 2 门课程）
- `audio/` — 142 个录音文件（约 2.7MB）
- `materials/` — 上传的课件
- `settings.json` — **包含 API Key**（`llm_api_key` 等）
- `models/` — 运行时下载的模型缓存（228MB，可丢弃）
- `chrome-profile/` — 内置浏览器缓存（152MB，可丢弃）
- `*.log` — 日志（可丢弃）

**删除电脑前执行：**

```bash
mkdir -p ~/Desktop/本地同传备份
ditto "$HOME/Library/Application Support/本地同传" ~/Desktop/本地同传备份/appdata
```

新机器上恢复（先装好应用并跑一次，让目录生成，再覆盖）：

```bash
ditto ~/Desktop/本地同传备份/appdata "$HOME/Library/Application Support/本地同传"
```

只想保留课程和录音、不要浏览器缓存的话，只复制 `history.db*`、`audio/`、`materials/`、`settings.json` 四项即可。

> `settings.json` 里有明文 API Key。备份文件不要放到网盘或公共仓库。

---

## 二、从零重建

```bash
gh repo clone Otto-Z/local-interpreter
cd local-interpreter
```

### macOS 端

需要 Python 3.12 和 `uv`：

```bash
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python -r requirements.txt
./run.sh
```

`run.sh` 启动本地服务，浏览器访问 http://127.0.0.1:8765 。

打包成可双击的 `.app`：

```bash
./build_app.sh          # 约 15–20 分钟，会从 HuggingFace 下载 Hy-MT2 与 Qwen3 模型
```

产物在 `dist/本地同传.app`。

### Android 端

需要 JDK 17、Android SDK platform 34 / build-tools 34、NDK 27.2、CMake 3.31.5。

先创建 `android/local.properties`（按本机路径）：

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > android/local.properties
```

然后：

```bash
cd android
./gradlew :app:assembleDebug
```

llama.cpp 源码（含 Q2_0C 量化补丁）、sherpa-onnx 预编译库、模型 assets 都在仓库里，无需额外准备。

---

## 三、可以放心删除的（不出仓库也能重建）

```bash
rm -rf .venv dist android/app/build android/app/.cxx android/.gradle \
       android/vendor/llama.cpp/build-q2test android/vendor/sherpa-android.tar.bz2
rm -rf data/models            # 首次运行时自动重新下载
rm -f data/test/server.log
```

`android/local.properties` 也可以删，新机器重建时再写。

---

## 四、仓库里没有、但也不影响重建的东西

- `data/models/sensevoice/` — SenseVoice 首次使用时自动从 sherpa-onnx releases 下载
- HuggingFace 模型缓存（`~/.cache/huggingface/`）— Whisper / Qwen3 会在首次使用时下载
- 签名密钥 `android/app/localinterpreter.jks` 的密码写死在 `build.gradle.kts` 里（`localinterpreter`），密钥文件本身已入库，换机器照样能出 release 包

---

## 五、已验证项

- 全新克隆 4197 个文件，与本地源码逐文件比对一致（除构建目录）
- `app/models/`、`android/app/src/main/assets/` 的 VAD 与声纹模型哈希与本地一致
- llama.cpp 的 13 处本地修改（Q2_0C 相关）完整保留
- 克隆版实际启动服务：`translator / assistant / asr / vad` 全部 ready，首页 200
