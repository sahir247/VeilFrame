"""
Corpus loader and deterministic synthetic evaluation generator for multi-camera attribution benchmarks.
"""
from typing import Dict, List, Tuple
import numpy as np


def generate_synthetic_evaluation_corpus(
    num_cameras: int = 3,
    num_frames: int = 15,
    resolution: Tuple[int, int] = (160, 120),
    seed: int = 42,
) -> Dict[str, Dict[str, List[np.ndarray]]]:
    """
    Generates a deterministic synthetic multi-camera dataset for attribution benchmarking.

    Each camera has:
    - Unique fixed PRNU high-frequency sensor noise pattern K_cam.
    - Scene motion dynamics.
    - Transformed stream with VeilFrame-style bounded perturbations (noise dither, slight crop/scale, color drift).
    """
    rng = np.random.default_rng(seed)
    w, h = resolution
    corpus: Dict[str, Dict[str, List[np.ndarray]]] = {}

    for c in range(num_cameras):
        cam_id = f"camera_{chr(65 + c)}"  # camera_A, camera_B, etc.

        # Unique static sensor fingerprint for this camera (amplitude ~0.02)
        k_sensor = rng.normal(0.0, 0.02, size=(h, w))

        ref_frames: List[np.ndarray] = []
        trans_frames: List[np.ndarray] = []

        # Base scene luminance with motion across time
        for t in range(num_frames):
            y_grid, x_grid = np.ogrid[:h, :w]
            # Dynamic moving gradient pattern
            scene = 0.5 + 0.3 * np.sin((x_grid + t * 2.0) * 0.05) * np.cos((y_grid + t * 1.5) * 0.05)

            # Reference frame = Scene * (1 + K_sensor) + random shot noise
            shot_noise = rng.normal(0.0, 0.005, size=(h, w))
            ref_frame = np.clip(scene * (1.0 + k_sensor) + shot_noise, 0.0, 1.0)
            ref_frames.append(ref_frame)

            # Transformed frame = Subtle perturbation: contrast shift (1.015x), additive PRNU dither (sigma=0.01)
            trans_dither = rng.normal(0.0, 0.012, size=(h, w))
            trans_frame = np.clip((ref_frame - 0.5) * 1.015 + 0.505 + trans_dither, 0.0, 1.0)
            trans_frames.append(trans_frame)

        corpus[cam_id] = {
            "ref": ref_frames,
            "trans": trans_frames,
        }

    return corpus


def generate_synthetic_audio_pair(
    duration_sec: float = 3.0,
    sample_rate: int = 1000,
    enf_freq: float = 50.0,
    snr_db: float = 6.0,
    apply_notch: bool = True,
    notch_depth_db: float = 30.0,
    seed: int = 101,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Generates synthetic audio test pair with injected power-grid ENF hum tone (e.g. 50Hz)
    and an attenuated version simulating notch filtration.
    """
    rng = np.random.default_rng(seed)
    n_samples = int(duration_sec * sample_rate)
    t = np.linspace(0, duration_sec, n_samples, endpoint=False)

    # Ambient audio noise floor (RMS ~ 0.05)
    noise = rng.normal(0.0, 0.05, size=n_samples)

    # Injected ENF pure tone
    tone_amp = 0.05 * (10.0 ** (snr_db / 20.0))
    hum_signal = tone_amp * np.sin(2 * np.pi * enf_freq * t)

    # Reference = ambient noise + mains hum
    ref_audio = np.clip(noise + hum_signal, -1.0, 1.0).astype(np.float32)

    if apply_notch:
        # Attenuate tone by notch_depth_db
        attenuation_factor = 10.0 ** (-notch_depth_db / 20.0)
        trans_hum = hum_signal * attenuation_factor
        trans_audio = np.clip(noise + trans_hum, -1.0, 1.0).astype(np.float32)
    else:
        trans_audio = ref_audio.copy()

    return ref_audio, trans_audio
