"""
veilframe.gui.folder — Folder analyzer and AI program lister GUI widgets.
"""

from veilframe.gui.folder.ai_program_lister_panel import AIProgramListerPanel
from veilframe.gui.folder.bundle_options import BundleOptionsWidget
from veilframe.gui.folder.bundle_preview import BundlePreviewDialog
from veilframe.gui.folder.project_tree_model import CATEGORY_COLORS, get_badge_colors, populate_ai_tree_item

__all__ = [
    "AIProgramListerPanel",
    "BundleOptionsWidget",
    "BundlePreviewDialog",
    "CATEGORY_COLORS",
    "get_badge_colors",
    "populate_ai_tree_item",
]
