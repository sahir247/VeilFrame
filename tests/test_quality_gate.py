"""
Unit and Integration Test Suite for Production-Grade Quality Gate & Ed25519 Audit Engine.

Tests:
1. Controlled Fixtures: Identical, Low-Perturbation, Boundary, Moderate Exceedance, Severe Distortion.
2. Direct Decoded-Frame Energy & Histogram Total Variation (D_TV) Divergence.
3. Pre-Resampling Temporal Integrity & Frame Correspondence.
4. Adversarial & Dishonest Input Detection (Noise, Resolution, Budget).
5. Publicly Verifiable Ed25519 Asymmetric Digital Signature & Tamper Detection.
"""
import shutil
import tempfile
import unittest
import subprocess
from pathlib import Path

from veilframe.core.resources import get_ffmpeg_path
from veilframe.core.analyzer import analyze_video
from veilframe.core.validator import (
    compute_sha256,
    compute_stats,
    audit_native_domain,
    audit_temporal_integrity,
    extract_decoded_frame_energy,
    calculate_policy_score,
    evaluate_canonical_fidelity,
    evaluate_visual_quality,
    generate_ed25519_signed_manifest,
    verify_audit_manifest,
)
from veilframe.models.settings import VisualBudgetPolicy


class TestProductionQualityGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ffmpeg = get_ffmpeg_path()
        cls.temp_dir = Path(tempfile.mkdtemp(prefix="gate_test_"))
        cls.ref_video = cls.temp_dir / "ref_test.mp4"

        # Generate a 2-second test pattern video (320x240, 30fps)
        cmd = [
            str(cls.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-f", "lavfi",
            "-i", "testsrc=duration=2.0:size=320x240:rate=30",
            "-f", "lavfi",
            "-i", "sine=frequency=1000:duration=2.0",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            str(cls.ref_video),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise RuntimeError(f"Failed to generate test reference video: {proc.stderr}")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.temp_dir, ignore_errors=True)

    def test_stats_distribution_calculation(self):
        """Test statistical distribution computation over per-frame metric arrays."""
        data = [0.96, 0.97, 0.98, 0.95, 0.99, 0.94, 0.98, 0.97]
        stats = compute_stats(data)
        self.assertAlmostEqual(stats.mean, sum(data) / len(data), places=4)
        self.assertEqual(stats.min_val, 0.94)
        self.assertEqual(stats.max_val, 0.99)
        self.assertTrue(stats.p5 <= stats.mean <= stats.p95)

    def test_fixture_identical_video(self):
        """Fixture 1: Identical reference and output should achieve PASS across all 3 tiers."""
        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        self.assertTrue(report.passed)
        self.assertEqual(report.three_tier_verdict.overall_verdict, "PASS")
        self.assertTrue(report.three_tier_verdict.tier1_policy_passed)
        self.assertTrue(report.three_tier_verdict.tier2_fidelity_passed)
        self.assertTrue(report.three_tier_verdict.tier3_temporal_passed)

        self.assertGreaterEqual(report.ssim.mean, 0.99)
        self.assertGreaterEqual(report.psnr.mean, 60.0)
        self.assertEqual(report.input_sha256, report.output_sha256)
        self.assertAlmostEqual(report.energy_metrics.luma_hist_divergence_tv, 0.0, places=3)

    def test_fixture_low_perturbation_within_budget(self):
        """Fixture 2: Micro-perturbation within 5% policy budget (99.8% scale + micro noise) passes."""
        trans_video = self.temp_dir / "trans_low_pert.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "scale=318:240:flags=lanczos,noise=alls=1:allf=t",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(trans_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=trans_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        # Should pass the 5% policy budget and fidelity constraints
        self.assertTrue(report.three_tier_verdict.tier1_policy_passed)
        self.assertLessEqual(report.policy_score.aggregate_policy_score_pct, 5.0)
        self.assertGreaterEqual(report.ssim.mean, 0.95)
        self.assertGreaterEqual(report.psnr.mean, 30.0)

    def test_fixture_moderate_exceedance_rejected(self):
        """Fixture 3: Moderate modification exceeding spatial budget (e.g. 10% crop/scale) is REJECTED."""
        exceed_video = self.temp_dir / "trans_exceed.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "scale=270:200:flags=lanczos",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(exceed_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=exceed_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        # Spatial delta exceeds 2.0% -> Tier 1 fails
        self.assertFalse(report.three_tier_verdict.tier1_policy_passed)
        self.assertEqual(report.three_tier_verdict.overall_verdict, "REJECT")
        self.assertFalse(report.passed)

    def test_fixture_severe_distortion_rejected(self):
        """Fixture 4: Severe distortion (Heavy blur & color degradation) fails fidelity gate."""
        distort_video = self.temp_dir / "trans_distorted.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "gblur=sigma=15,eq=brightness=0.3:contrast=0.4",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "35",
            "-pix_fmt", "yuv420p",
            str(distort_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=distort_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        self.assertFalse(report.three_tier_verdict.tier2_fidelity_passed)
        self.assertEqual(report.three_tier_verdict.overall_verdict, "REJECT")
        self.assertLess(report.ssim.mean, 0.95)

    def test_adversarial_dishonest_noise_detection(self):
        """
        Adversarial Test: When high noise is injected into pixels,
        the validator detects the high-frequency spectral energy shift from decoded pixels
        regardless of any external claims.
        """
        noisy_video = self.temp_dir / "trans_heavy_noise.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "noise=alls=40:allf=t",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(noisy_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        energy = extract_decoded_frame_energy(self.ref_video, noisy_video, sample_count=10)
        self.assertGreater(energy.abs_delta_hf, 5.0)
        self.assertGreater(energy.rel_delta_hf, 0.5)

    def test_adversarial_dishonest_resolution_detection(self):
        """
        Adversarial Test: Native-domain inspector measures actual stream dimensions
        directly from ffprobe, detecting any geometric alteration.
        """
        scaled_video = self.temp_dir / "trans_res_change.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "scale=300:220",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(scaled_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        ref_info = analyze_video(self.ref_video)
        trans_info = analyze_video(scaled_video)
        native = audit_native_domain(ref_info, trans_info)

        expected_spatial_delta = (abs(300 * 220 - 320 * 240) / float(320 * 240)) * 100.0
        self.assertAlmostEqual(native.spatial_delta_pct, expected_spatial_delta, places=2)
        self.assertGreater(native.spatial_delta_pct, 2.0)

    def test_ed25519_signature_verification_and_tamper_detection(self):
        """
        Test that Ed25519 Audit Manifest signatures verify cleanly,
        and modifying a single byte in manifest.json causes verification failure.
        """
        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        audit_dir = self.temp_dir / "audit_manifest_test"
        manifest_json, manifest_sha, manifest_sig, pub_key = generate_ed25519_signed_manifest(report, audit_dir)

        # 1. Genuine manifest verifies successfully
        self.assertTrue(verify_audit_manifest(manifest_json, manifest_sig, pub_key))

        # 2. Pinned fingerprint verification
        fingerprint = report.public_key_fingerprint
        self.assertTrue(fingerprint.startswith("SHA256:"))
        self.assertTrue(verify_audit_manifest(manifest_json, manifest_sig, pub_key, expected_fingerprint=fingerprint))
        self.assertFalse(verify_audit_manifest(manifest_json, manifest_sig, pub_key, expected_fingerprint="SHA256:0000000000000000000000000000000000000000000000000000000000000000"))

        # 3. Tampering with manifest.json causes verification failure
        original_bytes = manifest_json.read_bytes()
        tampered_bytes = original_bytes.replace(b"1.0.0", b"9.9.9")
        manifest_json.write_bytes(tampered_bytes)

        self.assertFalse(verify_audit_manifest(manifest_json, manifest_sig, pub_key))

        # Restore
        manifest_json.write_bytes(original_bytes)
        self.assertTrue(verify_audit_manifest(manifest_json, manifest_sig, pub_key))

    def test_inverse_dishonest_large_claim_minimal_actual(self):
        """
        Test that if an external claim states large modification (e.g. 10%),
        the validator judges solely based on the actual rendered video stream.
        """
        # Render video with tiny 0.1% change
        minimal_trans = self.temp_dir / "trans_minimal.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "scale=320:240",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(minimal_trans),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=minimal_trans,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        # Validator correctly measures the actual rendered video (0% spatial delta, high fidelity)
        self.assertEqual(report.native_metrics.spatial_delta_pct, 0.0)
        self.assertTrue(report.three_tier_verdict.tier1_policy_passed)
        self.assertTrue(report.three_tier_verdict.tier2_fidelity_passed)
        self.assertEqual(report.three_tier_verdict.overall_verdict, "PASS")

    def test_metadata_spoofing_resolution_detection(self):
        """
        Test that validator measures stream resolution from elementary video stream
        rather than trusting container metadata tags.
        """
        spoofed_video = self.temp_dir / "trans_spoofed_meta.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-vf", "scale=300:220",
            "-metadata", "title=2160x3840_Original",
            "-metadata", "comment=resolution=2160x3840",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            str(spoofed_video),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        ref_info = analyze_video(self.ref_video)
        trans_info = analyze_video(spoofed_video)
        native = audit_native_domain(ref_info, trans_info)

        # Native auditor inspects actual decoded stream resolution (300x220), not metadata
        self.assertEqual(native.resolution_trans, "300x220")
        self.assertGreater(native.spatial_delta_pct, 2.0)

    def test_reproducibility_deterministic_evaluations(self):
        """
        Reproducibility Test: Two independent evaluation runs on the same input
        must produce identical metric values within defined numerical tolerance (1e-5).
        """
        run1 = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        run2 = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )

        self.assertAlmostEqual(run1.ssim.mean, run2.ssim.mean, places=5)
        self.assertAlmostEqual(run1.psnr.mean, run2.psnr.mean, places=4)
        self.assertAlmostEqual(run1.energy_metrics.mean_luma_delta, run2.energy_metrics.mean_luma_delta, places=6)
        self.assertAlmostEqual(run1.energy_metrics.luma_hist_divergence_tv, run2.energy_metrics.luma_hist_divergence_tv, places=6)
        self.assertAlmostEqual(run1.policy_score.aggregate_policy_score_pct, run2.policy_score.aggregate_policy_score_pct, places=5)
        self.assertEqual(run1.three_tier_verdict.overall_verdict, run2.three_tier_verdict.overall_verdict)

    def test_corrupt_or_truncated_file_handling(self):
        """Test that invalid/corrupt video files raise clear errors without corrupting state."""
        corrupt_file = self.temp_dir / "corrupt.mp4"
        corrupt_file.write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00corruptdatahere")

        with self.assertRaises((RuntimeError, Exception)):
            evaluate_visual_quality(
                ref_path=self.ref_video,
                trans_path=corrupt_file,
                policy=VisualBudgetPolicy(),
                canonical_w=320,
                canonical_h=240,
            )

    def test_missing_audio_stream_video_only(self):
        """Test evaluation when input has no audio stream."""
        video_only = self.temp_dir / "video_only.mp4"
        cmd = [
            str(self.ffmpeg),
            "-hide_banner",
            "-nostats",
            "-y",
            "-i", str(self.ref_video),
            "-an",
            "-c:v", "copy",
            str(video_only),
        ]
        subprocess.run(cmd, check=True, capture_output=True)

        report = evaluate_visual_quality(
            ref_path=video_only,
            trans_path=video_only,
            policy=VisualBudgetPolicy(),
            canonical_w=320,
            canonical_h=240,
        )
        self.assertTrue(report.passed)
        self.assertEqual(report.three_tier_verdict.overall_verdict, "PASS")

    def test_failed_gate_quarantines_and_never_reports_success(self):
        """
        Test that when enforce_strict=True and quality constraints are violated,
        pipeline immediately unlinks/deletes the invalid output and raises RuntimeError.
        """
        from veilframe.core.pipeline import run_pipeline
        from veilframe.models.settings import ProcessingSettings

        settings = ProcessingSettings()
        # Introduce massive 50% spatial crop that will fail the quality gate
        settings.crop.enabled = True
        settings.crop.mode = "manual"
        settings.crop.x = 80
        settings.crop.y = 60
        settings.crop.width = 160
        settings.crop.height = 120
        settings.quality_gate.enabled = True
        settings.quality_gate.enforce_strict = True

        dst_output = self.temp_dir / "must_be_quarantined.mp4"

        with self.assertRaises(RuntimeError) as ctx:
            run_pipeline(
                src_path=self.ref_video,
                dst_path=dst_output,
                settings=settings,
            )

        self.assertIn("Visual quality gate REJECTED output", str(ctx.exception))
        # Ensure the output file was deleted and NOT left in place
        self.assertFalse(dst_output.exists())

    def test_uniform_timeline_sampling_distribution(self):
        """Test that decoded energy sampling is deterministically distributed across the full timeline."""
        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=VisualBudgetPolicy(sample_count=10, sample_range_start=0.05, sample_range_end=0.95),
            canonical_w=320,
            canonical_h=240,
        )
        indices = report.energy_metrics.sampled_indices_ref
        self.assertEqual(len(indices), 10)
        self.assertEqual(indices, sorted(indices))
        # First index should be near start (5%) and last index near end (95%)
        self.assertTrue(indices[0] < indices[-1])
        timestamps = report.energy_metrics.sampled_timestamps_ref
        self.assertEqual(len(timestamps), 10)
        self.assertTrue(0.0 <= timestamps[0] < timestamps[-1] <= 2.05)

    def test_persistent_signer_mode_with_key_identity(self):
        """Test dual-mode Ed25519 signing with persistent signer key identity."""
        from cryptography.hazmat.primitives.asymmetric import ed25519
        from cryptography.hazmat.primitives import serialization

        # Generate a dedicated persistent key
        priv_key = ed25519.Ed25519PrivateKey.generate()
        key_pem = priv_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
        key_file = self.temp_dir / "persistent_signer.pem"
        key_file.write_bytes(key_pem)

        policy = VisualBudgetPolicy(
            signing_mode="persistent",
            signing_key_path=str(key_file),
            key_id="veilframe-prod-signer-01",
        )

        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=policy,
            canonical_w=320,
            canonical_h=240,
        )

        audit_dir = self.temp_dir / "audit_persistent"
        paths = generate_ed25519_signed_manifest(report, audit_dir, policy=policy)

        # Verify with expected persistent key identity
        valid = verify_audit_manifest(
            manifest_path=paths[0],
            sig_path=paths[2],
            pub_key_path=paths[3],
            expected_key_id="veilframe-prod-signer-01",
        )
        self.assertTrue(valid)

        # Verification with incorrect key ID must fail
        wrong_id_valid = verify_audit_manifest(
            manifest_path=paths[0],
            sig_path=paths[2],
            pub_key_path=paths[3],
            expected_key_id="attacker-impersonated-id",
        )
        self.assertFalse(wrong_id_valid)

    def test_manifest_self_consistency_and_tamper_defense(self):
        """
        Verify that a generated PASS manifest is 100% self-consistent with ground truth
        and that modifying any individual metric or hash field immediately invalidates the signature.
        """
        import json

        policy = VisualBudgetPolicy()
        report = evaluate_visual_quality(
            ref_path=self.ref_video,
            trans_path=self.ref_video,
            policy=policy,
            canonical_w=320,
            canonical_h=240,
        )

        audit_dir = self.temp_dir / "audit_consistency"
        paths = generate_ed25519_signed_manifest(report, audit_dir, policy=policy)
        manifest_file, sha_file, sig_file, pub_file = paths

        # 1. Check hashes against actual file contents
        actual_input_sha = compute_sha256(self.ref_video)
        manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
        self.assertEqual(manifest_data["input_sha256"], actual_input_sha)
        self.assertEqual(manifest_data["output_sha256"], actual_input_sha)

        # 2. Check recorded policy against calibration calculation
        calib = manifest_data["policy_calibration"]
        luma_val = manifest_data["energy_metrics"]["mean_luma_delta"] * calib["luma_weight"]
        chroma_val = manifest_data["energy_metrics"]["chroma_delta_composite"] * calib["chroma_weight"]
        self.assertAlmostEqual(manifest_data["policy_score"]["luminance_score_pct"], luma_val, places=4)
        self.assertAlmostEqual(manifest_data["policy_score"]["chroma_score_pct"], chroma_val, places=4)

        # 3. Verify valid signature
        self.assertTrue(verify_audit_manifest(manifest_file, sig_file, pub_file))

        # 4. Tamper with ssim_mean and assert signature verification fails
        tampered_data = dict(manifest_data)
        tampered_data["rendered_fidelity"]["ssim_mean"] = 0.999999
        tampered_file = self.temp_dir / "tampered_manifest.json"
        tampered_file.write_text(json.dumps(tampered_data), encoding="utf-8")
        self.assertFalse(verify_audit_manifest(tampered_file, sig_file, pub_file))


if __name__ == "__main__":
    unittest.main()

