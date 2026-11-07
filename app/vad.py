"""语音活动检测：优先 Silero VAD（ONNX），失败时退回能量 VAD。"""
from __future__ import annotations

import numpy as np

SAMPLE_RATE = 16000
FRAME = 512  # 32ms @ 16k，Silero v5 要求的输入长度


class SileroVAD:
    name = "silero"

    def __init__(self, model_path: str):
        import onnxruntime as ort

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        opts.inter_op_num_threads = 1
        opts.log_severity_level = 3
        self.sess = ort.InferenceSession(
            model_path, sess_options=opts, providers=["CPUExecutionProvider"]
        )
        self._sr = np.array([SAMPLE_RATE], dtype=np.int64)
        self.reset()

    def reset(self) -> None:
        # Silero v5：state 形状 (2, 1, 128)，并在每帧前拼接 64 点上下文
        self._state = np.zeros((2, 1, 128), dtype=np.float32)
        self._context = np.zeros((1, 64), dtype=np.float32)

    def prob(self, frame: np.ndarray) -> float:
        x = np.concatenate([self._context, frame[None, :]], axis=1).astype(np.float32)
        out, self._state = self.sess.run(
            None, {"input": x, "state": self._state, "sr": self._sr}
        )
        self._context = x[:, -64:]
        return float(np.asarray(out).reshape(-1)[0])


class EnergyVAD:
    """自适应噪声门限的简易 VAD，作为 Silero 不可用时的兜底。"""

    name = "energy"

    def __init__(self):
        self.reset()

    def reset(self) -> None:
        self._noise = 0.005

    def prob(self, frame: np.ndarray) -> float:
        rms = float(np.sqrt(np.mean(frame.astype(np.float32) ** 2)) + 1e-9)
        if rms < self._noise * 1.8:
            self._noise = 0.98 * self._noise + 0.02 * rms
        threshold = max(self._noise * 3.0, 0.004)
        return 1.0 if rms > threshold else 0.0


def load_vad(model_path: str):
    try:
        return SileroVAD(model_path)
    except Exception:
        return EnergyVAD()
