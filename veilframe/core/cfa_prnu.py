"""
Bayer Color Filter Array (CFA) Mosaic-Aware PRNU Sensor Noise Engine.
====================================================================

Simulates the physical optical camera imaging pipeline:
1. Converts RGB frames into single-channel Bayer sensor planes (RGGB, BGGR, GRBG, GBRG).
2. Generates sub-pixel channel-specific PRNU sensor noise maps matching physical photon responsivities (sigma_R, sigma_G, sigma_B).
3. Applies non-linear saturation clamping M(I) = sin(pi * I / 255)^gamma to suppress noise in deep shadows (0) and saturated highlights (255).
4. Reconstructs full RGB frames via demosaicing to naturally propagate PRNU across color planes without tripping forensic CFA variance checkers.
"""
from typing import Optional, Tuple
import numpy as np

# Try importing OpenCV for accelerated demosaicing if available, otherwise pure NumPy fallback
try:
    import cv2
    _HAS_CV2 = True
except ImportError:
    _HAS_CV2 = False


def generate_synthetic_prnu(
    height: int,
    width: int,
    sigma_r: float = 0.015,
    sigma_g: float = 0.008,
    sigma_b: float = 0.012,
    pattern: str = "RGGB",
    seed: Optional[int] = None,
) -> np.ndarray:
    """
    Generates a 2D sensor PRNU map with channel-specific standard deviations matching CFA layout.
    """
    rng = np.random.default_rng(seed)
    prnu_map = np.zeros((height, width), dtype=np.float32)

    noise_r = rng.normal(0.0, sigma_r, (height, width)).astype(np.float32)
    noise_g = rng.normal(0.0, sigma_g, (height, width)).astype(np.float32)
    noise_b = rng.normal(0.0, sigma_b, (height, width)).astype(np.float32)

    p = pattern.upper()
    if p == "RGGB":
        # R: (even row, even col), G1: (even row, odd col)
        # G2: (odd row, even col), B: (odd row, odd col)
        prnu_map[0::2, 0::2] = noise_r[0::2, 0::2]
        prnu_map[0::2, 1::2] = noise_g[0::2, 1::2]
        prnu_map[1::2, 0::2] = noise_g[1::2, 0::2]
        prnu_map[1::2, 1::2] = noise_b[1::2, 1::2]
    elif p == "BGGR":
        prnu_map[0::2, 0::2] = noise_b[0::2, 0::2]
        prnu_map[0::2, 1::2] = noise_g[0::2, 1::2]
        prnu_map[1::2, 0::2] = noise_g[1::2, 0::2]
        prnu_map[1::2, 1::2] = noise_r[1::2, 1::2]
    elif p == "GRBG":
        prnu_map[0::2, 0::2] = noise_g[0::2, 0::2]
        prnu_map[0::2, 1::2] = noise_r[0::2, 1::2]
        prnu_map[1::2, 0::2] = noise_b[1::2, 0::2]
        prnu_map[1::2, 1::2] = noise_g[1::2, 1::2]
    elif p == "GBRG":
        prnu_map[0::2, 0::2] = noise_g[0::2, 0::2]
        prnu_map[0::2, 1::2] = noise_b[0::2, 1::2]
        prnu_map[1::2, 0::2] = noise_r[1::2, 0::2]
        prnu_map[1::2, 1::2] = noise_g[1::2, 1::2]
    else:
        # Default uniform
        prnu_map = noise_g

    # Zero-mean normalization across full sensor grid
    prnu_map -= np.mean(prnu_map)
    return prnu_map


def bayer_mosaic(
    image_rgb: np.ndarray,
    pattern: str = "RGGB",
) -> Tuple[np.ndarray, np.ndarray]:
    """
    Mosaics an RGB image [0, 255] into a single-channel Bayer sensor plane.
    Returns (raw_bayer, cfa_mask).
    """
    img = np.asarray(image_rgb, dtype=np.float32)
    h, w = img.shape[:2]

    raw_bayer = np.zeros((h, w), dtype=np.float32)
    cfa_mask = np.zeros((h, w, 3), dtype=np.float32)

    r_ch = img[:, :, 0]
    g_ch = img[:, :, 1]
    b_ch = img[:, :, 2]

    p = pattern.upper()
    if p == "RGGB":
        raw_bayer[0::2, 0::2] = r_ch[0::2, 0::2]
        cfa_mask[0::2, 0::2, 0] = 1.0

        raw_bayer[0::2, 1::2] = g_ch[0::2, 1::2]
        cfa_mask[0::2, 1::2, 1] = 1.0

        raw_bayer[1::2, 0::2] = g_ch[1::2, 0::2]
        cfa_mask[1::2, 0::2, 1] = 1.0

        raw_bayer[1::2, 1::2] = b_ch[1::2, 1::2]
        cfa_mask[1::2, 1::2, 2] = 1.0
    elif p == "BGGR":
        raw_bayer[0::2, 0::2] = b_ch[0::2, 0::2]
        cfa_mask[0::2, 0::2, 2] = 1.0

        raw_bayer[0::2, 1::2] = g_ch[0::2, 1::2]
        cfa_mask[0::2, 1::2, 1] = 1.0

        raw_bayer[1::2, 0::2] = g_ch[1::2, 0::2]
        cfa_mask[1::2, 0::2, 1] = 1.0

        raw_bayer[1::2, 1::2] = r_ch[1::2, 1::2]
        cfa_mask[1::2, 1::2, 0] = 1.0
    elif p == "GRBG":
        raw_bayer[0::2, 0::2] = g_ch[0::2, 0::2]
        cfa_mask[0::2, 0::2, 1] = 1.0

        raw_bayer[0::2, 1::2] = r_ch[0::2, 1::2]
        cfa_mask[0::2, 1::2, 0] = 1.0

        raw_bayer[1::2, 0::2] = b_ch[1::2, 0::2]
        cfa_mask[1::2, 0::2, 2] = 1.0

        raw_bayer[1::2, 1::2] = g_ch[1::2, 1::2]
        cfa_mask[1::2, 1::2, 1] = 1.0
    else:  # GBRG
        raw_bayer[0::2, 0::2] = g_ch[0::2, 0::2]
        cfa_mask[0::2, 0::2, 1] = 1.0

        raw_bayer[0::2, 1::2] = b_ch[0::2, 1::2]
        cfa_mask[0::2, 1::2, 2] = 1.0

        raw_bayer[1::2, 0::2] = r_ch[1::2, 0::2]
        cfa_mask[1::2, 0::2, 0] = 1.0

        raw_bayer[1::2, 1::2] = g_ch[1::2, 1::2]
        cfa_mask[1::2, 1::2, 1] = 1.0

    return raw_bayer, cfa_mask


def inject_cfa_prnu(
    raw_bayer: np.ndarray,
    prnu_map: np.ndarray,
    beta: float = 1.0,
    gamma: float = 0.6,
) -> np.ndarray:
    """
    Injects multiplicative PRNU into raw sensor mosaic with non-linear saturation clamping.
    Model: I_injected = I_clean + beta * I_clean * K * sin(pi * I / 255)^gamma
    """
    raw = np.asarray(raw_bayer, dtype=np.float32)
    normalized_intensity = np.clip(raw / 255.0, 0.0, 1.0)
    # Physical saturation dampening: zero variance at black (0) and saturated highlights (255)
    # Clip sin() >= 0 to avoid float precision negative values (-1e-16) producing NaNs with fractional gamma
    sin_val = np.clip(np.sin(np.pi * normalized_intensity), 0.0, 1.0)
    saturation_mask = (sin_val ** gamma).astype(np.float32)

    injected = raw + beta * raw * prnu_map * saturation_mask
    return np.clip(injected, 0.0, 255.0)


def _numpy_demosaic_bilinear(raw_bayer: np.ndarray, pattern: str = "RGGB") -> np.ndarray:
    """
    Pure NumPy bilinear demosaicing reconstruction fallback.
    """
    h, w = raw_bayer.shape
    rgb = np.zeros((h, w, 3), dtype=np.float32)
    p = pattern.upper()

    # Kernel for 3x3 box smoothing with edge reflection
    pad = np.pad(raw_bayer, 1, mode="reflect")

    # Green channel interpolation
    # Positions with native Green
    if p in ("RGGB", "BGGR"):
        g_mask = np.zeros((h, w), dtype=bool)
        g_mask[0::2, 1::2] = True
        g_mask[1::2, 0::2] = True
    else:  # GRBG, GBRG
        g_mask = np.zeros((h, w), dtype=bool)
        g_mask[0::2, 0::2] = True
        g_mask[1::2, 1::2] = True

    g_channel = raw_bayer.copy()
    # At non-green locations, average 4 cross neighbors: (up, down, left, right)
    non_g_mask = ~g_mask
    cross_avg = (pad[:-2, 1:-1] + pad[2:, 1:-1] + pad[1:-1, :-2] + pad[1:-1, 2:]) / 4.0
    g_channel[non_g_mask] = cross_avg[non_g_mask]
    rgb[:, :, 1] = g_channel

    # Red and Blue channel interpolation
    if p == "RGGB":
        r_rows, r_cols = slice(0, None, 2), slice(0, None, 2)
        b_rows, b_cols = slice(1, None, 2), slice(1, None, 2)
    elif p == "BGGR":
        b_rows, b_cols = slice(0, None, 2), slice(0, None, 2)
        r_rows, r_cols = slice(1, None, 2), slice(1, None, 2)
    elif p == "GRBG":
        r_rows, r_cols = slice(0, None, 2), slice(1, None, 2)
        b_rows, b_cols = slice(1, None, 2), slice(0, None, 2)
    else:  # GBRG
        b_rows, b_cols = slice(0, None, 2), slice(1, None, 2)
        r_rows, r_cols = slice(1, None, 2), slice(0, None, 2)

    # Reconstruct Red
    r_img = np.zeros((h, w), dtype=np.float32)
    r_img[r_rows, r_cols] = raw_bayer[r_rows, r_cols]
    # Simple bilinear fill
    pad_r = np.pad(r_img, 1, mode="reflect")
    diag_avg_r = (pad_r[:-2, :-2] + pad_r[:-2, 2:] + pad_r[2:, :-2] + pad_r[2:, 2:]) / 4.0
    horiz_avg_r = (pad_r[1:-1, :-2] + pad_r[1:-1, 2:]) / 2.0
    vert_avg_r = (pad_r[:-2, 1:-1] + pad_r[2:, 1:-1]) / 2.0

    r_mask = np.zeros((h, w), dtype=bool)
    r_mask[r_rows, r_cols] = True
    r_final = r_img.copy()
    r_final[~r_mask] = np.maximum(diag_avg_r, np.maximum(horiz_avg_r, vert_avg_r))[~r_mask]
    rgb[:, :, 0] = r_final

    # Reconstruct Blue
    b_img = np.zeros((h, w), dtype=np.float32)
    b_img[b_rows, b_cols] = raw_bayer[b_rows, b_cols]
    pad_b = np.pad(b_img, 1, mode="reflect")
    diag_avg_b = (pad_b[:-2, :-2] + pad_b[:-2, 2:] + pad_b[2:, :-2] + pad_b[2:, 2:]) / 4.0
    horiz_avg_b = (pad_b[1:-1, :-2] + pad_b[1:-1, 2:]) / 2.0
    vert_avg_b = (pad_b[:-2, 1:-1] + pad_b[2:, 1:-1]) / 2.0

    b_mask = np.zeros((h, w), dtype=bool)
    b_mask[b_rows, b_cols] = True
    b_final = b_img.copy()
    b_final[~b_mask] = np.maximum(diag_avg_b, np.maximum(horiz_avg_b, vert_avg_b))[~b_mask]
    rgb[:, :, 2] = b_final

    return np.clip(rgb, 0.0, 255.0)


def demosaic_reconstruction(raw_bayer: np.ndarray, pattern: str = "RGGB") -> np.ndarray:
    """
    Reconstructs full RGB frame from single-channel Bayer sensor plane.
    """
    raw_u8 = np.clip(raw_bayer, 0.0, 255.0).astype(np.uint8)
    p = pattern.upper()

    if _HAS_CV2:
        flag_map = {
            "RGGB": cv2.COLOR_BAYER_BG2RGB,
            "BGGR": cv2.COLOR_BAYER_RG2RGB,
            "GRBG": cv2.COLOR_BAYER_GB2RGB,
            "GBRG": cv2.COLOR_BAYER_GR2RGB,
        }
        flag = flag_map.get(p, cv2.COLOR_BAYER_BG2RGB)
        try:
            return cv2.cvtColor(raw_u8, flag).astype(np.float32)
        except Exception:
            pass

    return _numpy_demosaic_bilinear(raw_bayer, pattern=p)


def apply_cfa_prnu_pipeline(
    image_rgb: np.ndarray,
    pattern: str = "RGGB",
    beta: float = 1.0,
    sigma_r: float = 0.015,
    sigma_g: float = 0.008,
    sigma_b: float = 0.012,
    gamma: float = 0.6,
    seed: Optional[int] = None,
) -> np.ndarray:
    """
    Executes complete Bayer CFA mosaicing, channel-specific PRNU injection,
    and demosaicing reconstruction on an RGB frame [0, 255].
    """
    h, w = image_rgb.shape[:2]
    raw_bayer, _ = bayer_mosaic(image_rgb, pattern=pattern)
    prnu_map = generate_synthetic_prnu(
        h, w,
        sigma_r=sigma_r,
        sigma_g=sigma_g,
        sigma_b=sigma_b,
        pattern=pattern,
        seed=seed,
    )
    injected_bayer = inject_cfa_prnu(raw_bayer, prnu_map, beta=beta, gamma=gamma)
    return demosaic_reconstruction(injected_bayer, pattern=pattern)
