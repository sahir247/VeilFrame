"""
veilframe.gui.folder.bundle_preview — Interactive preview dialog for generated AI program bundles.
"""

from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QFont, QGuiApplication
from PySide6.QtWidgets import (
    QDialog,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from veilframe.folder.ai_bundle.bundle_builder import AIBundleResult
from veilframe.folder.ai_bundle.bundle_config import BundleFormat


class BundlePreviewDialog(QDialog):
    """Dialog displaying generated AI bundle with token metrics, copy-to-clipboard, and export."""

    def __init__(self, result: AIBundleResult, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.result = result
        self.setWindowTitle("VeilFrame AI Bundle Preview & Export")
        self.resize(950, 700)
        self.setStyleSheet("background-color: #121212; color: #f1f5f9;")
        self._init_ui()

    def _init_ui(self) -> None:
        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(16, 16, 16, 16)
        main_lay.setSpacing(12)

        # ----------------------------------------------------
        # 1. Top Metrics Banner
        # ----------------------------------------------------
        top_bar = QFrame()
        top_bar.setStyleSheet("background: #1e1e1e; border: 1px solid #333333; border-radius: 6px; padding: 8px;")
        top_lay = QHBoxLayout(top_bar)
        top_lay.setContentsMargins(10, 4, 10, 4)
        top_lay.setSpacing(16)

        lbl_title = QLabel("AI CONTEXT BUNDLE")
        lbl_title.setStyleSheet("color: #38bdf8; font-weight: 800; font-size: 13px; letter-spacing: 0.5px;")
        top_lay.addWidget(lbl_title)

        fmt_badge = QLabel(f"[{self.result.format.value.upper()}]")
        fmt_badge.setStyleSheet("color: #a855f7; font-weight: 700; font-size: 11px;")
        top_lay.addWidget(fmt_badge)

        top_lay.addStretch()

        # Token metrics
        lbl_tokens = QLabel(f"⚡ Tokens: {self.result.total_tokens:,}")
        lbl_tokens.setStyleSheet("color: #22c55e; font-weight: 700; font-size: 12px;")
        top_lay.addWidget(lbl_tokens)

        lbl_files = QLabel(f"📁 Files: {self.result.included_count} included ({self.result.excluded_count} excluded)")
        lbl_files.setStyleSheet("color: #94a3b8; font-size: 11px;")
        top_lay.addWidget(lbl_files)

        main_lay.addWidget(top_bar)

        # ----------------------------------------------------
        # 2. Text Editor / Content Area
        # ----------------------------------------------------
        self.txt_content = QTextEdit()
        self.txt_content.setFont(QFont("Consolas", 10))
        self.txt_content.setReadOnly(True)
        self.txt_content.setStyleSheet("""
            QTextEdit {
                background-color: #181818;
                color: #e2e8f0;
                border: 1px solid #2e2e2e;
                border-radius: 4px;
                padding: 8px;
            }
        """)

        if self.result.is_binary:
            zip_size = len(self.result.content) if isinstance(self.result.content, bytes) else 0
            self.txt_content.setPlainText(
                f"CURATED CLEAN ZIP ARCHIVE GENERATED\n\n"
                f"Archive Size: {zip_size:,} bytes\n"
                f"Included Files: {self.result.included_count}\n"
                f"Excluded Files: {self.result.excluded_count}\n"
                f"AI Context Summary: Embedded as AI_PROJECT_BUNDLE.md in root\n\n"
                f"Click 'Save Bundle to File...' below to export this zip archive to your filesystem."
            )
        else:
            text_str = self.result.content if isinstance(self.result.content, str) else ""
            self.txt_content.setPlainText(text_str)

        main_lay.addWidget(self.txt_content, 1)

        # ----------------------------------------------------
        # 3. Action Buttons & Feedback
        # ----------------------------------------------------
        btn_lay = QHBoxLayout()
        btn_lay.setSpacing(10)

        self.lbl_feedback = QLabel("")
        self.lbl_feedback.setStyleSheet("color: #22c55e; font-weight: 600; font-size: 11px;")
        btn_lay.addWidget(self.lbl_feedback)
        btn_lay.addStretch()

        if not self.result.is_binary:
            self.btn_copy = QPushButton("Copy to Clipboard")
            self.btn_copy.setStyleSheet(
                "background: #2563eb; color: #ffffff; border: none; "
                "padding: 8px 18px; border-radius: 4px; font-weight: 600; font-size: 11px;"
            )
            self.btn_copy.clicked.connect(self._on_copy)
            btn_lay.addWidget(self.btn_copy)

        self.btn_save = QPushButton("Save Bundle to File...")
        self.btn_save.setStyleSheet(
            "background: #0284c7; color: #ffffff; border: none; "
            "padding: 8px 18px; border-radius: 4px; font-weight: 600; font-size: 11px;"
        )
        self.btn_save.clicked.connect(self._on_save)
        btn_lay.addWidget(self.btn_save)

        self.btn_close = QPushButton("Close")
        self.btn_close.setStyleSheet(
            "background: #2b2b2b; color: #e0e0e0; border: 1px solid #484848; "
            "padding: 8px 16px; border-radius: 4px; font-size: 11px;"
        )
        self.btn_close.clicked.connect(self.accept)
        btn_lay.addWidget(self.btn_close)

        main_lay.addLayout(btn_lay)

    def _on_copy(self) -> None:
        if isinstance(self.result.content, str):
            clipboard = QGuiApplication.clipboard()
            clipboard.setText(self.result.content)
            self.lbl_feedback.setText("✓ Copied full bundle to clipboard!")
            QTimer.singleShot(3000, lambda: self.lbl_feedback.setText(""))

    def _on_save(self) -> None:
        ext_map = {
            BundleFormat.AIBUNDLE: "AI Bundle (*.aibundle);;Text (*.txt)",
            BundleFormat.MARKDOWN: "Markdown (*.md);;Text (*.txt)",
            BundleFormat.TEXT: "Text (*.txt);;All Files (*)",
            BundleFormat.JSON: "JSON (*.json);;All Files (*)",
            BundleFormat.ZIP: "ZIP Archive (*.zip);;All Files (*)",
            BundleFormat.HTML: "HTML (*.html);;All Files (*)",
        }
        filter_str = ext_map.get(self.result.format, "All Files (*)")
        def_name = f"project_bundle.{self.result.format.value}"

        dest, _ = QFileDialog.getSaveFileName(self, "Save AI Project Bundle", def_name, filter_str)
        if not dest:
            return

        try:
            bytes_written = self.result.write(dest)
            QMessageBox.information(
                self,
                "Bundle Saved",
                f"Successfully saved bundle to:\n{dest}\n\nSize: {bytes_written:,} bytes\nTokens: ~{self.result.total_tokens:,}",
            )
        except Exception as e:
            QMessageBox.critical(self, "Save Failed", f"Failed to write bundle file:\n{e}")
