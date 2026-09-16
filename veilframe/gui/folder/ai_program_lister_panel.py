"""
veilframe.gui.folder.ai_program_lister_panel — Dedicated AI Program Lister & Context Generator panel.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Set

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QAbstractItemView,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSplitter,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from veilframe.folder.ai_bundle.bundle_builder import AIBundleBuilder
from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.models.classification import AIAction, FileCategory
from veilframe.folder.models.file_record import FileRecord
from veilframe.folder.models.scan_result import ScanResult
from veilframe.gui.folder.bundle_options import BundleOptionsWidget
from veilframe.gui.folder.bundle_preview import BundlePreviewDialog
from veilframe.gui.folder.project_tree_model import populate_ai_tree_item


class AIProgramListerPanel(QWidget):
    """Main panel for project intelligence analysis, file ranking, and AI bundle creation."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.scan_result: Optional[ScanResult] = None
        self._pinned_files: Set[str] = set()
        self._excluded_files: Set[str] = set()
        self._init_ui()

    def _init_ui(self) -> None:
        lay = QVBoxLayout(self)
        lay.setContentsMargins(8, 8, 8, 8)
        lay.setSpacing(8)

        # ----------------------------------------------------
        # 1. Project Intelligence Banner
        # ----------------------------------------------------
        self.banner_frame = QFrame()
        self.banner_frame.setStyleSheet(
            "background: #181818; border: 1px solid #2e2e2e; border-radius: 6px; padding: 6px;"
        )
        banner_lay = QVBoxLayout(self.banner_frame)
        banner_lay.setContentsMargins(10, 6, 10, 6)
        banner_lay.setSpacing(4)

        self.lbl_intel_title = QLabel("PROJECT INTELLIGENCE OVERVIEW")
        self.lbl_intel_title.setStyleSheet("color: #38bdf8; font-size: 11px; font-weight: 800; letter-spacing: 0.5px;")
        banner_lay.addWidget(self.lbl_intel_title)

        self.lbl_intel_details = QLabel("No active scan. Run an AI-Ready scan on a project folder to extract context.")
        self.lbl_intel_details.setStyleSheet("color: #94a3b8; font-size: 11px;")
        self.lbl_intel_details.setWordWrap(True)
        banner_lay.addWidget(self.lbl_intel_details)

        # Security warnings banner (hidden by default)
        self.sec_banner = QFrame()
        self.sec_banner.setStyleSheet(
            "background: #450a0a; border: 1px solid #dc2626; border-radius: 4px; padding: 4px 8px;"
        )
        sec_lay = QHBoxLayout(self.sec_banner)
        sec_lay.setContentsMargins(4, 2, 4, 2)
        self.lbl_sec_warning = QLabel("")
        self.lbl_sec_warning.setStyleSheet("color: #fca5a5; font-size: 11px; font-weight: 600;")
        sec_lay.addWidget(self.lbl_sec_warning)
        self.sec_banner.hide()
        banner_lay.addWidget(self.sec_banner)

        lay.addWidget(self.banner_frame)

        # ----------------------------------------------------
        # 2. Bundle Configuration Controls
        # ----------------------------------------------------
        self.options_widget = BundleOptionsWidget()
        self.options_widget.optionsChanged.connect(self._on_options_changed)
        lay.addWidget(self.options_widget)

        # ----------------------------------------------------
        # 3. File Intelligence Tree & Filter
        # ----------------------------------------------------
        filter_bar = QHBoxLayout()
        lbl_filter = QLabel("Search:")
        lbl_filter.setStyleSheet("color: #888888; font-size: 11px; font-weight: 600;")
        filter_bar.addWidget(lbl_filter)

        self.txt_filter = QLineEdit()
        self.txt_filter.setPlaceholderText("Filter ranked files by name, path, or category...")
        self.txt_filter.setStyleSheet(
            "background: #222222; color: #ffffff; border: 1px solid #3a3a3a; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        self.txt_filter.textChanged.connect(self._apply_tree_filter)
        filter_bar.addWidget(self.txt_filter, 1)

        lay.addLayout(filter_bar)

        self.tree = QTreeWidget()
        self.tree.setHeaderLabels([
            "File Path", "Category", "Policy", "Score", "Tokens", "Language", "Classification Rule"
        ])
        self.tree.setStyleSheet("""
            QTreeWidget {
                background: #141414;
                color: #e2e8f0;
                border: 1px solid #282828;
                font-size: 11px;
            }
            QTreeWidget::item {
                padding: 3px 0;
            }
            QTreeWidget::item:hover { background: #202020; }
            QTreeWidget::item:selected { background: #1e3a8a; color: #ffffff; }
            QHeaderView::section {
                background: #202020;
                color: #94a3b8;
                padding: 5px;
                border: 1px solid #282828;
                font-size: 11px;
                font-weight: 600;
            }
        """)
        self.tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tree.header().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(3, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(4, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(5, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(6, QHeaderView.ResizeToContents)
        self.tree.itemChanged.connect(self._on_tree_item_changed)
        lay.addWidget(self.tree, 1)

        # ----------------------------------------------------
        # 4. Bottom Action Bar
        # ----------------------------------------------------
        bot_bar = QFrame()
        bot_bar.setStyleSheet("background: #1e1e1e; border: 1px solid #2e2e2e; border-radius: 6px; padding: 6px;")
        bot_lay = QHBoxLayout(bot_bar)
        bot_lay.setContentsMargins(10, 4, 10, 4)
        bot_lay.setSpacing(12)

        self.lbl_token_gauge = QLabel("0 files included • 0 tokens")
        self.lbl_token_gauge.setStyleSheet("color: #22c55e; font-size: 12px; font-weight: 700;")
        bot_lay.addWidget(self.lbl_token_gauge)

        bot_lay.addStretch()

        self.btn_generate = QPushButton("GENERATE AI BUNDLE...")
        self.btn_generate.setStyleSheet(
            "background: #2563eb; color: #ffffff; border: none; "
            "padding: 8px 22px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
        )
        self.btn_generate.clicked.connect(self._on_generate_bundle)
        bot_lay.addWidget(self.btn_generate)

        lay.addWidget(bot_bar)

    def set_scan_result(self, result: ScanResult) -> None:
        """Update view with new scan result and populate tree."""
        self.scan_result = result
        self._pinned_files.clear()
        self._excluded_files.clear()

        # 1. Update Project Intelligence Banner
        langs = ", ".join(result.languages) if result.languages else "None"
        ecos = ", ".join(result.ecosystems) if result.ecosystems else "Generic"
        entry_points = [f.relative_path for f in result.files if f.is_entry_point]
        ep_str = f" | Entry points: {', '.join(entry_points[:3])}" if entry_points else ""

        self.lbl_intel_details.setText(
            f"Languages: {langs} | Ecosystems: {ecos}{ep_str} | Files: {len(result.files):,}"
        )

        # Security warnings
        if result.security_alerts:
            self.lbl_sec_warning.setText(
                f"⚠️ {len(result.security_alerts)} potential secret(s) detected. Automatically masked/excluded."
            )
            self.sec_banner.show()
        else:
            self.sec_banner.hide()

        # 2. Populate Tree
        self._rebuild_tree()

    def _rebuild_tree(self) -> None:
        self.tree.blockSignals(True)
        self.tree.clear()

        if not self.scan_result:
            self.tree.blockSignals(False)
            return

        cfg = self.options_widget.get_config()

        # Rank files by priority score descending
        sorted_files = sorted(
            self.scan_result.files,
            key=lambda x: (
                -(x.priority_score if x.priority_score is not None else 0),
                x.relative_path
            )
        )

        for f in sorted_files:
            item = QTreeWidgetItem()
            populate_ai_tree_item(item, f)

            # Checkbox for manual inclusion/exclusion
            norm = f.relative_path.replace("\\", "/").strip("/")
            is_included = (
                (norm in self._pinned_files) or
                (f.effective_action != AIAction.EXCLUDE and norm not in self._excluded_files)
            )

            item.setCheckState(0, Qt.Checked if is_included else Qt.Unchecked)
            item.setData(0, Qt.UserRole, f)
            self.tree.addTopLevelItem(item)

        self.tree.blockSignals(False)
        self._update_token_gauge()

    def _on_tree_item_changed(self, item: QTreeWidgetItem, column: int) -> None:
        if column == 0:
            f: Optional[FileRecord] = item.data(0, Qt.UserRole)
            if f:
                norm = f.relative_path.replace("\\", "/").strip("/")
                if item.checkState(0) == Qt.Checked:
                    self._pinned_files.add(norm)
                    self._excluded_files.discard(norm)
                else:
                    self._excluded_files.add(norm)
                    self._pinned_files.discard(norm)
                self._update_token_gauge()

    def _on_options_changed(self) -> None:
        self._update_token_gauge()

    def _update_token_gauge(self) -> None:
        if not self.scan_result:
            self.lbl_token_gauge.setText("0 files included • 0 tokens")
            return

        cfg = self.options_widget.get_config()
        cfg.pinned_files = set(self._pinned_files)
        cfg.excluded_files = set(self._excluded_files)

        from veilframe.folder.ai_bundle.file_selector import FileSelector
        selector = FileSelector(cfg)
        included, excluded, tokens = selector.select_files(self.scan_result.files)

        budget_str = f" / {cfg.target_tokens:,}" if cfg.target_tokens else " (Unlimited)"
        pct_str = ""
        if cfg.target_tokens:
            pct = min(100.0, (tokens / cfg.target_tokens) * 100.0)
            pct_str = f" ({pct:.1f}%)"

        self.lbl_token_gauge.setText(
            f"⚡ {len(included):,} files selected • ~{tokens:,}{budget_str} tokens{pct_str}"
        )

    def _apply_tree_filter(self, text: str) -> None:
        query = text.strip().lower()
        root = self.tree.invisibleRootItem()
        for i in range(root.childCount()):
            item = root.child(i)
            matches = (
                not query or
                query in item.text(0).lower() or
                query in item.text(1).lower() or
                query in item.text(5).lower()
            )
            item.setHidden(not matches)

    def _on_generate_bundle(self) -> None:
        if not self.scan_result:
            QMessageBox.warning(self, "No Scan Data", "Please scan a project folder before generating an AI bundle.")
            return

        cfg = self.options_widget.get_config()
        cfg.pinned_files = set(self._pinned_files)
        cfg.excluded_files = set(self._excluded_files)

        builder = AIBundleBuilder(cfg)
        result = builder.build(self.scan_result)

        dlg = BundlePreviewDialog(result, parent=self)
        dlg.exec_()
