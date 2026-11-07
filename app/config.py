"""全局配置与设置的持久化。"""
from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 打包成 .app 后应用目录只读，运行数据放到用户目录
DATA_DIR = Path(
    os.environ.get("LOCAL_INTERPRETER_DATA") or (ROOT / "data")
)
DATA_DIR.mkdir(parents=True, exist_ok=True)
try:
    os.chmod(DATA_DIR, 0o700)
except OSError:
    pass
SETTINGS_PATH = DATA_DIR / "settings.json"

# VAD 模型优先用用户目录的副本，其次用应用内自带的
VAD_MODEL_PATH = DATA_DIR / "models" / "silero_vad.onnx"
if not VAD_MODEL_PATH.exists():
    bundled = Path(__file__).resolve().parent / "models" / "silero_vad.onnx"
    if bundled.exists():
        VAD_MODEL_PATH = bundled

DEFAULT_SETTINGS: dict = {
    # 语音识别
    "asr_engine": "whisper",        # whisper | sensevoice | qwen3
    "asr_model": "mlx-community/whisper-large-v3-turbo",
    "asr_qwen_model": "mlx-community/Qwen3-ASR-0.6B-8bit",
    "source_lang": "auto",          # auto / zh / en / ja / ko / ...
    # 翻译：内置模型或任意 OpenAI 兼容服务
    "llm_backend": "builtin",       # builtin | api
    "llm_base_url": "http://127.0.0.1:8000/v1",
    "llm_model": "",
    "llm_api_key": "",
    "backends": [],                 # 配置档：[{name, base_url, model, api_key}]
    "target_lang": "auto",          # auto 表示翻成与原文相反的语言（中文<->英文）
    "translate_mode": "sentence",   # sentence 整句 | append 流式追加
    # 分句
    "end_silence_ms": 400,          # 静音多久判定一句话说完
    "max_segment_s": 6.0,           # 连续说话时强制切分的最长片段
    "partials": True,               # 说话过程中显示识别预览
    "partial_translate": True,      # 说话过程中同步生成译文草稿
    "context_fix": True,            # 根据上下文二次校正识别文本
    "assistant_enabled": True,      # 课堂助教总开关
    "assistant_summary": True,      # 实时要点
    "assistant_qa": True,           # 提问自动作答
    "assistant_interval_s": 60,     # 要点更新间隔
    "assistant_only_teacher": False, # 只回答主讲人（说话最多的人）的问题
    "assistant_target_speaker": "",  # 只回答指定说话人（如「说话人 1」）；留空不限
    "glossary_auto": True,           # 下课后自动补充课程术语表
    "assistant_base_url": "",       # 分析服务（留空用翻译模型）
    "assistant_model": "",
    "assistant_api_key": "",
    "save_audio": True,
    "diarization_enabled": True,   # 区分说话人（老师/同学）           # 保存每段音频供回听
    "show_source": True,
    "subtitle_size": 46,            # 字幕模式字号
    "subtitle_opacity": 0.82,       # 字幕模式背景不透明度
    "retention_days": 0,            # 自动清理多少天前的课次；0 表示永久保留
}

LANG_NAMES = {
    "zh": "简体中文",
    "en": "英语",
    "ja": "日语",
    "ko": "韩语",
    "fr": "法语",
    "de": "德语",
    "es": "西班牙语",
    "ru": "俄语",
    "it": "意大利语",
    "pt": "葡萄牙语",
    "ar": "阿拉伯语",
    "th": "泰语",
    "vi": "越南语",
}

SOURCE_LANG_CHOICES = ["auto", "zh", "en", "ja", "ko", "fr", "de", "es", "ru", "it", "pt", "ar", "th", "vi"]
TARGET_LANG_CHOICES = ["auto"] + [c for c in SOURCE_LANG_CHOICES if c != "auto"]


def load_settings() -> dict:
    settings = dict(DEFAULT_SETTINGS)
    if SETTINGS_PATH.exists():
        try:
            data = json.loads(SETTINGS_PATH.read_text("utf-8"))
            if isinstance(data, dict):
                for key in DEFAULT_SETTINGS:
                    if key in data:
                        settings[key] = data[key]
        except Exception:
            pass
    return settings


def save_settings(patch: dict) -> dict:
    settings = load_settings()
    for key in DEFAULT_SETTINGS:
        if key in patch:
            settings[key] = patch[key]
    SETTINGS_PATH.write_text(json.dumps(settings, ensure_ascii=False, indent=2), "utf-8")
    return settings
