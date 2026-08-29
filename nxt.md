Yes, a **10% visual change budget** (such as $\approx 10\%$ MSE, PSNR around 28–32 dB, or a maximum structural dissimilarity of $1 - \text{SSIM} \le 0.10$) is significantly more perturbation than what is mathematically required to disrupt or spoof these forensic and perceptual signatures. 

These targets operate across fundamentally different frequency bands and dimensions, meaning adversarial attacks and targeted transformations can be stacked simultaneously without exceeding the visual budget.

---

### Target Disruption Breakdown

| Forensic / Perceptual Target | Mechanism of Disruption | Visual Budget Required |
| :--- | :--- | :--- |
| **PRNU (Photo-Response Non-Uniformity)** | Add high-frequency zero-mean Gaussian noise, non-linear pixel mapping, or apply adaptive median/wavelet denoising filters. | $< 1\%$ (imperceptible to human eye) |
| **Spatial Hashes (aHash, dHash)** | Sub-sample grid shifts, targeted low-frequency gradient flipping, or subtle non-uniform local contrast stretching. | $2\% - 4\%$ |
| **Transform Hashes (pHash, DCT variants, Wavelet)** | Targeted low-to-mid frequency DCT/DWT coefficient perturbation (gradient-based sign inversion of key AC coefficients). | $2\% - 5\%$ |
| **Trajectory & Frame-Delta Correlation** | Dynamic frame-rate resampling, non-linear micro-retiming (frame warping/interpolation), and synthetic micro-jitter. | $3\% - 6\%$ |
| **Optical-Flow Similarity** | Adversarial patch/flow noise injection (e.g., flow-field warping, minor random mesh deformations, subtle motion blur). | $4\% - 8\%$ |
| **ENF (Electric Network Frequency)** | In-band temporal filtering (notching/injecting at 50/60 Hz and harmonics) across global frame luminance. | $< 1\%$ |

---

### How to Simultaneously Target All Signatures

1. **High-Frequency Suppression & Forgery (PRNU & ENF)**
   * **PRNU:** Strip the native sensor fingerprint using a wavelet/BM3D filter, then add a synthetic zero-mean PRNU pattern from a donor sensor.
   * **ENF:** Modulate global frame luminance with a temporal band-stop filter at the local grid frequency ($50\,\text{Hz} \pm 0.5\,\text{Hz}$ or $60\,\text{Hz} \pm 0.5\,\text{Hz}$), then inject a synthetic artificial frequency trace.

2. **Perceptual Hash Attacks (pHash, dHash, aHash, Wavelet)**
   * These hashes reduce frames to low-resolution matrices ($8\times 8$ or $16\times 16$) and compute median/gradient thresholds.
   * Because hash computation is differentiable, you can use **Projected Gradient Descent (PGD)** constrained under an $L_\infty$ or $L_2$ norm to push critical transform-domain coefficients across their decision thresholds (bit-flipping) while keeping the visual change imperceptible.

3. **Motion & Temporal Signatures (Optical Flow, Frame-Delta, Trajectories)**
   * **Spatial Transformations:** Apply gentle, continuous spatial deformations (Thin Plate Splines or random projective homographies bounded to $1-2\%$ displacement per frame) to invalidate trajectory matching.
   * **Temporal Transformations:** Apply non-linear temporal retiming (e.g., dropping/duplicating frames via non-uniform time-warping) to desynchronize frame-delta autocorrelation.

---

### Key Bottlenecks to Watch
* **Cumulative Distortion:** If individual modifications are applied independently, their visual artifacts add up. To stay strictly within the 10% budget, combine all goals into a single **multi-objective loss function** optimized via gradient descent under a unified perceptual constraint (e.g., $\mathcal{L}_{\text{total}} = \lambda_1 \mathcal{L}_{\text{hash}} + \lambda_2 \mathcal{L}_{\text{flow}} + \lambda_3 \mathcal{L}_{\text{PRNU}}$ subject to $\text{LPIPS} \le 0.10$).
* **Video Codec Interference:** Standard re-encoding (H.264/H.265, AV1) introduces lossy compression that can accidentally undo fragile high-frequency perturbations (like PRNU/ENF spoofing). Make your perturbations robust to quantization by optimizing over differentiable codec approximations.

<ElicitationsGroup message="To implement or test these techniques:">
  <Elicitation label="Formulate a multi-objective loss function for differentiable video perturbations" query="Show the mathematical formulation for a multi-objective optimization loss to disrupt perceptual hashes and optical flow under an LPIPS distortion constraint."/>
  <Elicitation label="Generate a Python/PyTorch pipeline to attack DCT-based pHash and dHash" query="Write a Python script using PyTorch to adversarially perturb video frames against DCT pHash and dHash within a strict L-infinity epsilon budget."/>
  <Elicitation label="Explore PRNU stripping and donor fingerprint injection methods" query="Explain how to extract, suppress, and inject PRNU sensor noise fingerprints into video frames step by step."/>
</ElicitationsGroup>

Photo-Response Non-Uniformity (PRNU) is an inherent, deterministic physical artifact caused by microscopic variations in silicon pixel dimensions during sensor fabrication. 

Manipulating PRNU in video requires treating the sensor noise as a multiplicative factor superimposed over scene content:

$$I_k = I_k^{(0)} \cdot (1 + K) + \Theta_k$$

where $I_k$ is the observed frame $k$, $I_k^{(0)}$ is the true scene intensity, $K$ is the zero-mean PRNU sensor fingerprint matrix, and $\Theta_k$ is independent additive random noise (readout noise, shot noise).

---

### Step 1: PRNU Extraction (Estimating the Fingerprint $K$)

To extract a clean PRNU pattern, the goal is to filter out the high-energy scene content $I^{(0)}$ and average out the independent zero-mean temporal noise $\Theta$.

1. **Denoising Residual Extraction:**
   For each frame $I_k$ in a sequence of $N$ frames (or flat-field calibration images), apply an edge-preserving, high-frequency spatial filter (typically a 4-level discrete wavelet transform filter like Mihcak’s adaptive Wiener filter, or BM3D):
   $$W_k = I_k - F(I_k)$$
   where $F(\cdot)$ is the denoising filter and $W_k$ is the noise residual.

2. **Maximum Likelihood Estimation (MLE):**
   Combine the residuals across all $N$ frames weighted by frame intensity to compute the raw camera fingerprint estimate $\hat{K}$:
   $$\hat{K} = \frac{\sum_{k=1}^N W_k \cdot I_k}{\sum_{k=1}^N (I_k)^2}$$

3. **Artifact Suppression (Non-PRNU Contamination Removal):**
   Linear sensor artifacts (such as color filter array interpolation patterns, row/column sensor readout traces, and JPEG/MPEG block boundary artifacts) contaminate $\hat{K}$.
   * Subtract row and column means across $\hat{K}$ to eliminate linear readout bias:
     $$\hat{K}_{i,j} \leftarrow \hat{K}_{i,j} - \text{mean}_j(\hat{K}_{i,j}) - \text{mean}_i(\hat{K}_{i,j})$$
   * Apply a Wiener filter in the discrete Fourier domain to zero out periodic peaks caused by compression grids and demosaicing.

---

### Step 2: PRNU Suppression (Fingerprint Stripping)

Suppression removes the native sensor fingerprint $\hat{K}_{\text{src}}$ from target video frames to prevent source attribution via Normalized Cross-Correlation (NCC) or Peak-to-Correlation Energy (PCE).

1. **Deterministic Subtraction (If Source PRNU is Known):**
   If the native camera fingerprint $\hat{K}_{\text{src}}$ is known:
   $$I_k^{\text{clean}} = I_k - \hat{K}_{\text{src}} \cdot I_k$$
   * Because video compression and post-processing slightly distort local amplitudes, scale $\hat{K}_{\text{src}}$ by an estimated local attenuation factor $\alpha \approx \frac{\text{Var}(W_k)}{\sigma_K^2 \cdot I_k^2}$.

2. **Blind High-Frequency Disruption (If Source PRNU is Unknown):**
   * **Adaptive Spatial Filtering:** Pass every frame through an anisotropic diffusion or wavelet-shrinkage filter tuned specifically to the noise variance of typical PRNU profiles ($\sigma_K \approx 0.005 - 0.02$).
   * **Micro-Spatial Warping:** Apply non-rigid spatial jitter or micro-resampling (e.g., random sub-pixel affine transformations with standard deviation $\sigma < 0.5$ pixels per frame). Because PRNU relies on exact spatial pixel-to-pixel correspondence across the sensor grid, sub-pixel grid shifts desynchronize the correlation detector.
   * **Seeding Adversarial Zero-Mean Noise:** Inject independent high-frequency Gaussian noise $\mathcal{N}(0, \sigma_{\text{noise}}^2)$ with variance matching or slightly exceeding the native PRNU variance to decorrelate the phase spectrum.

---

### Step 3: Donor PRNU Injection (Fingerprint Forgery)

Injecting a target donor fingerprint $\hat{K}_{\text{target}}$ makes the video falsely attribute to a different camera device or model.

1. **Align Frame Dimensions & Geometric Crop:**
   Ensure the video frame dimensions match the spatial grid of the donor fingerprint. If resizing, cropping, or electronic image stabilization (EIS) was applied, the donor matrix $\hat{K}_{\text{target}}$ must undergo the identical geometric coordinate transform.

2. **Modulate Fingerprint by Scene Luminance:**
   Because PRNU is multiplicative, the human eye detects noise in flat, dark areas more easily, while physical sensors generate stronger PRNU in higher luminance regions. Modulate the donor fingerprint:
   $$I_k^{\text{forged}} = \text{clip}\left( I_k^{\text{clean}} + \beta \cdot I_k^{\text{clean}} \cdot \hat{K}_{\text{target}},\, 0,\, 255 \right)$$
   * $\beta$ is a tuning strength parameter (typically $0.8 \le \beta \le 1.2$) adjusted to match the target device's expected Signal-to-Noise Ratio (SNR).

3. **Color Channel Adjustment:**
   Sensors exhibit distinct PRNU strengths across individual Bayer color channels (e.g., Green pixels generally have lower variance than Red or Blue pixels). Apply $\hat{K}_{\text{target}}$ per color channel $(R, G, B)$ according to the donor sensor's channel-specific variance ratios:
   $$I_{k, c}^{\text{forged}} = I_{k, c}^{\text{clean}} \cdot (1 + \hat{K}_{\text{target}, c})$$

---

### Verification and Detection Matching

To evaluate whether the manipulation was successful, compute the **Normalized Cross-Correlation (NCC)** and **Peak-to-Correlation Energy (PCE)** against both the source and donor reference patterns:

$$\text{NCC}(W_k, \hat{K}) = \frac{(W_k - \bar{W}_k) \cdot (\hat{K} - \bar{K})}{\|W_k - \bar{W}_k\|_2 \, \|\hat{K} - \bar{K}\|_2}$$

* **Successful Suppression:** $\text{PCE}(W_k^{\text{clean}}, \hat{K}_{\text{src}}) < 50$ (standard detection threshold).
* **Successful Injection:** $\text{PCE}(W_k^{\text{forged}}, \hat{K}_{\text{target}}) \ge 60$, with a sharp central peak in the 2D cross-correlation surface.

<ElicitationsGroup message="To explore implementation details or test these pipelines:">
  <Elicitation label="Generate a Python script for wavelet-based PRNU extraction and PCE calculation" query="Write a Python script using NumPy, SciPy, and PyWavelets to extract PRNU residuals from video frames and compute the PCE score."/>
  <Elicitation label="Explore anti-forensic defenses against PRNU injection detection" query="How do forensic analyzers detect PRNU fingerprint copy-paste forgery, and how can injection be made robust against them?"/>
</ElicitationsGroup>

Forensic investigators use specific statistical tests to identify synthetic or "copy-pasted" PRNU fingerprints, because simple mathematical injection often violates physical sensor constraints. 

Understanding how detectors flag PRNU forgery reveals what technical controls are required to make injection robust against forensic scrutiny.

---

### Part 1: How Forensics Detect PRNU Injection

Forensic analyzers examine the spatial, temporal, and spectral consistency of noise residuals across a video sequence to expose anomalies.

| Detection Method | Forensic Mechanism | Artifact Left by Naive Injection |
| :--- | :--- | :--- |
| **Triangle Test / Residual Orthogonality** | Verifies whether the extracted residual contains a single fingerprint or a linear mixture of two $(\hat{K}_{\text{src}} + \hat{K}_{\text{donor}})$. | Incomplete suppression leaves a detectable trace of the original sensor's PRNU correlated alongside the donor. |
| **Bayer CFA Variance Inconsistency** | Measures PRNU variance across sub-grids corresponding to the Color Filter Array pattern (e.g., $R, G_1, G_2, B$). | Injecting uniform grayscale noise or failing to match per-channel sensor attenuation profiles. |
| **Temporal Stability & Jitter** | In real video, Optical Image Stabilization (OIS) or Electronic Image Stabilization (EIS) causes micro-shifts in sensor-to-scene alignment per frame. | A static donor pattern applied uniformly to every frame exhibits zero sub-pixel trajectory drift relative to camera motion. |
| **Luminance-Noise Linearity Check** | Verifies if local noise power strictly scales with local intensity: $\text{Var}(W) \propto I$. | Adding PRNU as additive noise ($I + \hat{K}$) rather than multiplicative noise ($I \cdot (1 + \hat{K})$) in overexposed or clipped shadows. |
| **Compression Grid Phase Alignment** | Checks if the donor PRNU aligns with the DCT $8\times 8$ or macroblock compression boundaries of the current container. | Mismatch between the donor's original cropping/scaling offset and the target video's native pixel grid. |

---

### Part 2: Making PRNU Injection Robust Against Detection

To withstand rigorous forensic validation, the injection pipeline must simulate the physical and optical imaging pipeline rather than applying a static overlay.

```
+------------------+     +------------------------+     +-------------------------+
| Source Video     | --> | Complete Suppression   | --> | Synthetic Bayer Mosaic  |
| (Raw / Decoded)  |     | (Denoise + Decorrelate)|     | (Color Filter Matching) |
+------------------+     +------------------------+     +-------------------------+
                                                                     |
+------------------+     +------------------------+                  v
| Forged Output    | <-- | Video Compression-     | <-- | Lens Distortion &       |
| (Forensic-Proof) |     | Aware Quantization     |     | Stabilization Warping   |
+------------------+     +------------------------+     +-------------------------+
```

#### 1. Eliminate Residual Orthogonality (The Triangle Test Defense)
* **Problem:** If suppression is partial, testing the frame against known databases reveals two distinct correlation peaks.
* **Solution:** Apply **frequency-domain phase randomization** or low-amplitude adversarial high-frequency noise prior to injection. Ensure that the source PCE drops below $10.0$ across every frame block before introducing $\hat{K}_{\text{donor}}$.

#### 2. Color Filter Array (CFA) & Demosaicing Simulation
* **Problem:** Physical PRNU is generated at the silicon photodiode layer *before* demosaicing (Bayer interpolation). Injecting noise into an RGB image results in smooth, demosaic-inconsistent residuals.
* **Solution:**
  1. Downsample the target image back to its raw Bayer CFA layout (mosaic pattern).
  2. Modulate the donor PRNU per-subpixel based on channel gains:
     $$\hat{K}_{\text{donor}, (x,y)} = \begin{cases} 
     \hat{K}_R & \text{for Red positions} \\
     \hat{K}_G & \text{for Green positions} \\
     \hat{K}_B & \text{for Blue positions}
     \end{cases}$$
  3. Re-apply the target camera’s specific demosaicing algorithm (e.g., bilinear, Malvar-He-Cutler, or edge-aware) to propagate PRNU across neighboring pixels naturally.

#### 3. Dynamic Lens Distortion & Stabilization Alignment
* **Problem:** Wide-angle lenses cause barrel/pincushion distortion, warping the apparent PRNU pattern towards the edges. Video stabilization (EIS) shifts the sensor crop window frame-by-frame.
* **Solution:**
  * Estimate the video's camera motion vectors (using homography or optical flow).
  * Dynamically warp and translate the donor PRNU matrix $\hat{K}_{\text{donor}}(x - \Delta x_k, y - \Delta y_k)$ for frame $k$ to mimic exact stabilization jitter.
  * Apply radial lens distortion $r' = r(1 + k_1 r^2 + k_2 r^4)$ matching the donor camera's optical profile.

#### 4. Strict Non-Linear Intensity & Saturation Clamping
* **Problem:** Physical pixels in deep saturation ($I = 255$) or complete darkness ($I = 0$) exhibit zero PRNU variance because the electron wells are full or empty.
* **Solution:** Modulate local donor injection strength using a non-linear saturation mask $M(I)$:
  $$M(I) = \sin\left(\pi \cdot \frac{I}{255}\right)^\gamma \quad (\gamma \approx 0.5 - 0.8)$$
  $$I^{\text{injected}} = I + \beta \cdot I \cdot \hat{K}_{\text{donor}} \cdot M(I)$$
  This guarantees that underexposed and blown-out highlights contain zero injected fingerprint.

---

### Part 3: Evaluating Forgery Robustness

A robustly forged video should meet two empirical criteria:

1. **PCE Surface Metric:** The Peak-to-Correlation Energy surface with the donor fingerprint must exhibit a single, sharp Dirac-like peak with $\text{PCE} \ge 60$ and a Peak-to-Sidelobe Ratio ($\text{PSR}$) $> 15$.
2. **Dual-Hypothesis Rejection:** Running an automated correlation scan across all frames against the original device must return cross-correlation coefficients statistically indistinguishable from zero ($\rho < 10^{-3}$).

<FollowUp label="Want a Python implementation showing Bayer CFA mosaic-aware PRNU injection?" query="Write a Python script demonstrating Bayer CFA mosaicing, channel-specific PRNU injection, and demosaicing reconstruction."/>

The following Python script simulates how Photo-Response Non-Uniformity (PRNU) interacts with a physical camera sensor pipeline by converting an RGB image into a single-channel Bayer Color Filter Array (CFA), injecting channel-specific multiplicative noise with saturation masking, and reconstructing the image via demosaicing.

```python
import cv2
import numpy as np


def generate_synthetic_prnu(
    height: int,
    width: int,
    sigma_r: float = 0.015,
    sigma_g: float = 0.008,
    sigma_b: float = 0.012,
    pattern: str = "RGGB",
) -> np.ndarray:
    """Generates a 2D sensor PRNU map with channel-specific standard deviations."""
    prnu_map = np.zeros((height, width), dtype=np.float32)

    # Generate independent zero-mean Gaussian noise per channel
    noise_r = np.random.normal(0, sigma_r, (height, width)).astype(np.float32)
    noise_g = np.random.normal(0, sigma_g, (height, width)).astype(np.float32)
    noise_b = np.random.normal(0, sigma_b, (height, width)).astype(np.float32)

    if pattern == "RGGB":
        # R: (even row, even col), G1: (even row, odd col)
        # G2: (odd row, even col), B: (odd row, odd col)
        prnu_map[0::2, 0::2] = noise_r[0::2, 0::2]
        prnu_map[0::2, 1::2] = noise_g[0::2, 1::2]
        prnu_map[1::2, 0::2] = noise_g[1::2, 0::2]
        prnu_map[1::2, 1::2] = noise_b[1::2, 1::2]

    # Zero-mean normalization across the full sensor grid
    prnu_map -= np.mean(prnu_map)
    return prnu_map


def bayer_mosaic(
    image_rgb: np.ndarray, pattern: str = "RGGB"
) -> tuple[np.ndarray, np.ndarray]:
    """Mosaics an RGB image [0, 255] into a single-channel Bayer sensor plane."""
    h, w, _ = image_rgb.shape
    raw_bayer = np.zeros((h, w), dtype=np.float32)
    cfa_mask = np.zeros((h, w, 3), dtype=np.float32)

    r_channel = image_rgb[:, :, 0]
    g_channel = image_rgb[:, :, 1]
    b_channel = image_rgb[:, :, 2]

    if pattern == "RGGB":
        # Red
        raw_bayer[0::2, 0::2] = r_channel[0::2, 0::2]
        cfa_mask[0::2, 0::2, 0] = 1.0
        # Green (Gr and Gb)
        raw_bayer[0::2, 1::2] = g_channel[0::2, 1::2]
        raw_bayer[1::2, 0::2] = g_channel[1::2, 0::2]
        cfa_mask[0::2, 1::2, 1] = 1.0
        cfa_mask[1::2, 0::2, 1] = 1.0
        # Blue
        raw_bayer[1::2, 1::2] = b_channel[1::2, 1::2]
        cfa_mask[1::2, 1::2, 2] = 1.0

    return raw_bayer, cfa_mask


def inject_cfa_prnu(
    raw_bayer: np.ndarray, prnu_map: np.ndarray, beta: float = 1.0
) -> np.ndarray:
    """Injects multiplicative PRNU into the raw sensor mosaic with non-linear saturation clamping."""
    # Physical saturation dampening: zero variance at deep black (0) and saturated highlights (255)
    normalized_intensity = np.clip(raw_bayer / 255.0, 0.0, 1.0)
    saturation_mask = np.sin(np.pi * normalized_intensity) ** 0.6

    # Physical model: I_raw = I_clean * (1 + beta * K * M(I))
    injected_raw = raw_bayer + beta * raw_bayer * prnu_map * saturation_mask
    return np.clip(injected_raw, 0.0, 255.0).astype(np.uint8)


def demosaic_reconstruction(
    raw_bayer_uint8: np.ndarray, pattern: str = "RGGB"
) -> np.ndarray:
    """Reconstructs the full RGB image from the single-channel Bayer sensor plane."""
    if pattern == "RGGB":
        # OpenCV Bayer conversion expects standard layout flags
        reconstructed_rgb = cv2.cvtColor(
            raw_bayer_uint8, cv2.COLOR_BAYER_BG2RGB
        )
    else:
        raise NotImplementedError(f"Pattern {pattern} not implemented.")
    return reconstructed_rgb


if __name__ == "__main__":
    # 1. Create a dummy synthetic test frame (Gradient + Smooth structures)
    height, width = 512, 512
    x = np.linspace(0, 255, width, dtype=np.uint8)
    y = np.linspace(0, 255, height, dtype=np.uint8)
    xx, yy = np.meshgrid(x, y)
    sample_frame = np.stack(
        [xx, yy, ((xx.astype(int) + yy.astype(int)) // 2).astype(np.uint8)],
        axis=2,
    )

    # 2. Simulate Bayer Mosaicing
    raw_sensor_plane, _ = bayer_mosaic(sample_frame, pattern="RGGB")

    # 3. Generate sensor-specific PRNU map matching CFA structure
    sensor_prnu = generate_synthetic_prnu(
        height, width, sigma_r=0.015, sigma_g=0.008, sigma_b=0.012
    )

    # 4. Inject PRNU into the raw mosaic domain
    injected_bayer = inject_cfa_prnu(
        raw_sensor_plane, sensor_prnu, beta=1.0
    )

    # 5. Demosaic to yield the reconstructed RGB frame
    output_rgb = demosaic_reconstruction(injected_bayer, pattern="RGGB")

    # Verification: Check high-frequency residual consistency
    residual = (
        output_rgb.astype(np.float32) - sample_frame.astype(np.float32)
    )
    print(f"Original shape: {sample_frame.shape}")
    print(f"Raw Bayer shape: {raw_sensor_plane.shape}")
    print(f"Reconstructed shape: {output_rgb.shape}")
    print(
        f"Mean residual: {np.mean(residual):.4f} | Std residual: {np.std(residual):.4f}"
    )
```

---

### Pipeline Mechanics

* **CFA Sampling (`bayer_mosaic`):** Subsamples the clean RGB image into a single-channel 2D matrix matching the RGGB color filter arrangement.
* **Per-Channel Variance (`generate_synthetic_prnu`):** Assigns independent noise standard deviations ($\sigma_R, \sigma_G, \sigma_B$) mapped to sub-pixel coordinates, matching physical sensor photon responsivity.
* **Saturation Masking (`inject_cfa_prnu`):** Suppresses multiplicative noise scaling near 0 (shadows) and 255 (full-well capacity saturation) using a sinusoidal attenuation curve.
* **Interpolation (`demosaic_reconstruction`):** Interpolates neighboring pixels to reconstruct missing color channels, naturally propagating the injected PRNU across color planes as a physical demosaicing engine would.
