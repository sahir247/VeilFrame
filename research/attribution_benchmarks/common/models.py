"""
Data models and output schemas for the scientific attribution benchmark framework.
================================================================================

Implements the 3-layer metrics architecture:
1. SignalMetrics: Physical / spectral / matrix transformation measurements.
2. DetectorMetrics: Output of a specific detector algorithm at a defined threshold.
3. AttributionMetrics: Attribution accuracy / classification performance (TPR, FPR, AUC).
4. BenchmarkEnvironment: Reproducibility metadata (hashes, versions, configuration).
"""
from dataclasses import dataclass, field
from typing import Dict, Any, List, Optional, Tuple
import sys
import platform


@dataclass
class BenchmarkEnvironment:
    """System and execution environment details for benchmark reproducibility."""
    implementation: str = "VeilFrame Attribution Benchmark Suite"
    version: str = "0.1.0"
    python_version: str = sys.version.split()[0]
    platform: str = platform.platform()
    numpy_version: str = ""
    ffmpeg_version: str = ""
    dataset_manifest_hash: Optional[str] = None
    reference_sha256: Optional[str] = None
    transformed_sha256: Optional[str] = None
    sampling_configuration: Dict[str, Any] = field(default_factory=dict)
    random_seed: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "implementation": self.implementation,
            "version": self.version,
            "python_version": self.python_version,
            "platform": self.platform,
            "numpy_version": self.numpy_version,
            "ffmpeg_version": self.ffmpeg_version,
            "dataset_manifest_hash": self.dataset_manifest_hash,
            "reference_sha256": self.reference_sha256,
            "transformed_sha256": self.transformed_sha256,
            "sampling_configuration": self.sampling_configuration,
            "random_seed": self.random_seed,
        }


@dataclass
class SignalMetrics:
    """Layer 1: Physical / mathematical signal transformation measurements."""
    name: str
    values: Dict[str, Any] = field(default_factory=dict)
    units: Dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "values": self.values,
            "units": self.units,
        }


@dataclass
class DetectorMetrics:
    """Layer 2: Output of a defined detector matching algorithm at a specific threshold."""
    detector_name: str
    algorithm: str
    match_score: float
    threshold: float
    match_status: str  # "MATCH", "NO_MATCH", "INCONCLUSIVE", "UNAVAILABLE"
    decision_margin: float = 0.0
    parameters: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "detector_name": self.detector_name,
            "algorithm": self.algorithm,
            "match_score": self.match_score,
            "threshold": self.threshold,
            "match_status": self.match_status,
            "decision_margin": self.decision_margin,
            "parameters": self.parameters,
        }


@dataclass
class AttributionMetrics:
    """Layer 3: Empirical attribution classification and statistical performance."""
    evaluated_pairs: int = 1
    true_positive_rate: Optional[float] = None
    false_positive_rate: Optional[float] = None
    area_under_curve: Optional[float] = None
    confidence_interval_95: Optional[Tuple[float, float]] = None
    classification: Optional[str] = None  # "TRUE_POSITIVE", "FALSE_NEGATIVE", etc.
    summary: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "evaluated_pairs": self.evaluated_pairs,
            "true_positive_rate": self.true_positive_rate,
            "false_positive_rate": self.false_positive_rate,
            "area_under_curve": self.area_under_curve,
            "confidence_interval_95": self.confidence_interval_95,
            "classification": self.classification,
            "summary": self.summary,
        }


@dataclass
class BenchmarkResult:
    """Standardized 3-layer benchmark result for a single detector suite."""
    benchmark_name: str
    benchmark_version: str
    status: str  # "success", "skipped", "unavailable", "error"
    signal_metrics: SignalMetrics
    detector_metrics: DetectorMetrics
    attribution_metrics: AttributionMetrics
    environment: BenchmarkEnvironment
    error_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "benchmark": {
                "name": self.benchmark_name,
                "version": self.benchmark_version,
                "status": self.status,
                "error": self.error_message,
            },
            "signal_metrics": self.signal_metrics.to_dict(),
            "detector_metrics": self.detector_metrics.to_dict(),
            "attribution_metrics": self.attribution_metrics.to_dict(),
            "environment": self.environment.to_dict(),
        }


@dataclass
class BenchmarkSuiteReport:
    """Comprehensive multi-detector benchmark suite report."""
    suite_version: str = "0.1.0"
    timestamp_utc: str = ""
    benchmarks: Dict[str, BenchmarkResult] = field(default_factory=dict)
    summary_findings: Dict[str, Any] = field(default_factory=dict)
    environment: BenchmarkEnvironment = field(default_factory=BenchmarkEnvironment)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "suite_version": self.suite_version,
            "timestamp_utc": self.timestamp_utc,
            "environment": self.environment.to_dict(),
            "benchmarks": {k: v.to_dict() for k, v in self.benchmarks.items()},
            "summary_findings": self.summary_findings,
        }
