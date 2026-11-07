"""WebSocket 端到端测试：模拟浏览器把音频流推给服务端。

用法：先启动服务，再运行
    .venv/bin/python tools/test_ws.py data/test/zh.wav [倍速]
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
import wave
from pathlib import Path

import websockets

CHUNK = 2048


def read_wav(path: str):
    with wave.open(path, "rb") as w:
        raw = w.readframes(w.getnframes())
    import numpy as np

    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


async def main(path: str, speed: float, url: str) -> None:
    import numpy as np

    audio = read_wav(path)
    audio = np.concatenate([audio, np.zeros(int(1.0 * 16000), dtype=np.float32)])

    events = []
    t_start = time.time()

    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "start",
                    "sample_rate": 16000,
                    "target_lang": "auto",
                }
            )
        )

        async def reader():
            try:
                async for message in ws:
                    obj = json.loads(message)
                    events.append(obj)
                    elapsed = time.time() - t_start
                    if obj["type"] == "segment":
                        print(f"{elapsed:6.2f}s  识别: {obj['text']}")
                    elif obj["type"] == "translation_delta":
                        print(f"{elapsed:6.2f}s  译文+ {obj['delta']!r}")
                    elif obj["type"] == "translation_done":
                        print(f"{elapsed:6.2f}s  完成 #{obj['id']} -> {obj['tgt']}")
                    elif obj["type"] == "partial":
                        print(f"{elapsed:6.2f}s  预览: {obj['text']}")
                    elif obj["type"] == "error":
                        print(f"{elapsed:6.2f}s  错误: {obj.get('message')}")
                    elif obj["type"] == "ready":
                        print(f"{elapsed:6.2f}s  服务就绪")
            except Exception:
                pass

        task = asyncio.create_task(reader())
        interval = (CHUNK / 16000) / speed
        for i in range(0, len(audio), CHUNK):
            await ws.send(audio[i : i + CHUNK].tobytes())
            await asyncio.sleep(interval)
        await ws.send(json.dumps({"type": "stop"}))

        try:
            await asyncio.wait_for(task, timeout=90)
        except asyncio.TimeoutError:
            task.cancel()

    done = [e for e in events if e["type"] == "translation_done"]
    print(f"\n共完成 {len(done)} 段翻译")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    speed = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    url = sys.argv[3] if len(sys.argv) > 3 else "ws://127.0.0.1:8765/ws/audio"
    asyncio.run(main(sys.argv[1], speed, url))
