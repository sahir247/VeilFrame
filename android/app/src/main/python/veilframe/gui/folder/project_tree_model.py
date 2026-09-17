"""
veilframe.gui.folder.project_tree_model — Visual badges and styling for project intelligence tree.
"""

from __future__ import annotations

from typing import Dict, Tuple

from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import QTreeWidgetItem

from veilframe.folder.models.classification import AIAction, FileCategory
from veilframe.folder.models.file_record import FileRecord


# Visual palette for category badges
CATEGORY_COLORS: Dict[FileCategory, Tuple[str, str]] = {
    # FileCategory: (background_hex, text_hex)
    FileCategory.SOURCE: ("#14532d", "#86efac"),         # Dark green / bright green
    FileCategory.CONFIG: ("#0c4a6e", "#7dd3fc"),         # Dark sky / light blue
    FileCategory.MANIFEST: ("#0369a1", "#e0f2fe"),       # Ocean blue
    FileCategory.DOCUMENTATION: ("#713f12", "#fde047"),  # Dark yellow / light gold
    FileCategory.TEST: ("#581c87", "#d8b4fe"),           # Dark purple / light violet
    FileCategory.SCRIPT: ("#1e3a8a", "#93c5fd"),         # Dark blue
    FileCategory.CI_CD: ("#065f46", "#6ee7b7"),          # Teal
    FileCategory.IDE_CONFIG: ("#1f2937", "#9ca3af"),     # Dark slate
    FileCategory.VCS_CONFIG: ("#1f2937", "#9ca3af"),     # Dark slate
    FileCategory.DEPENDENCY: ("#18181b", "#71717a"),     # Muted gray
    FileCategory.VENDOR: ("#18181b", "#71717a"),         # Muted gray
    FileCategory.CACHE: ("#18181b", "#52525b"),          # Dark gray
    FileCategory.BUILD_OUTPUT: ("#18181b", "#52525b"),   # Dark gray
    FileCategory.GENERATED: ("#18181b", "#52525b"),      # Dark gray
    FileCategory.COMPILED: ("#18181b", "#52525b"),       # Dark gray
    FileCategory.DATABASE: ("#7c2d12", "#fdba74"),       # Rust orange
    FileCategory.DATASET: ("#7c2d12", "#fed7aa"),        # Warm amber
    FileCategory.MODEL: ("#831843", "#f472b6"),          # Magenta pink
    FileCategory.SECRET: ("#7f1d1d", "#fca5a5"),         # Deep red / bright red
    FileCategory.CREDENTIAL: ("#7f1d1d", "#fca5a5"),     # Deep red / bright red
    FileCategory.PRIVATE_KEY: ("#991b1b", "#fecaca"),    # Crimson
    FileCategory.UNKNOWN: ("#27272a", "#a1a1aa"),        # Zinc neutral
}


def get_badge_colors(category: FileCategory) -> Tuple[QColor, QColor]:
    """Return (background_color, text_color) for a given category."""
    bg_hex, fg_hex = CATEGORY_COLORS.get(category, ("#27272a", "#a1a1aa"))
    return QColor(bg_hex), QColor(fg_hex)


def format_token_count(tokens: int) -> str:
    """Format token count cleanly (e.g., 1,420 or 12.5k)."""
    if tokens < 1_000:
        return f"{tokens} tok"
    elif tokens < 100_000:
        return f"{tokens / 1_000:.1f}k tok"
    else:
        return f"{tokens // 1_000:,}k tok"


def populate_ai_tree_item(item: QTreeWidgetItem, f: FileRecord) -> None:
    """Populate a QTreeWidgetItem with rich AI intelligence attributes."""
    cat = f.effective_category
    bg, fg = get_badge_colors(cat)

    # Col 0: Relative Path / Name
    name_display = f.relative_path
    if f.is_entry_point:
        name_display = f"★ {name_display} (Entry Point)"
    item.setText(0, name_display)

    # Col 1: Category Badge
    item.setText(1, cat.value)
    item.setBackground(1, bg)
    item.setForeground(1, fg)

    # Col 2: AI Action
    action_text = f.effective_action.value.upper()
    item.setText(2, action_text)
    if f.effective_action == AIAction.EXCLUDE:
        item.setForeground(2, QColor("#ef4444"))
    elif f.effective_action == AIAction.INCLUDE:
        item.setForeground(2, QColor("#22c55e"))
    elif f.effective_action == AIAction.WARN:
        item.setForeground(2, QColor("#f59e0b"))

    # Col 3: Priority Score
    item.setText(3, str(f.priority_score if f.priority_score is not None else "-"))

    # Col 4: Tokens
    item.setText(4, format_token_count(f.token_count or max(1, f.size // 4)))

    # Col 5: Language
    item.setText(5, f.language or "-")

    # Col 6: Reason
    item.setText(6, f.classification.reason or "")
