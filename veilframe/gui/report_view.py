"""
veilframe.gui.report_view — Privacy & Quality Report View.

Supports both Video and Image reports:
  • Video: 3-tier QualityGate verdict (Tier 1 Budget, Tier 2 SSIM/PSNR, Tier 3 PTS Monotonicity) + Video Manifest.
  • Image: 5 Normative Contracts (Privacy, Geometry, Fidelity, Integrity, Completeness) + Red-Team Probes + Signed ImageAuditManifest.
"""

import json
from pathlib import Path
from typing import Optional, Union, Any

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel,
    QTextEdit, QPushButton, QGroupBox, QTabWidget,
    QFrame, QGridLayout, QApplication,
)
from PySide6.QtCore import Qt, QTimer

from ..core.verifier import VerificationReport
from ..models.video_info import VisualQualityReport
from ..image.models.status import CheckStatus
from ..image.pipeline import ImageSanitizationResult
from .quality_panel import QualityPanel, _badge, _meta_label, _value_label, _hline, _section_label


class _ManifestTab(QWidget):
    """Structured manifest summary + raw JSON viewer."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setSpacing(10)
        lay.setContentsMargins(0, 0, 0, 0)

        # Structured summary
        self._summary_frame = QFrame()
        self._summary_frame.setStyleSheet(
            "background: #222222; border: 1px solid #333333; border-radius: 4px;"
        )
        self._summary_lay = QGridLayout(self._summary_frame)
        self._summary_lay.setContentsMargins(12, 10, 12, 10)
        self._summary_lay.setHorizontalSpacing(16)
        self._summary_lay.setVerticalSpacing(5)
        lay.addWidget(self._summary_frame)

        # Raw JSON
        raw_hdr = QHBoxLayout()
        raw_hdr.addWidget(_section_label("Raw Signed Manifest JSON (RFC 8785)"))
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
        self._clear_grid()
        self.btn_copy_json.setEnabled(False)
        self.txt_json.clear()
        self._raw_text = ""

        if not quality_report:
            return

        r = quality_report
        manifest_data: Optional[dict] = None

        if r.manifest_path and Path(r.manifest_path).exists():
            try:
                self._raw_text = Path(r.manifest_path).read_text(encoding="utf-8")
                manifest_data = json.loads(self._raw_text)
            except Exception:
                manifest_data = None

        signing_info = manifest_data.get("signing", {}) if manifest_data else {}
        engine_info = manifest_data.get("quality_engine", {}) if manifest_data else {}

        rows = [
            ("Manifest schema", manifest_data.get("manifest_version", "1.1.0") if manifest_data else "1.1.0"),
            ("Engine / Policy", f"{engine_info.get('engine_version', '1.1.0')} / {engine_info.get('policy_version', '5pct-v1.0')}"),
            ("Signing mode", signing_info.get("mode", r.signing_mode)),
            ("Ed25519 key ID", signing_info.get("key_id") or r.signing_key_id or "ephemeral"),
            ("Input SHA-256", (manifest_data.get("input_sha256") or r.input_sha256)[:24] + "…" if (manifest_data.get("input_sha256") or r.input_sha256) else "—"),
            ("Output SHA-256", (manifest_data.get("output_sha256") or r.output_sha256)[:24] + "…" if (manifest_data.get("output_sha256") or r.output_sha256) else "—"),
            ("Digital Signature", (r.manifest_signature[:24] + "…") if r.manifest_signature else "—"),
            ("Public key fingerprint", (signing_info.get("public_key_fingerprint_raw") or r.public_key_fingerprint)[:28] + "…" if (signing_info.get("public_key_fingerprint_raw") or r.public_key_fingerprint) else "—"),
        ]

        for i, (key, val) in enumerate(rows):
            key_lbl = QLabel(key + ":")
            key_lbl.setStyleSheet("color: #606060; font-size: 11px;")
            val_lbl = QLabel(val)
            val_lbl.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 500;")
            val_lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
            self._summary_lay.addWidget(key_lbl, i, 0)
            self._summary_lay.addWidget(val_lbl, i, 1)

        if not self._raw_text and manifest_data:
            self._raw_text = json.dumps(manifest_data, indent=2)

        self.txt_json.setPlainText(self._raw_text)
        self.btn_copy_json.setEnabled(bool(self._raw_text))

    def set_image_manifest(self, result: ImageSanitizationResult):
        self._clear_grid()
        self.btn_copy_json.setEnabled(False)
        self.txt_json.clear()
        self._raw_text = ""

        if not result or not result.manifest:
            return

        m = result.manifest
        m_dict = m.to_dict()
        self._raw_text = json.dumps(m_dict, indent=2)

        ip = m.identity_preimage
        ep = m.evidence_preimage

        rows = [
            ("Manifest schema", m.schema_version),
            ("Domain Separation", "VEILFRAME-IDENTITY-V1 / VEILFRAME-EVIDENCE-V1"),
            ("Signer Identity", f"{ep.signer_identity.trust_anchor_id} ({ep.signer_identity.key_id})"),
            ("Raw Source Digest", ip.raw_source_hash[:24] + "…"),
            ("Candidate Output Digest", ip.candidate_output_hash[:24] + "…"),
            ("Final Artifact Digest", ep.final_artifact_hash[:24] + "…"),
            ("Publication Integrity", "HOLDS (Candidate == Final)" if m.publication_integrity_holds() else "VIOLATION"),
            ("Transformation DAG Hash", ip.dag_hash[:24] + "…"),
            ("Verification Plan Hash", ip.verification_plan_hash[:24] + "…"),
            ("Ed25519 Signature", m.ed25519_signature_hex[:24] + "…"),
        ]

        for i, (key, val) in enumerate(rows):
            key_lbl = QLabel(key + ":")
            key_lbl.setStyleSheet("color: #606060; font-size: 11px;")
            val_lbl = QLabel(val)
            val_lbl.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 500;")
            val_lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
            self._summary_lay.addWidget(key_lbl, i, 0)
            self._summary_lay.addWidget(val_lbl, i, 1)

        self.txt_json.setPlainText(self._raw_text)
        self.btn_copy_json.setEnabled(True)

    def _clear_grid(self):
        while self._summary_lay.count():
            item = self._summary_lay.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def _copy_json(self):
        QApplication.clipboard().setText(self._raw_text)
        self.btn_copy_json.setText("Copied!")
        QTimer.singleShot(2000, lambda: self.btn_copy_json.setText("Copy JSON"))

    def clear(self):
        self._clear_grid()
        self.btn_copy_json.setEnabled(False)
        self.txt_json.clear()
        self._raw_text = ""


class ImageContractsWidget(QWidget):
    """Visual panel for Image Privacy 5-Contract and Red-Team results."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setSpacing(12)
        lay.setContentsMargins(0, 8, 0, 0)

        # 1. Five Contracts Grid
        c_box = QGroupBox("FIVE NORMATIVE CONTRACTS (FAIL-CLOSED)")
        c_lay = QGridLayout(c_box)
        c_lay.setHorizontalSpacing(16)
        c_lay.setVerticalSpacing(8)

        self._c_privacy = _badge("skip", "SKIP")
        self._c_geometry = _badge("skip", "SKIP")
        self._c_fidelity = _badge("skip", "SKIP")
        self._c_integrity = _badge("skip", "SKIP")
        self._c_completeness = _badge("skip", "SKIP")

        c_lay.addWidget(QLabel("1. Privacy Contract (Zero Residue / Red-Team Probes):"), 0, 0)
        c_lay.addWidget(self._c_privacy, 0, 1)

        c_lay.addWidget(QLabel("2. Geometry Contract (||Observed - Expected|| <= Tol):"), 1, 0)
        c_lay.addWidget(self._c_geometry, 1, 1)

        c_lay.addWidget(QLabel("3. Fidelity Contract (Outside-Mask SSIM >= 0.95, PSNR >= 35dB):"), 2, 0)
        c_lay.addWidget(self._c_fidelity, 2, 1)

        c_lay.addWidget(QLabel("4. Integrity Contract (Publication Candidate == Artifact):"), 3, 0)
        c_lay.addWidget(self._c_integrity, 3, 1)

        c_lay.addWidget(QLabel("5. Completeness Contract (100% Redaction Execution):"), 4, 0)
        c_lay.addWidget(self._c_completeness, 4, 1)

        for r in range(5):
            item = c_lay.itemAtPosition(r, 0)
            if item and item.widget():
                item.widget().setStyleSheet("color: #a0a0a0; font-size: 11px;")

        lay.addWidget(c_box)

        # 2. Red-Team Probes Breakdown Card
        rt_box = QGroupBox("ADVERSARIAL RED-TEAM PROBE MATRIX")
        self.rt_lay = QVBoxLayout(rt_box)
        self.lbl_rt_summary = QLabel("Probes: Not executed yet.")
        self.lbl_rt_summary.setStyleSheet("color: #606060; font-size: 11px;")
        self.rt_lay.addWidget(self.lbl_rt_summary)
        lay.addWidget(rt_box)

    def set_result(self, result: ImageSanitizationResult):
        v = result.verdict
        if not v:
            return

        def _map_badge(status: CheckStatus, lbl: QLabel):
            if status == CheckStatus.PASS:
                lbl.setText("PASS")
                lbl.setObjectName("badgePass")
                lbl.setStyleSheet("background-color: #162a1e; color: #3fb768; border: 1px solid #1e4a2e; border-radius: 3px; padding: 2px 8px; font-weight: 700;")
            else:
                lbl.setText("FAIL")
                lbl.setObjectName("badgeFail")
                lbl.setStyleSheet("background-color: #2e1414; color: #d84040; border: 1px solid #4a1818; border-radius: 3px; padding: 2px 8px; font-weight: 700;")

        _map_badge(v.privacy_status, self._c_privacy)
        _map_badge(v.geometry_status, self._c_geometry)
        _map_badge(v.fidelity_status, self._c_fidelity)
        _map_badge(CheckStatus.PASS if result.manifest and result.manifest.publication_integrity_holds() else CheckStatus.FAIL, self._c_integrity)
        _map_badge(v.completeness_status, self._c_completeness)

        # Red-Team summary
        rt = result.red_team_result
        if rt:
            lines = [f"Overall Status: {rt.overall_status.name}  |  Total Probes: {rt.total_probes_run}  |  Detections: {rt.total_detections}"]
            for p in rt.probe_results:
                indep_str = f"L{p.independence_level.value}" if hasattr(p, "independence_level") else "L3"
                lines.append(f"  ● {p.probe_id.ljust(22)} [{p.status.name.ljust(4)}]  Confidence: {p.confidence:.2f}  Residual: {p.detected_count} ({indep_str})")
            self.lbl_rt_summary.setText("\n".join(lines))
            self.lbl_rt_summary.setStyleSheet("font-family: 'Cascadia Code', monospace; color: #b0b0b0; font-size: 11px;")

    def clear(self):
        for b in (self._c_privacy, self._c_geometry, self._c_fidelity, self._c_integrity, self._c_completeness):
            b.setText("SKIP")
            b.setStyleSheet("background-color: #202020; color: #555555; border: 1px solid #303030; border-radius: 3px; padding: 2px 8px;")
        self.lbl_rt_summary.setText("Probes: Not executed yet.")
        self.lbl_rt_summary.setStyleSheet("color: #606060; font-size: 11px;")


class ReportViewWidget(QGroupBox):
    """Multi-tab report panel for Video & Image sanitization."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("PRIVACY & QUALITY REPORT", parent)
        self._report: Optional[VerificationReport] = None
        self._image_result: Optional[ImageSanitizationResult] = None
        self._init_ui()

    def _init_ui(self):
        outer = QVBoxLayout(self)
        outer.setSpacing(8)
        outer.setContentsMargins(8, 8, 8, 8)

        # Verdict banner
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

        # Tab 2 — Quality Gate (Video)
        self._quality_panel = QualityPanel()
        self.tabs.addTab(self._quality_panel, "Video Quality Gate")

        # Tab 3 — Image Contracts & Red-Team (Image)
        self._image_contracts_panel = ImageContractsWidget()
        self.tabs.addTab(self._image_contracts_panel, "Image 5-Contracts")

        # Tab 4 — Manifest
        self._manifest_tab = _ManifestTab()
        self.tabs.addTab(self._manifest_tab, "Audit Manifest")

    def set_report(self, report: VerificationReport):
        self._report = report
        self._image_result = None

        self.txt_report.setPlainText(report.format_text() if report else "")
        self.btn_copy_report.setEnabled(bool(report))

        q = report.quality_report if report else None
        if q and report.all_passed and q.passed:
            self.lbl_verdict.setText(f"PASSED     SSIM: {q.ssim.mean:.4f}   PSNR: {q.psnr.mean:.1f} dB")
            self.lbl_verdict.setStyleSheet("color: #3fb768; font-weight: 700; font-size: 12px;")
        elif q and not q.passed:
            self.lbl_verdict.setText("REJECTED — Visual fidelity constraints violated")
            self.lbl_verdict.setStyleSheet("color: #d84040; font-weight: 700; font-size: 12px;")
        elif report and report.all_passed:
            self.lbl_verdict.setText("PASSED — Metadata successfully sanitized")
            self.lbl_verdict.setStyleSheet("color: #3fb768; font-weight: 700; font-size: 12px;")
        else:
            self.lbl_verdict.setText("WARNING — Artifacts or constraints detected")
            self.lbl_verdict.setStyleSheet("color: #c97f1a; font-weight: 700; font-size: 12px;")

        if q:
            self._quality_panel.set_report(q)
            self._manifest_tab.set_manifest(q)
            self.tabs.setCurrentIndex(1)
        else:
            self._quality_panel.clear()

    def set_image_report(self, result: ImageSanitizationResult):
        self._image_result = result
        self._report = None

        # Build clean formatted text
        lines = [
            "==================================================",
            "   VEILFRAME IMAGE PRIVACY SANITIZATION REPORT   ",
            "==================================================",
            f"Overall Verdict: {result.status.name}",
            f"Publication State: {result.manifest.publication_state.value.upper() if result.manifest else 'N/A'}",
            f"Candidate Hash: {result.manifest.identity_preimage.candidate_output_hash if result.manifest else 'N/A'}",
            "",
            "--- Five Normative Contracts ---",
            f"  1. Privacy Contract:     {result.verdict.privacy_status.name}",
            f"  2. Geometry Contract:    {result.verdict.geometry_status.name}",
            f"  3. Fidelity Contract:    {result.verdict.fidelity_status.name}",
            f"  4. Integrity Contract:   {'PASS' if result.manifest and result.manifest.publication_integrity_holds() else 'FAIL'}",
            f"  5. Completeness Contract:{result.verdict.completeness_status.name}",
            "",
            "--- Outside-Mask Fidelity Metrics ---",
            f"  SSIM (non-redacted):     {result.fidelity_result.ssim:.4f if result.fidelity_result and result.fidelity_result.ssim is not None else 'N/A'} (>= 0.95)",
            f"  PSNR (non-redacted):     {result.fidelity_result.psnr_db:.1f} dB if result.fidelity_result and result.fidelity_result.psnr_db is not None else 'N/A' (>= 35 dB)",
            f"  MAE  (non-redacted):     {result.fidelity_result.mae:.6f if result.fidelity_result and result.fidelity_result.mae is not None else 'N/A'} (<= 0.02)",
            "",
            "--- Adversarial Red-Team Probes ---",
        ]
        if result.red_team_result:
            for p in result.red_team_result.probe_results:
                lines.append(f"  ● {p.probe_id.ljust(22)}: {p.status.name} (confidence={p.confidence:.2f}, detected={p.detected_count})")

        text_out = "\n".join(lines)
        self.txt_report.setPlainText(text_out)
        self.btn_copy_report.setEnabled(True)

        if result.is_success:
            self.lbl_verdict.setText("PASSED — All 5 Contracts & Red-Team Probes Verified")
            self.lbl_verdict.setStyleSheet("color: #3fb768; font-weight: 700; font-size: 12px;")
        else:
            self.lbl_verdict.setText("FAIL / QUARANTINED — Policy or Contract Violation")
            self.lbl_verdict.setStyleSheet("color: #d84040; font-weight: 700; font-size: 12px;")

        self._image_contracts_panel.set_result(result)
        self._manifest_tab.set_image_manifest(result)
        self.tabs.setCurrentIndex(2)

    def clear(self):
        self._report = None
        self._image_result = None
        self.txt_report.clear()
        self.lbl_verdict.setText("No report")
        self.lbl_verdict.setStyleSheet("color: #555555; font-weight: 600; font-size: 12px;")
        self.btn_copy_report.setEnabled(False)
        self._quality_panel.clear()
        self._image_contracts_panel.clear()
        self._manifest_tab.clear()
        self.tabs.setCurrentIndex(0)

    def _copy_text_report(self):
        text = self.txt_report.toPlainText()
        if text:
            QApplication.clipboard().setText(text)
            self.btn_copy_report.setText("Copied!")
            QTimer.singleShot(2000, lambda: self.btn_copy_report.setText("Copy Text Report"))
