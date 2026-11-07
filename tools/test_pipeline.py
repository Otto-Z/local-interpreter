"""端到端管线测试：音频文件 -> 分句 -> 识别 -> 翻译。

用法（项目根目录）：
    .venv/bin/python tools/test_pipeline.py data/test/zh.wav
"""
from __future__ import annotations

import asyncio
import sys
import time
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.asr import ASREngine  # noqa: E402
from app.config import VAD_MODEL_PATH, load_settings  # noqa: E402
from app.llm import Translator, resolve_target  # noqa: E402
from app.pipeline import Segmenter  # noqa: E402
from app.vad import load_vad  # noqa: E402

CHUNK = 2048


def read_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000, f"需要 16k 采样率，实际 {w.getframerate()}"
        assert w.getnchannels() == 1
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


async def main(path: str) -> None:
    settings = load_settings()
    print(f"语音模型: {settings['asr_model']}")
    print(f"翻译模型: {settings['llm_model']} @ {settings['llm_base_url']}")

    t = time.time()
    asr = ASREngine(settings["asr_model"])
    await asyncio.to_thread(asr.load)
    print(f"模型加载完成 {time.time() - t:.1f}s，状态={asr.status}")
    if asr.status != "ready":
        print("加载失败:", asr.error)
        return

    translator = Translator(
        settings["llm_base_url"], settings["llm_model"], settings["llm_api_key"]
    )
    if not settings["llm_model"]:
        print("提示：未配置翻译模型，只测试识别与分句。")

    segmenter = Segmenter(
        load_vad(str(VAD_MODEL_PATH)),
        end_silence_ms=settings["end_silence_ms"],
        max_segment_s=settings["max_segment_s"],
    )
    audio = read_wav(path)
    audio = np.concatenate([audio, np.zeros(int(0.8 * 16000), dtype=np.float32)])

    finals = []
    t = time.time()
    for i in range(0, len(audio), CHUNK):
        for ev in segmenter.process(audio[i : i + CHUNK]):
            if ev["kind"] == "final" and not ev["too_short"]:
                finals.append(ev)
    print(f"\n分句完成：{len(finals)} 段，用时 {time.time() - t:.2f}s")
    print(f"VAD: {segmenter.vad.name}\n")

    context: list[str] = []
    for i, ev in enumerate(finals, 1):
        t = time.time()
        text, lang = await asyncio.to_thread(
            asr.transcribe, ev["audio"], None if settings["source_lang"] == "auto" else settings["source_lang"]
        )
        asr_s = time.time() - t
        print(f"[{i}] {ev['t0']:5.1f}-{ev['t1']:5.1f}s  识别 {asr_s:.2f}s ({lang})")
        print(f"    原文: {text}")
        if not text:
            print("    (空)\n")
            continue
        if not settings["llm_model"]:
            print()
            continue
        target = resolve_target(lang, settings["target_lang"])
        t = time.time()
        chunks = []
        first = None
        async for delta in translator.translate_stream(text, lang, target, context):
            if first is None:
                first = time.time() - t
            chunks.append(delta)
        print(f"    译文({target}): {''.join(chunks)}")
        print(f"    翻译 {time.time() - t:.2f}s（首字 {first or 0:.2f}s）\n")
        context.append(text)
        context = context[-2:]


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    asyncio.run(main(sys.argv[1]))
