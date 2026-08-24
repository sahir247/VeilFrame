"""
Verification report card widget presenting sanitization results and audit findings.
"""
from typing import Optional
from PySide6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QTextEdit,
    QPushButton,
    QGroupBox,
    QApplication,
)
from PySide6.QtCore import Qt
from ..core.verifier import VerificationReport


class ReportViewWidget(QGroupBox):
    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("PRIVACY VERIFICATION REPORT", parent)
        self._report: Optional[VerificationReport] = None
        self._init_ui()

    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setSpacing(10)

        # Status badge banner
        banner_lay = QHBoxLayout()
        self.lbl_verdict = QLabel("No verification report available")
        self.lbl_verdict.setStyleSheet("color: #64748b; font-weight: 600; font-size: 13px;")
        banner_lay.addWidget(self.lbl_verdict)
        banner_lay.addStretch()

        self.btn_copy = QPushButton("Copy Report")
        self.btn_copy.setEnabled(False)
        self.btn_copy.clicked.connect(self._copy_report)
        banner_lay.addWidget(self.btn_copy)
        lay.addLayout(banner_lay)

        # Text Report View
        self.txt_report = QTextEdit()
        self.txt_report.setReadOnly(True)
        self.txt_report.setPlaceholderText("The privacy report will appear here after processing...")
        self.txt_report.setMinimumHeight(240)
        lay.addWidget(self.txt_report)

    def set_report(self, report: Optional[VerificationReport]):
        self._report = report
        if not report:
            self.clear()
            return

        report_text = report.format_text()
        self.txt_report.setPlainText(report_text)
        self.btn_copy.setEnabled(True)

        if report.quality_report:
            q = report.quality_report
            if report.all_passed and q.passed:
                self.lbl_verdict.setText(f"✓ AUDITED & PASSED — Cleaned metadata & Visual Quality Gate passed (SSIM: {q.ssim.mean:.4f}, PSNR: {q.psnr.mean:.1f} dB)")
                self.lbl_verdict.setStyleSheet("color: #10b981; font-weight: 700; font-size: 13px;")
            elif not q.passed:
                self.lbl_verdict.setText("⚠️ QUALITY GATE ALERT — Visual fidelity constraints violated.")
                self.lbl_verdict.setStyleSheet("color: #ef4444; font-weight: 700; font-size: 13px;")
            else:
                self.lbl_verdict.setText("⚠️ WARNING — Potential metadata or stream artifacts detected.")
                self.lbl_verdict.setStyleSheet("color: #f59e0b; font-weight: 700; font-size: 13px;")
        elif report.all_passed:
            self.lbl_verdict.setText("✓ VERIFICATION PASSED — Embedded metadata successfully sanitized.")
            self.lbl_verdict.setStyleSheet("color: #10b981; font-weight: 700; font-size: 13px;")
        else:
            self.lbl_verdict.setText("⚠️ WARNING — Potential metadata or stream artifacts detected.")
            self.lbl_verdict.setStyleSheet("color: #f59e0b; font-weight: 700; font-size: 13px;")

    def _copy_report(self):
        if self._report:
            clipboard = QApplication.clipboard()
            clipboard.setText(self._report.format_text())
            self.btn_copy.setText("Copied!")
            from PySide6.QtCore import QTimer
            QTimer.singleShot(2000, lambda: self.btn_copy.setText("Copy Report"))

    def clear(self):
        self._report = None
        self.txt_report.clear()
        self.lbl_verdict.setText("No verification report available")
        self.lbl_verdict.setStyleSheet("color: #64748b; font-weight: 600; font-size: 13px;")
        self.btn_copy.setEnabled(False)
