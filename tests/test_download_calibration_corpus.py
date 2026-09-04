#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tests for tools/download_calibration_corpus.py
Validates provenance manifest parsing, Cardinal Sequence Independence Rule enforcement,
SHA-256 verification, and error handling.
"""

import json
import pytest
from pathlib import Path

from tools.download_calibration_corpus import (
    load_manifest,
    verify_manifest_independence,
    calculate_sha256,
    run_downloader,
)


def test_production_manifest_is_valid():
    manifest_path = Path("calibration/data/corpus_manifest.json")
    assert manifest_path.exists(), "Production manifest file must exist"
    data = load_manifest(manifest_path)
    assert "sequences" in data
    assert len(data["sequences"]) >= 9, "Must register at least 9 open benchmark sequences"

    valid, errors = verify_manifest_independence(data["sequences"])
    assert valid, f"Production manifest violates independence: {errors}"


def test_manifest_independence_rule_detects_duplicate_filenames():
    sequences = [
        {
            "sequence_group_id": "group_a",
            "filename": "clip1.mp4",
            "category": "nature",
            "canonical_url": "https://example.com/clip1.mp4",
        },
        {
            "sequence_group_id": "group_b",
            "filename": "clip1.mp4",  # Duplicate filename
            "category": "nature",
            "canonical_url": "https://example.com/clip1_dupe.mp4",
        }
    ]
    valid, errors = verify_manifest_independence(sequences)
    assert not valid
    assert any("Duplicate filename" in e for e in errors)


def test_manifest_independence_rule_detects_conflicting_categories_for_same_group():
    sequences = [
        {
            "sequence_group_id": "group_shared",
            "filename": "clip1.mp4",
            "category": "nature",
            "canonical_url": "https://example.com/clip1.mp4",
        },
        {
            "sequence_group_id": "group_shared",
            "filename": "clip2.mp4",
            "category": "sports",  # Conflicting category for same scene root
            "canonical_url": "https://example.com/clip2.mp4",
        }
    ]
    valid, errors = verify_manifest_independence(sequences)
    assert not valid
    assert any("Conflicting categories" in e for e in errors)


def test_downloader_dry_run_mode(tmp_path):
    manifest_path = Path("calibration/data/corpus_manifest.json")
    results = run_downloader(
        manifest_path=manifest_path,
        dest_dir=tmp_path,
        dry_run=True,
    )
    assert results["skipped_count"] == 9
    assert results["failed_count"] == 0
    assert results["downloaded_count"] == 0


def test_downloader_verify_only_missing_files(tmp_path):
    manifest_path = Path("calibration/data/corpus_manifest.json")
    results = run_downloader(
        manifest_path=manifest_path,
        dest_dir=tmp_path,
        verify_only=True,
    )
    assert results["failed_count"] == 9
    assert results["verified_count"] == 0
