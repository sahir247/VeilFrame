"""
Datasets package for attribution benchmarks.
"""
from .manifest import DatasetEntry, DatasetManifest
from .corpus import (
    generate_synthetic_evaluation_corpus,
    generate_synthetic_audio_pair,
)

__all__ = [
    "DatasetEntry",
    "DatasetManifest",
    "generate_synthetic_evaluation_corpus",
    "generate_synthetic_audio_pair",
]
