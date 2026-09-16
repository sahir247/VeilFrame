"""
veilframe.folder — High-performance extensible folder intelligence and AI bundle subsystem.
"""

from veilframe.folder.classification import (
    Classifier,
    calculate_file_priority,
    get_action_color,
    get_category_color,
)
from veilframe.folder.database.folder_db import FolderDatabase
from veilframe.folder.duplicates.duplicate_finder import DuplicateFinder
from veilframe.folder.duplicates.hasher import (
    ParallelHashEngine,
    compute_file_hash,
    compute_quick_fingerprint,
)
from veilframe.folder.exporter import FolderExporter
from veilframe.folder.metadata import (
    MetadataEngine,
    detect_file_type,
    detect_language,
)
from veilframe.folder.models import (
    DEFAULT_CATEGORY_ACTIONS,
    AIAction,
    ClassificationResult,
    DuplicateGroup,
    ExtensionStat,
    FileCategory,
    FileRecord,
    FolderRecord,
    RulePriority,
    ScanError,
    ScanResult,
    ScanStats,
    format_bytes,
    get_default_action,
)
from veilframe.folder.project_context import (
    GitIgnoreParser,
    ParsedManifest,
    ProjectDetector,
    ProjectGraph,
    detect_ecosystems,
    extract_imports,
    parse_manifest,
)
from veilframe.folder.rules import (
    Rule,
    RuleRegistry,
    resolve_highest_precedence,
)
from veilframe.folder.scanner import (
    CancellationToken,
    DirectoryWalker,
    FolderScanner,
    HashAlgorithm,
    ScanConfig,
    ScanProfile,
)
from veilframe.folder.security import (
    SECRET_PATTERNS,
    SecretDetector,
    SecurityAlert,
    calculate_entropy,
    has_high_entropy,
    is_sensitive_filepath,
    mask_secret,
)
from veilframe.folder.statistics import StatisticsEngine

__all__ = [
    # Core Scanner & Config
    "ScanConfig",
    "ScanProfile",
    "HashAlgorithm",
    "FolderScanner",
    "DirectoryWalker",
    "CancellationToken",
    # Models & Taxonomy
    "FileRecord",
    "FolderRecord",
    "ScanError",
    "DuplicateGroup",
    "ExtensionStat",
    "ScanStats",
    "ScanResult",
    "format_bytes",
    "FileCategory",
    "AIAction",
    "RulePriority",
    "ClassificationResult",
    "DEFAULT_CATEGORY_ACTIONS",
    "get_default_action",
    # Database & Duplicates
    "FolderDatabase",
    "DuplicateFinder",
    "StatisticsEngine",
    "FolderExporter",
    "compute_file_hash",
    "compute_quick_fingerprint",
    "ParallelHashEngine",
    # Rules & Security
    "Rule",
    "RuleRegistry",
    "resolve_highest_precedence",
    "Classifier",
    "calculate_file_priority",
    "get_category_color",
    "get_action_color",
    "SecretDetector",
    "SecurityAlert",
    "mask_secret",
    "calculate_entropy",
    "has_high_entropy",
    "SECRET_PATTERNS",
    "is_sensitive_filepath",
    # Project Context
    "detect_ecosystems",
    "ProjectDetector",
    "ParsedManifest",
    "parse_manifest",
    "GitIgnoreParser",
    "extract_imports",
    "ProjectGraph",
    "MetadataEngine",
    "detect_file_type",
    "detect_language",
]
