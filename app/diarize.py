"""本地说话人区分：CAM++ 声纹嵌入 + 在线聚类。"""
from __future__ import annotations

import threading
import urllib.request
from pathlib import Path

import numpy as np

from .config import DATA_DIR

MODEL_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
    "speaker-recongition-models/"
    "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
)
BUNDLED = Path(__file__).resolve().parent / "models" / "speaker_campplus.onnx"
CACHED = DATA_DIR / "models" / "speaker_campplus.onnx"
COLORS = ["#4fd1a5", "#e0b85c", "#7aa7ff", "#f08a8a", "#b48ef0", "#6cc9d6", "#f2a65a", "#9ad36a"]


def ensure_model() -> Path:
    if BUNDLED.exists():
        return BUNDLED
    if CACHED.exists():
        return CACHED
    CACHED.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(MODEL_URL, CACHED)
    return CACHED


class Diarizer:
    def __init__(self, threshold: float = 0.68, max_speakers: int = 8):
        import sherpa_onnx

        config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(ensure_model()), num_threads=2, debug=False, provider="cpu"
        )
        self._extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
        self._lock = threading.Lock()
        self._threshold = threshold
        self._max = max_speakers
        self._centroids: list[np.ndarray] = []

    def _embed(self, audio: np.ndarray) -> np.ndarray | None:
        audio = np.ascontiguousarray(audio, dtype=np.float32)
        if len(audio) < int(1.2 * 16000):
            return None
        stream = self._extractor.create_stream()
        stream.accept_waveform(16000, audio)
        if not self._extractor.is_ready(stream):
            return None
        vector = np.asarray(self._extractor.compute(stream), dtype=np.float32)
        norm = float(np.linalg.norm(vector))
        if norm < 1e-6:
            return None
        return vector / norm

    def assign(self, audio: np.ndarray) -> tuple[int, str, str] | None:
        vector = self._embed(audio)
        if vector is None:
            return None
        with self._lock:
            best_idx = -1
            best_score = -1.0
            for index, centroid in enumerate(self._centroids):
                score = float(np.dot(centroid, vector))
                if score > best_score:
                    best_score = score
                    best_idx = index
            if best_idx >= 0 and best_score >= self._threshold:
                blended = 0.7 * self._centroids[best_idx] + 0.3 * vector
                self._centroids[best_idx] = blended / np.linalg.norm(blended)
                index = best_idx
            elif len(self._centroids) < self._max:
                self._centroids.append(vector)
                index = len(self._centroids) - 1
            else:
                index = best_idx
                blended = 0.7 * self._centroids[index] + 0.3 * vector
                self._centroids[index] = blended / np.linalg.norm(blended)
        return index, f"说话人 {index + 1}", COLORS[index % len(COLORS)]
