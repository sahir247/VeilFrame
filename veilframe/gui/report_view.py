"""
VeilFrame UI 2.0 — Report View.

3-tab layout:
  Summary      — Metadata sanitization result + VerificationReport text
  Quality Gate — QualityPanel widget (metric sparkbars, verdict badges)
  Manifest     — Structured summary sub-panel + raw JSON viewer
"""
import json
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel,
    QTextEdit, QPushButton, QGroupBox, QTabWidget,
    QFrame, QScrollArea, QGridLayout, QApplication,
    QSizePolicy,
)
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QFont

from ..core.verifier import VerificationReport
from ..models.video_info import VisualQualityReport
from .quality_panel import QualityPanel, _badge, _meta_label, _value_label, _hline, _section_label


class _ManifestTab(QWidget):
    """Structured manifest summary + raw JSON viewer (two sub-panels)."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setSpacing(10)
        lay.setContentsMargins(0, 0, 0, 0)

        # ── Structured summary ──────────────────────────────────────── #
        self._summary_frame = QFrame()
        self._summary_frame.setStyleSheet(
            "background: #222222; border: 1px solid #333333; border-radius: 4px;"
        )
        self._summary_lay = QGridLayout(self._summary_frame)
        self._summary_lay.setContentsMargins(12, 10, 12, 10)
        self._summary_lay.setHorizontalSpacing(16)
        self._summary_lay.setVerticalSpacing(5)
        lay.addWidget(self._summary_frame)

        # ── Raw JSON ────────────────────────────────────────────────── #
        raw_hdr = QHBoxLayout()
        raw_hdr.addWidget(_section_label("Raw Manifest JSON"))
        raw_hdr.addStretch()
        self.btn_copy_json = QPushButton("Copy JSON")
        self.btn_copy_json.setEnabled(False)
        self.btn_copy_json.clicked.connect(self._copy_json)
        raw_hdr.addWidget(self.btn_copy_json)
        lay.addLayout(raw_hdr)

        self.txt_json = QTextEdit()
        self.txt_json.setReadOnly(True)
        self.txt_json.setPlaceholderText("Manifest JSON will appear here after processing…")
        self.txt_json.setMinimumHeight(220)
        lay.addWidget(self.txt_json)

        self._raw_text: str = ""

    def set_manifest(self, quality_report: Optional[VisualQualityReport]):
        """Populate from VisualQualityReport.raw_details which holds the provider_infos."""
        # Clear summary grid
        while self._summary_lay.count():
            item = self._summary_lay.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        self.btn_copy_json.setEnabled(False)
        self.txt_json.clear()
        self._raw_text = ""

        if not quality_report:
            return

        r = quality_report

        # Structured summary
        rows = [
            ("Manifest version", "1.1.0"),
            ("Algorithm", "quality-gate-v4.0"),
            ("Policy", "5pct-v1.0"),
            ("Signing mode", r.signing_mode),
            ("Ed25519 key", r.signing_key_id or "ephemeral"),
            ("Input SHA-256", r.input_sha256[:20] + "…" if r.input_sha256 else "—"),
            ("Output SHA-256", r.output_sha256[:20] + "…" if r.output_sha256 else "—"),
            ("Signature", r.manifest_signature[:20] + "…" if r.manifest_signature else "—"),
            ("Public key fingerprint", r.public_key_fingerprint[:28] + "…" if r.public_key_fingerprint else "—"),
        ]
        # Provider infos
        infos = r.raw_details.get("provider_infos", [])
        for info in infos:
            caps = info.get("capabilities") or []
            primary_cap = caps[0].upper() if caps else "provider"
            ver = info.get("runtime_version") or "—"
            rows.append((f"Provider  ({primary_cap})", f"ffmpeg {ver}"))
            libvmaf = info.get("libvmaf_version")
            if libvmaf:
                rows.append(("libvmaf version", libvmaf))

        for i, (key, val) in enumerate(rows):
            key_lbl = QLabel(key + ":")
            key_lbl.setStyleSheet("color: #606060; font-size: 11px;")
            val_lbl = QLabel(val)
            val_lbl.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 500;")
            val_lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
            self._summary_lay.addWidget(key_lbl, i, 0)
            self._summary_lay.addWidget(val_lbl, i, 1)

        # Build representative JSON from the report's known fields
        manifest_preview = {
            "manifest_version": "1.1.0",
            "quality_engine": {
                "engine_version": "1.1.0",
                "algorithm_version": "quality-gate-v4.0",
                "policy_version": "5pct-v1.0",
            },
            "signing": {
                "mode": r.signing_mode,
                "algorithm": "Ed25519",
                "key_id": r.signing_key_id,
                "public_key_fingerprint_raw": r.public_key_fingerprint,
            },
            "input_sha256": r.input_sha256,
            "output_sha256": r.output_sha256,
            "rendered_fidelity": {
                "ssim_mean": r.ssim.mean,
                "ssim_p5": r.ssim.p5,
                "ssim_worst": r.ssim.min_val,
                "psnr_mean_db": r.psnr.mean,
                "psnr_worst_db": r.psnr.min_val,
            },
            "quality_providers": {
                entry["metric"]: {
                    "mean": entry.get("mean"),
                    "minimum": entry.get("minimum"),
                    "evidence_sha256": entry.get("evidence_sha256"),
                    "note": entry.get("note"),
                }
                for entry in (r.provider_results or [])
            },
            "verdict": {
                "tier1_policy_passed": r.three_tier_verdict.tier1_policy_passed,
                "tier2_fidelity_passed": r.three_tier_verdict.tier2_fidelity_passed,
                "tier3_temporal_passed": r.three_tier_verdict.tier3_temporal_passed,
                "overall_verdict": r.three_tier_verdict.overall_verdict,
                "all_passed": r.three_tier_verdict.all_passed,
            },
            "signature": r.manifest_signature,
        }

        self._raw_text = json.dumps(manifest_preview, indent=2, default=str)
        self.txt_json.setPlainText(self._raw_text)
        self.btn_copy_json.setEnabled(True)

    def _copy_json(self):
        QApplication.clipboard().setText(self._raw_text)
        self.btn_copy_json.setText("Copied!")
        QTimer.singleShot(2000, lambda: self.btn_copy_json.setText("Copy JSON"))

    def clear(self):
        self.set_manifest(None)


class ReportViewWidget(QGroupBox):
    """
    3-tab report panel shown after processing completes.

    Tabs: Summary | Quality Gate | Manifest
    """

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("PRIVACY & QUALITY REPORT", parent)
        self._report: Optional[VerificationReport] = None
        self._quality_report: Optional[VisualQualityReport] = None
        self._vmaf_evidence_path: Optional[Path] = None
        self._init_ui()

    def _init_ui(self):
        outer = QVBoxLayout(self)
        outer.setSpacing(8)
        outer.setContentsMargins(8, 8, 8, 8)

        # Verdict banner (always visible, above tabs)
        banner = QHBoxLayout()
        self.lbl_verdict = QLabel("No report")
        self.lbl_verdict.setStyleSheet("color: #555555; font-weight: 600; font-size: 12px;")
        banner.addWidget(self.lbl_verdict)
        banner.addStretch()
        self.btn_copy_report = QPushButton("Copy Text Report")
        self.btn_copy_report.setEnabled(False)
        self.btn_copy_report.clicked.connect(self._copy_text_report)
        banner.addWidget(self.btn_copy_report)
        outer.addLayout(banner)

        # Tabs
        self.tabs = QTabWidget()
        outer.addWidget(self.tabs)

        # Tab 1 — Summary
        self._tab_summary = QWidget()
        sum_lay = QVBoxLayout(self._tab_summary)
        sum_lay.setContentsMargins(0, 8, 0, 0)
        self.txt_report = QTextEdit()
        self.txt_report.setReadOnly(True)
        self.txt_report.setPlaceholderText("The privacy report will appear here after processing…")
        self.txt_report.setMinimumHeight(220)
        sum_lay.addWidget(self.txt_report)
        self.tabs.addTab(self._tab_summary, "Summary")

        # Tab 2 — Quality Gate
        self._quality_panel = QualityPanel()
        self.tabs.addTab(self._quality_panel, "Quality Gate")

        # Tab 3 — Manifest
        self._manifest_tab = _ManifestTab()
        self.tabs.addTab(self._manifest_tab, "Manifest")

    def set_report(
        self,
        report: VerificationReport,
        vmaf_evidence_path: Optional[Path] = None,
    ):
        self._report = report
        self._vmaf_evidence_path = vmaf_evidence_path
        self._quality_report = report.quality_report if report else None

        # Summary tab — raw text
        self.txt_report.setPlainText(report.format_text() if report else "")
        self.btn_copy_report.setEnabled(bool(report))

        # Verdict banner
        q = report.quality_report if report else None
        if q:
            if report.all_passed and q.passed:
                verdict_v = "pass"
                ssim_str = f"SSIM: {q.ssim.mean:.4f}"
                psnr_str = f"PSNR: {q.psnr.mean:.1f} dB"

                # Check if VMAF available
                vmaf_entries = [p for p in (q.provider_results or []) if p.get("metric") == "vmaf"]
                if vmaf_entries:
                    vmaf_mean = vmaf_entries[0].get("mean", 0.0)
                    extra = f"  VMAF: {vmaf_mean:.1f}"
                else:
                    extra = ""

                self.lbl_verdict.setText(
                    f"PASSED     {ssim_str}   {psnr_str}{extra}"
                )
                self.lbl_verdict.setStyleSheet(
                    "color: #3fb768; font-weight: 700; font-size: 12px;"
                )
            elif not q.passed:
                self.lbl_verdict.setText("REJECTED — Visual fidelity constraints violated")
                self.lbl_verdict.setStyleSheet(
                    "color: #d84040; font-weight: 700; font-size: 12px;"
                )
            else:
                self.lbl_verdict.setText("WARNING — Metadata or stream artifacts detected")
                self.lbl_verdict.setStyleSheet(
                    "color: #c97f1a; font-weight: 700; font-size: 12px;"
                )
        elif report and report.all_passed:
            self.lbl_verdict.setText("PASSED — Metadata successfully sanitized")
            self.lbl_verdict.setStyleSheet(
                "color: #3fb768; font-weight: 700; font-size: 12px;"
            )
        else:
            self.lbl_verdict.setText("WARNING — Potential artifacts detected")
            self.lbl_verdict.setStyleSheet(
                "color: #c97f1a; font-weight: 700; font-size: 12px;"
            )

        # Quality Gate tab
        if q:
            self._quality_panel.set_report(q, vmaf_evidence_path=vmaf_evidence_path)
        else:
            self._quality_panel.clear()

        # Manifest tab
        self._manifest_tab.set_manifest(q)

        # Switch to Quality Gate tab automatically if processing passed
        if q:
            self.tabs.setCurrentIndex(1)

    def clear(self):
        self._report = None
        self._quality_report = None
        self.txt_report.clear()
        self.lbl_verdict.setText("No report")
        self.lbl_verdict.setStyleSheet("color: #555555; font-weight: 600; font-size: 12px;")
        self.btn_copy_report.setEnabled(False)
        self._quality_panel.clear()
        self._manifest_tab.clear()
        self.tabs.setCurrentIndex(0)

    def _copy_text_report(self):
        if self._report:
            QApplication.clipboard().setText(self._report.format_text())
            self.btn_copy_report.setText("Copied!")
            QTimer.singleShot(2000, lambda: self.btn_copy_report.setText("Copy Text Report"))
