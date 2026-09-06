"""
Unit Tests for VeilFrame VMAF Measurement Control & Alignment Engine.
====================================================================
Verifies:
  - Exact frame alignment verification
  - Dropped frame detection fails closed
  - Duplicated frame detection fails closed
  - Frame rate / timestamp mismatch fails closed
  - Duration mismatch fails closed
  - Color range / matrix mismatch detection
"""
import pytest
from tools.vmaf_measurement_control import (
    StreamMetadata,
    audit_frame_alignment,
    audit_color_domain,
)


def _make_dummy_stream(
    filename="test.mp4",
    nb_frames=1253,
    duration=52.208,
    fps=24.0,
    width=1920,
    height=1080,
    pix_fmt="yuv420p",
    color_range="tv",
    color_space="bt709",
    color_primaries="bt709",
    color_transfer="bt709",
    bits=8,
) -> StreamMetadata:
    return StreamMetadata(
        filename=filename,
        filepath=f"/mock/{filename}",
        sha256="0" * 64,
        file_size_bytes=1000000,
        duration_sec=duration,
        bit_rate=4000000,
        container_format="mp4",
        codec="h264",
        codec_tag="avc1",
        width=width,
        height=height,
        r_frame_rate=f"{int(fps)}/1",
        avg_frame_rate=f"{int(fps)}/1",
        fps=fps,
        time_base=f"1/{int(fps*1000)}",
        nb_frames=nb_frames,
        pixel_format=pix_fmt,
        bits_per_raw_sample=bits,
        color_range=color_range,
        color_space=color_space,
        color_primaries=color_primaries,
        color_transfer=color_transfer,
        chroma_location="center",
        chroma_subsampling="4:2:0",
    )


def test_frame_alignment_matching_pair():
    ref = _make_dummy_stream("ref.y4m", nb_frames=1253, duration=52.208)
    dist = _make_dummy_stream("dist.mp4", nb_frames=1253, duration=52.208)

    res = audit_frame_alignment(ref, dist)
    assert res.is_aligned is True
    assert res.delta_frames == 0
    assert not res.dropped_frames_detected
    assert not res.duplicated_frames_detected
    assert len(res.errors) == 0


def test_dropped_frame_fails_closed():
    ref = _make_dummy_stream("ref.y4m", nb_frames=1253, duration=52.208)
    dist = _make_dummy_stream("dist.mp4", nb_frames=1252, duration=52.166)  # 1 frame dropped

    res = audit_frame_alignment(ref, dist)
    assert res.is_aligned is False
    assert res.dropped_frames_detected is True
    assert res.delta_frames == -1
    assert any("Dropped frames detected" in err for err in res.errors)


def test_duplicated_frame_fails_closed():
    ref = _make_dummy_stream("ref.y4m", nb_frames=1253, duration=52.208)
    dist = _make_dummy_stream("dist.mp4", nb_frames=1254, duration=52.250)  # 1 extra frame

    res = audit_frame_alignment(ref, dist)
    assert res.is_aligned is False
    assert res.duplicated_frames_detected is True
    assert res.delta_frames == 1
    assert any("Duplicated frames detected" in err for err in res.errors)


def test_fps_mismatch_fails_closed():
    ref = _make_dummy_stream("ref.y4m", fps=24.0)
    dist = _make_dummy_stream("dist.mp4", fps=25.0)

    res = audit_frame_alignment(ref, dist)
    assert res.is_aligned is False
    assert any("Frame rate mismatch" in err for err in res.errors)


def test_duration_mismatch_fails_closed():
    ref = _make_dummy_stream("ref.y4m", duration=52.208)
    dist = _make_dummy_stream("dist.mp4", duration=55.000)

    res = audit_frame_alignment(ref, dist)
    assert res.is_aligned is False
    assert any("Duration mismatch exceeds tolerance" in err for err in res.errors)


def test_color_domain_equivalence_pass():
    ref = _make_dummy_stream("ref.y4m", color_range="tv", color_space="bt709")
    dist = _make_dummy_stream("dist.mp4", color_range="tv", color_space="bt709")

    res = audit_color_domain(ref, dist)
    assert res.is_equivalent is True
    assert res.range_match is True
    assert res.matrix_match is True
    assert len(res.warnings) == 0


def test_color_range_mismatch_detected():
    ref = _make_dummy_stream("ref.y4m", color_range="tv")
    dist = _make_dummy_stream("dist.mp4", color_range="pc")  # Full range vs limited!

    res = audit_color_domain(ref, dist)
    assert res.is_equivalent is False
    assert res.range_match is False
    assert any("Color range mismatch" in w for w in res.warnings)


def test_color_space_matrix_mismatch_detected():
    ref = _make_dummy_stream("ref.y4m", color_space="bt709")
    dist = _make_dummy_stream("dist.mp4", color_space="smpte170m")  # Matrix mismatch!

    res = audit_color_domain(ref, dist)
    assert res.is_equivalent is False
    assert res.matrix_match is False
    assert any("Color space mismatch" in w for w in res.warnings)


def test_bit_depth_mismatch_recorded():
    ref = _make_dummy_stream("ref.y4m", bits=10)
    dist = _make_dummy_stream("dist.mp4", bits=8)

    res = audit_color_domain(ref, dist)
    assert res.bit_depth_match is False
    assert any("Bit depth difference" in w for w in res.warnings)
