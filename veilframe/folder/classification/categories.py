"""
veilframe.folder.classification.categories — Category metadata, badge colors, and presentation helpers.
"""

from __future__ import annotations

from typing import Dict

from veilframe.folder.models.classification import AIAction, FileCategory

# Visual color mapping for GUI badges and CLI terminal styling
CATEGORY_COLOR_MAP: Dict[FileCategory, str] = {
    FileCategory.SOURCE: "#10b981",       # Emerald Green
    FileCategory.CONFIG: "#0ea5e9",       # Sky Blue
    FileCategory.MANIFEST: "#38bdf8",     # Light Cyan
    FileCategory.DOCUMENTATION: "#a855f7",# Purple
    FileCategory.TEST: "#8b5cf6",         # Violet
    FileCategory.SCRIPT: "#f59e0b",       # Amber
    FileCategory.CI_CD: "#06b6d4",        # Cyan
    FileCategory.IDE_CONFIG: "#64748b",   # Slate Gray
    FileCategory.VCS_CONFIG: "#64748b",   # Slate Gray
    FileCategory.DEPENDENCY: "#ef4444",   # Red
    FileCategory.VENDOR: "#f97316",       # Orange
    FileCategory.CACHE: "#71717a",        # Muted Gray
    FileCategory.BUILD_OUTPUT: "#71717a", # Muted Gray
    FileCategory.GENERATED: "#71717a",    # Muted Gray
    FileCategory.COMPILED: "#71717a",     # Muted Gray
    FileCategory.RUNTIME_DATA: "#71717a", # Muted Gray
    FileCategory.MEDIA: "#ec4899",        # Pink
    FileCategory.DATABASE: "#eab308",     # Yellow
    FileCategory.DATASET: "#eab308",      # Yellow
    FileCategory.MODEL: "#d946ef",        # Fuchsia
    FileCategory.BINARY: "#6b7280",       # Gray
    FileCategory.SECRET: "#dc2626",       # Warning Bright Red
    FileCategory.CREDENTIAL: "#dc2626",   # Warning Bright Red
    FileCategory.PRIVATE_KEY: "#dc2626",  # Warning Bright Red
    FileCategory.UNKNOWN: "#9ca3af",      # Neutral Gray
}

ACTION_COLOR_MAP: Dict[AIAction, str] = {
    AIAction.INCLUDE: "#22c55e",          # Green
    AIAction.EXCLUDE: "#ef4444",          # Red
    AIAction.WARN: "#f97316",             # Warning Amber/Orange
    AIAction.DESCRIBE: "#38bdf8",         # Blue
    AIAction.ANALYZE: "#a855f7",          # Purple
    AIAction.SELECTIVE_INCLUDE: "#14b8a6",# Teal
    AIAction.REDACT: "#e11d48",           # Rose
}


def get_category_color(category: FileCategory) -> str:
    """Return hex color representation for a file category badge."""
    return CATEGORY_COLOR_MAP.get(category, "#9ca3af")


def get_action_color(action: AIAction) -> str:
    """Return hex color representation for an AI action badge."""
    return ACTION_COLOR_MAP.get(action, "#9ca3af")
