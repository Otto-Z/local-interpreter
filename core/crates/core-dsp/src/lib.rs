//! Real-time audio DSP primitives shared across macOS, Android, and desktop targets:
//! - Anti-aliased continuous stream resampler (`i16` / `f32`, mono/stereo -> 16 kHz mono `f32`)
//! - Pre-roll ring buffer
//! - Endpointing speech segmenter with pluggable VAD

use std::collections::VecDeque;
use std::f32::consts::PI;

pub const TARGET_SAMPLE_RATE: u32 = 16_000;

/// Compute normalized RMS level in `[0.0, 1.0]` for UI audio meters.
pub fn rms_level(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum_sq: f32 = samples.iter().map(|&s| s * s).sum();
    (sum_sq / samples.len() as f32).sqrt().clamp(0.0, 1.0)
}

/// Downmix interleaved multi-channel PCM into mono.
pub fn downmix_to_mono(interleaved: &[f32], channels: usize) -> Vec<f32> {
    if channels <= 1 {
        return interleaved.to_vec();
    }
    let frames = interleaved.len() / channels;
    let inv = 1.0 / channels as f32;
    let mut out = Vec::with_capacity(frames);
    for f in 0..frames {
        let base = f * channels;
        let mut sum = 0.0f32;
        for c in 0..channels {
            sum += interleaved[base + c];
        }
        out.push(sum * inv);
    }
    out
}

/// Encode 16 kHz mono Float32 PCM (`[-1.0, 1.0]`) into a standard 44-byte header RIFF WAV (16-bit PCM).
pub fn encode_wav_16k_mono_pcm16(samples: &[f32]) -> Vec<u8> {
    let data_bytes = (samples.len() * 2) as u32;
    let riff_size = 36 + data_bytes;
    let mut out = Vec::with_capacity(44 + data_bytes as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&riff_size.to_le_bytes());
    out.extend_from_slice(b"WAVE");
    out.extend_from_slice(b"fmt ");
    out.extend_from_slice(&16u32.to_le_bytes()); // PCM chunk size
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM format
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&TARGET_SAMPLE_RATE.to_le_bytes());
    out.extend_from_slice(&(TARGET_SAMPLE_RATE * 2).to_le_bytes()); // byte rate
    out.extend_from_slice(&2u16.to_le_bytes()); // block align
    out.extend_from_slice(&16u16.to_le_bytes()); // bits per sample
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data_bytes.to_le_bytes());
    for &s in samples {
        let clamped = s.clamp(-1.0, 1.0);
        let v = (clamped * 32767.0).round() as i16;
        out.extend_from_slice(&v.to_le_bytes());
    }
    out
}

/// Continuous anti-aliased stream resampler using a windowed-sinc low-pass FIR filter
/// when downsampling, keeping seamless state across arbitrary chunk boundaries.
pub struct StreamResampler {
    in_rate: u32,
    out_rate: u32,
    step: f64,
    phase: f64,
    /// Low-pass FIR kernel applied when `in_rate > out_rate` to prevent high-frequency aliasing.
    lpf: Vec<f32>,
    lpf_hist: Vec<f32>,
    prev_sample: f32,
    has_prev: bool,
}

impl StreamResampler {
    pub fn new(in_rate: u32, out_rate: u32) -> Self {
        let in_r = in_rate.max(1);
        let out_r = out_rate.max(1);
        let step = in_r as f64 / out_r as f64;
        let lpf = if in_r > out_r {
            // 15-tap Hamming-windowed sinc low-pass at 0.45 * (out_rate / in_rate) * Nyquist
            let taps = 15usize;
            let half = (taps / 2) as isize;
            let cutoff = 0.45 * (out_r as f32 / in_r as f32);
            let mut kernel = Vec::with_capacity(taps);
            let mut sum = 0.0f32;
            for i in 0..taps {
                let n = i as isize - half;
                let sinc = if n == 0 {
                    2.0 * cutoff
                } else {
                    let x = 2.0 * PI * cutoff * n as f32;
                    x.sin() / (PI * n as f32)
                };
                let w = 0.54 - 0.46 * (2.0 * PI * i as f32 / (taps - 1) as f32).cos();
                let v = sinc * w;
                kernel.push(v);
                sum += v;
            }
            if sum.abs() > 1e-6 {
                for v in &mut kernel {
                    *v /= sum;
                }
            }
            kernel
        } else {
            Vec::new()
        };
        let hist_len = lpf.len().saturating_sub(1);
        Self {
            in_rate: in_r,
            out_rate: out_r,
            step,
            phase: 0.0,
            lpf,
            lpf_hist: vec![0.0; hist_len],
            prev_sample: 0.0,
            has_prev: false,
        }
    }

    pub fn in_rate(&self) -> u32 {
        self.in_rate
    }

    pub fn out_rate(&self) -> u32 {
        self.out_rate
    }

    pub fn reset(&mut self) {
        self.phase = 0.0;
        self.lpf_hist.fill(0.0);
        self.prev_sample = 0.0;
        self.has_prev = false;
    }

    /// Resample mono `f32` PCM frames continuously across chunks.
    pub fn process_f32(&mut self, input: &[f32]) -> Vec<f32> {
        if input.is_empty() {
            return Vec::new();
        }
        if self.in_rate == self.out_rate {
            return input.to_vec();
        }

        // 1. Optional anti-aliasing FIR low-pass when downsampling.
        let filtered_buf: Vec<f32>;
        let src: &[f32] = if !self.lpf.is_empty() {
            let taps = self.lpf.len();
            let hist_len = self.lpf_hist.len();
            let mut work = Vec::with_capacity(hist_len + input.len());
            work.extend_from_slice(&self.lpf_hist);
            work.extend_from_slice(input);

            filtered_buf = (0..input.len())
                .map(|i| {
                    let mut acc = 0.0f32;
                    for (k, &coeff) in self.lpf.iter().enumerate() {
                        acc += work[i + k] * coeff;
                    }
                    acc
                })
                .collect();

            // Save trailing history for the next chunk.
            let tail_start = work.len() - hist_len;
            self.lpf_hist.copy_from_slice(&work[tail_start..]);
            debug_assert_eq!(taps, hist_len + 1);
            &filtered_buf
        } else {
            input
        };

        // 2. Continuous linear fractional-phase resampling.
        let est = ((src.len() as f64 / self.step).ceil() as usize) + 2;
        let mut out = Vec::with_capacity(est);
        let n = src.len();
        let mut pos = self.phase;

        while pos < n as f64 {
            let idx = pos.floor() as isize;
            let frac = (pos - idx as f64) as f32;
            let s0 = if idx < 0 {
                if self.has_prev {
                    self.prev_sample
                } else {
                    src[0]
                }
            } else {
                src[idx as usize]
            };
            let s1 = if (idx + 1) < n as isize {
                src[(idx + 1).max(0) as usize]
            } else {
                // Wait until next chunk supplies the sample past the boundary.
                break;
            };
            out.push(s0 + (s1 - s0) * frac);
            pos += self.step;
        }

        self.prev_sample = src[n - 1];
        self.has_prev = true;
        self.phase = pos - n as f64;
        out
    }

    /// Resample mono `i16` PCM frames into normalized `f32` (`[-1.0, 1.0]`).
    pub fn process_i16(&mut self, input: &[i16]) -> Vec<f32> {
        let floats: Vec<f32> = input.iter().map(|&s| s as f32 / 32768.0).collect();
        self.process_f32(&floats)
    }
}

/// Pluggable Voice Activity Detection interface (implemented by Silero VAD or energy VAD).
pub trait VadDetector: Send {
    /// Returns probability `[0.0, 1.0]` that `frame` (typically 512 samples @ 16 kHz) contains speech.
    fn speech_probability(&mut self, frame: &[f32]) -> f32;
    fn reset(&mut self);
}

/// Fast adaptive-threshold RMS + zero-crossing VAD for offline testing and zero-dependency fallback.
pub struct AdaptiveEnergyVad {
    noise_floor: f32,
    speech_ratio: f32,
    min_rms: f32,
}

impl Default for AdaptiveEnergyVad {
    fn default() -> Self {
        Self {
            noise_floor: 0.008,
            speech_ratio: 2.5,
            min_rms: 0.015,
        }
    }
}

impl VadDetector for AdaptiveEnergyVad {
    fn speech_probability(&mut self, frame: &[f32]) -> f32 {
        let r = rms_level(frame);
        let threshold = (self.noise_floor * self.speech_ratio).max(self.min_rms);
        if r < threshold {
            // Track background noise slowly.
            self.noise_floor = 0.96 * self.noise_floor + 0.04 * r.max(0.002);
            (r / threshold * 0.45).clamp(0.0, 0.49)
        } else {
            (0.55 + ((r - threshold) / (threshold * 2.0)).min(0.45)).clamp(0.5, 1.0)
        }
    }

    fn reset(&mut self) {
        self.noise_floor = 0.008;
    }
}

#[derive(Debug, Clone)]
pub struct SegmenterConfig {
    pub sample_rate: u32,
    pub frame_samples: usize,
    pub speech_threshold: f32,
    pub min_speech_ms: u32,
    pub min_silence_ms: u32,
    pub max_segment_ms: u32,
    pub pre_roll_ms: u32,
}

impl Default for SegmenterConfig {
    fn default() -> Self {
        Self {
            sample_rate: TARGET_SAMPLE_RATE,
            frame_samples: 512,
            speech_threshold: 0.5,
            min_speech_ms: 250,
            min_silence_ms: 550,
            max_segment_ms: 15_000,
            pre_roll_ms: 250,
        }
    }
}

#[derive(Debug, Clone)]
pub struct SpeechSegment {
    pub t0_s: f64,
    pub t1_s: f64,
    pub samples: Vec<f32>,
}

/// Real-time endpointing segmenter with pre-roll ring buffer to avoid clipping initial phonemes.
pub struct SpeechSegmenter<V: VadDetector> {
    cfg: SegmenterConfig,
    vad: V,
    pending_in: Vec<f32>,
    pre_roll: VecDeque<f32>,
    pre_roll_cap: usize,
    active_buf: Vec<f32>,
    in_speech: bool,
    speech_start_sample: u64,
    silence_samples: usize,
    total_samples: u64,
}

impl<V: VadDetector> SpeechSegmenter<V> {
    pub fn new(cfg: SegmenterConfig, vad: V) -> Self {
        let pre_roll_cap = (cfg.sample_rate as usize * cfg.pre_roll_ms as usize) / 1000;
        Self {
            cfg,
            vad,
            pending_in: Vec::new(),
            pre_roll: VecDeque::with_capacity(pre_roll_cap + 512),
            pre_roll_cap,
            active_buf: Vec::new(),
            in_speech: false,
            speech_start_sample: 0,
            silence_samples: 0,
            total_samples: 0,
        }
    }

    pub fn push_samples(&mut self, samples: &[f32]) -> Vec<SpeechSegment> {
        self.pending_in.extend_from_slice(samples);
        let mut emitted = Vec::new();
        let frame_len = self.cfg.frame_samples.max(1);
        let min_speech_samples = (self.cfg.sample_rate as usize * self.cfg.min_speech_ms as usize) / 1000;
        let min_silence_samples = (self.cfg.sample_rate as usize * self.cfg.min_silence_ms as usize) / 1000;
        let max_segment_samples = (self.cfg.sample_rate as usize * self.cfg.max_segment_ms as usize) / 1000;

        while self.pending_in.len() >= frame_len {
            let frame: Vec<f32> = self.pending_in.drain(..frame_len).collect();
            let prob = self.vad.speech_probability(&frame);
            let is_speech = prob >= self.cfg.speech_threshold;

            if !self.in_speech {
                if is_speech {
                    self.in_speech = true;
                    self.silence_samples = 0;
                    let pre_len = self.pre_roll.len() as u64;
                    self.speech_start_sample = self.total_samples.saturating_sub(pre_len);
                    self.active_buf.clear();
                    self.active_buf.extend(self.pre_roll.drain(..));
                    self.active_buf.extend_from_slice(&frame);
                } else {
                    for &s in &frame {
                        if self.pre_roll.len() >= self.pre_roll_cap {
                            self.pre_roll.pop_front();
                        }
                        self.pre_roll.push_back(s);
                    }
                }
            } else {
                self.active_buf.extend_from_slice(&frame);
                if is_speech {
                    self.silence_samples = 0;
                } else {
                    self.silence_samples += frame_len;
                }

                let reached_silence = self.silence_samples >= min_silence_samples;
                let reached_max = self.active_buf.len() >= max_segment_samples;

                if reached_silence || reached_max {
                    let end_sample = self.total_samples + frame_len as u64;
                    let speech_len = self.active_buf.len().saturating_sub(self.silence_samples);
                    if speech_len >= min_speech_samples {
                        let t0_s = self.speech_start_sample as f64 / self.cfg.sample_rate as f64;
                        let t1_s = end_sample as f64 / self.cfg.sample_rate as f64;
                        emitted.push(SpeechSegment {
                            t0_s,
                            t1_s,
                            samples: std::mem::take(&mut self.active_buf),
                        });
                    } else {
                        self.active_buf.clear();
                    }
                    self.in_speech = false;
                    self.silence_samples = 0;
                }
            }

            self.total_samples += frame_len as u64;
        }

        emitted
    }

    /// Flush any remaining active speech buffer at end-of-stream / stop.
    pub fn flush(&mut self) -> Option<SpeechSegment> {
        let min_speech_samples =
            (self.cfg.sample_rate as usize * self.cfg.min_speech_ms as usize) / 1000;
        if self.in_speech && self.active_buf.len() >= min_speech_samples {
            let t0_s = self.speech_start_sample as f64 / self.cfg.sample_rate as f64;
            let t1_s = (self.speech_start_sample + self.active_buf.len() as u64) as f64
                / self.cfg.sample_rate as f64;
            self.in_speech = false;
            self.silence_samples = 0;
            Some(SpeechSegment {
                t0_s,
                t1_s,
                samples: std::mem::take(&mut self.active_buf),
            })
        } else {
            self.active_buf.clear();
            self.in_speech = false;
            None
        }
    }

    pub fn reset(&mut self) {
        self.pending_in.clear();
        self.pre_roll.clear();
        self.active_buf.clear();
        self.in_speech = false;
        self.silence_samples = 0;
        self.speech_start_sample = 0;
        self.total_samples = 0;
        self.vad.reset();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resampler_48k_to_16k_preserves_duration_and_suppresses_aliasing() {
        let mut r = StreamResampler::new(48_000, 16_000);
        // 1 second of 1 kHz sine (in-band) + 18 kHz sine (above 8 kHz Nyquist -> must be attenuated)
        let n = 48_000;
        let in_band: Vec<f32> = (0..n)
            .map(|i| (2.0 * PI * 1000.0 * i as f32 / 48_000.0).sin())
            .collect();
        // Feed in small chunks to verify seamless cross-chunk continuity
        let mut out = Vec::new();
        for chunk in in_band.chunks(960) {
            out.extend(r.process_f32(chunk));
        }
        assert!((out.len() as i32 - 16_000).abs() <= 4);
        let in_rms = rms_level(&out[100..out.len() - 100]);
        assert!(in_rms > 0.60, "1kHz passband rms too low: {in_rms}");

        // Now test 18 kHz out-of-band signal: anti-aliasing FIR must heavily suppress it!
        r.reset();
        let high_freq: Vec<f32> = (0..n)
            .map(|i| (2.0 * PI * 18_000.0 * i as f32 / 48_000.0).sin())
            .collect();
        let aliased = r.process_f32(&high_freq);
        let hf_rms = rms_level(&aliased[100..aliased.len() - 100]);
        assert!(hf_rms < 0.05, "18kHz aliasing not suppressed: {hf_rms}");
    }

    #[test]
    fn segmenter_emits_speech_with_preroll_and_silence_endpoint() {
        let mut seg = SpeechSegmenter::new(SegmenterConfig::default(), AdaptiveEnergyVad::default());
        // 0.5s silence + 0.8s 440Hz tone + 0.8s silence
        let mut audio = vec![0.0f32; 8_000];
        for i in 0..12_800 {
            audio.push(0.4 * (2.0 * PI * 440.0 * i as f32 / 16_000.0).sin());
        }
        audio.extend(vec![0.0f32; 12_800]);

        let mut segments = Vec::new();
        for chunk in audio.chunks(512) {
            segments.extend(seg.push_samples(chunk));
        }
        assert_eq!(segments.len(), 1);
        assert!(segments[0].t0_s < 0.5, "pre-roll should start before 0.5s");
        assert!(segments[0].t1_s > 1.3);
    }

    #[test]
    fn wav_encoder_writes_valid_riff_header_and_clamps_samples() {
        let wav = encode_wav_16k_mono_pcm16(&[0.0, 1.5, -1.5, 0.5]);
        assert_eq!(&wav[0..4], b"RIFF");
        assert_eq!(&wav[8..12], b"WAVE");
        assert_eq!(&wav[36..40], b"data");
        assert_eq!(wav.len(), 44 + 8);
        let s1 = i16::from_le_bytes([wav[46], wav[47]]);
        let s2 = i16::from_le_bytes([wav[48], wav[49]]);
        assert_eq!(s1, 32767);
        assert_eq!(s2, -32767);
    }
}
