"""
veilframe.gui.folder.bundle_options — Configuration widget for AI bundle generation options.
"""

from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from veilframe.folder.ai_bundle.bundle_config import BundleConfig, BundleFormat


class BundleOptionsWidget(QWidget):
    """UI Controls for adjusting token budgets, file category filters, and bundle formats."""

    optionsChanged = Signal()

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self._init_ui()

    def _init_ui(self) -> None:
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(8)

        # Primary controls frame
        frame = QFrame()
        frame.setStyleSheet("background: #1e1e1e; border: 1px solid #2e2e2e; border-radius: 6px; padding: 6px;")
        flay = QVBoxLayout(frame)
        flay.setContentsMargins(10, 8, 10, 8)
        flay.setSpacing(10)

        # Row 1: Token Budget & Format Selection
        row1 = QHBoxLayout()
        row1.setSpacing(12)

        lbl_budget = QLabel("Token Target Budget:")
        lbl_budget.setStyleSheet("color: #38bdf8; font-size: 11px; font-weight: 700;")
        row1.addWidget(lbl_budget)

        self.combo_budget = QComboBox()
        self.combo_budget.addItems([
            "32k Tokens (Compact / Fast)",
            "64k Tokens (Standard)",
            "128k Tokens (Claude / GPT-4o)",
            "200k Tokens (Deep Context)",
            "Unlimited (Full Project)",
            "Custom Token Limit...",
        ])
        self.combo_budget.setCurrentIndex(2)  # Default 128k
        self.combo_budget.setStyleSheet(
            "background: #262626; color: #ffffff; border: 1px solid #444444; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        self.combo_budget.currentIndexChanged.connect(self._on_budget_changed)
        row1.addWidget(self.combo_budget, 1)

        self.spin_custom_tokens = QSpinBox()
        self.spin_custom_tokens.setRange(1_000, 2_000_000)
        self.spin_custom_tokens.setSingleStep(5_000)
        self.spin_custom_tokens.setValue(128_000)
        self.spin_custom_tokens.setStyleSheet(
            "background: #262626; color: #ffffff; border: 1px solid #444444; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        self.spin_custom_tokens.hide()
        self.spin_custom_tokens.valueChanged.connect(lambda: self.optionsChanged.emit())
        row1.addWidget(self.spin_custom_tokens)

        row1.addSpacing(16)

        lbl_format = QLabel("Output Bundle Format:")
        lbl_format.setStyleSheet("color: #38bdf8; font-size: 11px; font-weight: 700;")
        row1.addWidget(lbl_format)

        self.combo_format = QComboBox()
        self.combo_format.addItems([
            "Native .aibundle (Structured)",
            "Markdown (.md, TOC + Fences)",
            "Plain Text (.txt)",
            "Structured JSON (.json)",
            "Curated Clean ZIP Archive (.zip)",
            "Interactive HTML (.html)",
        ])
        self.combo_format.setStyleSheet(
            "background: #262626; color: #ffffff; border: 1px solid #444444; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        self.combo_format.currentIndexChanged.connect(lambda: self.optionsChanged.emit())
        row1.addWidget(self.combo_format, 1)

        flay.addLayout(row1)

        # Row 2: Filtering Checkboxes Grid
        grid = QGridLayout()
        grid.setHorizontalSpacing(16)
        grid.setVerticalSpacing(6)

        self.chk_include_tests = QCheckBox("Include Test Files")
        self.chk_include_tests.setChecked(True)
        self.chk_include_tests.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_include_tests, 0, 0)

        self.chk_include_docs = QCheckBox("Include Documentation")
        self.chk_include_docs.setChecked(True)
        self.chk_include_docs.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_include_docs, 0, 1)

        self.chk_include_configs = QCheckBox("Include Project Configs")
        self.chk_include_configs.setChecked(True)
        self.chk_include_configs.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_include_configs, 0, 2)

        self.chk_include_scripts = QCheckBox("Include Utility Scripts")
        self.chk_include_scripts.setChecked(True)
        self.chk_include_scripts.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_include_scripts, 0, 3)

        self.chk_redact_secrets = QCheckBox("Redact Secrets & API Keys")
        self.chk_redact_secrets.setChecked(True)
        self.chk_redact_secrets.setStyleSheet("color: #f87171; font-weight: 600;")
        self.chk_redact_secrets.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_redact_secrets, 1, 0)

        self.chk_compress_data = QCheckBox("Summarize DBs / Datasets")
        self.chk_compress_data.setChecked(True)
        self.chk_compress_data.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_compress_data, 1, 1)

        self.chk_tree = QCheckBox("Include Directory Tree")
        self.chk_tree.setChecked(True)
        self.chk_tree.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_tree, 1, 2)

        self.chk_relationships = QCheckBox("Include Module Graph")
        self.chk_relationships.setChecked(True)
        self.chk_relationships.toggled.connect(lambda: self.optionsChanged.emit())
        grid.addWidget(self.chk_relationships, 1, 3)

        flay.addLayout(grid)
        lay.addWidget(frame)

    def _on_budget_changed(self, idx: int) -> None:
        if idx == 5:
            self.spin_custom_tokens.show()
        else:
            self.spin_custom_tokens.hide()
        self.optionsChanged.emit()

    def get_config(self) -> BundleConfig:
        """Construct a configured BundleConfig object from current UI selections."""
        # 1. Budget
        b_idx = self.combo_budget.currentIndex()
        target_tokens: Optional[int] = 128_000
        if b_idx == 0:
            target_tokens = 32_000
        elif b_idx == 1:
            target_tokens = 64_000
        elif b_idx == 2:
            target_tokens = 128_000
        elif b_idx == 3:
            target_tokens = 200_000
        elif b_idx == 4:
            target_tokens = None
        elif b_idx == 5:
            target_tokens = self.spin_custom_tokens.value()

        # 2. Format
        f_idx = self.combo_format.currentIndex()
        format_map = {
            0: BundleFormat.AIBUNDLE,
            1: BundleFormat.MARKDOWN,
            2: BundleFormat.TEXT,
            3: BundleFormat.JSON,
            4: BundleFormat.ZIP,
            5: BundleFormat.HTML,
        }
        fmt = format_map.get(f_idx, BundleFormat.AIBUNDLE)

        return BundleConfig(
            target_tokens=target_tokens,
            format=fmt,
            include_tests=self.chk_include_tests.isChecked(),
            include_documentation=self.chk_include_docs.isChecked(),
            include_configs=self.chk_include_configs.isChecked(),
            include_scripts=self.chk_include_scripts.isChecked(),
            redact_secrets=self.chk_redact_secrets.isChecked(),
            compress_context=self.chk_compress_data.isChecked(),
            include_tree=self.chk_tree.isChecked(),
            include_relationships=self.chk_relationships.isChecked(),
        )
