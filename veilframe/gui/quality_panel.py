"""
VeilFrame UI 2.0 — QualityPanel widget.

Displays the three-tier quality gate verdict and per-metric sparkbars
after processing. Data is sourced from VisualQualityReport only.
No pipeline logic here.

Layout:
  ┌─ QUALITY GATE ─────────────────────────────────────────────┐
  │  Verdict badge row  (PASS / REJECT + Tier 1/2/3 sub-badges) │
  │  ── Rendered Fidelity ──────────────────────────────────────│
  │     SSIM sparkbar row                                       │
  │     PSNR sparkbar row                                       │
  │  ── VMAF Evidence ──────────────────────────────────────────│
  │     VMAF sparkbar row  (or SKIPPED badge)                   │
  │  ── Policy Score ───────────────────────────────────────────│
  │     Aggregate bar + 5-dimension grid                        │
  │  ── Temporal Integrity ─────────────────────────────────────│
  │     Missing / Duplicate / Reordered / Drift badges          │
  │  ── Manifest Attestation ───────────────────────────────────│
  │     Signing mode + fingerprint (truncated)                  │
  └────────────────────────────────────────────────────────────┘
"""
from pathlib import Path
from typing import Optional

from PySide6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QFrame, QProgressBar, QPushButton, QGroupBox,
    QScrollArea, QSizePolicy,
)
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QDesktopServices
from PySide6.QtCore import QUrl

from ..models.video_info import VisualQualityReport
from .theme import badge_style


def _hline() -> QFrame:
    f = QFrame()
    f.setObjectName("hline")
    f.setFrameShape(QFrame.HLine)
    f.setFixedHeight(1)
    f.setStyleSheet("background-color: #2e2e2e; border: none;")
    return f


def _section_label(text: str) -> QLabel:
    lbl = QLabel(text.upper())
    lbl.setObjectName("sectionTitle")
    lbl.setStyleSheet(
        "font-size: 10px; font-weight: 700; letter-spacing: 1.5px; color: #606060;"
    )
    return lbl


def _meta_label(text: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet("color: #606060; font-size: 11px;")
    return lbl


def _value_label(text: str, color: str = "#d0d0d0") -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet(f"color: {color}; font-size: 12px; font-weight: 500;")
    lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
    return lbl


def _badge(text: str, variant: str) -> QLabel:
    lbl = QLabel(text)
    lbl.setStyleSheet(badge_style(variant))
    lbl.setFixedHeight(22)
    return lbl


class SparkBar(QWidget):
    """
    Horizontal metric sparkbar with mean / percentile / worst labels.

    Displays: [metric name] [bar] [mean] [P5] [worst]
    Bar fill is proportional to mean, range [min_val, max_val].
    Always blue regardless of metric.
    """

    def __init__(
        self,
        metric_name: str,
        mean: float,
        p5: float,
        worst: float,
        unit: str = "",
        min_val: float = 0.0,
        max_val: float = 1.0,
        decimals: int = 4,
        parent: Optional[QWidget] = None,
    ):
        super().__init__(parent)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 2, 0, 2)
        lay.setSpacing(10)

        # Metric label
        name_lbl = QLabel(metric_name)
        name_lbl.setFixedWidth(50)
        name_lbl.setStyleSheet("color: #707070; font-size: 11px; font-weight: 600;")
        lay.addWidget(name_lbl)

        # Progress bar (fill = mean fraction)
        bar = QProgressBar()
        bar.setRange(0, 1000)
        frac = max(0.0, min(1.0, (mean - min_val) / max(max_val - min_val, 1e-9)))
        bar.setValue(int(frac * 1000))
        bar.setTextVisible(False)
        bar.setFixedHeight(6)
        bar.setStyleSheet("""
            QProgressBar {
                background-color: #2a2a2a;
                border: none;
                border-radius: 3px;
            }
            QProgressBar::chunk {
                background-color: #3570e6;
                border-radius: 3px;
            }
        """)
        lay.addWidget(bar, 1)

        fmt = f"{{:.{decimals}f}}{unit}"

        def stat_col(label: str, val: float) -> QWidget:
            col = QWidget()
            col_lay = QVBoxLayout(col)
            col_lay.setContentsMargins(0, 0, 0, 0)
            col_lay.setSpacing(0)
            lbl_val = QLabel(fmt.format(val))
            lbl_val.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 600;")
            lbl_key = QLabel(label)
            lbl_key.setStyleSheet("color: #555555; font-size: 9px;")
            col_lay.addWidget(lbl_val, alignment=Qt.AlignCenter)
            col_lay.addWidget(lbl_key, alignment=Qt.AlignCenter)
            col.setFixedWidth(64)
            return col

        lay.addWidget(stat_col("mean", mean))
        lay.addWidget(stat_col("P5", p5))
        lay.addWidget(stat_col("worst", worst))


class QualityPanel(QGroupBox):
    """
    Full quality gate report widget for the UI 2.0 Report → Quality Gate tab.
    Populated from VisualQualityReport after a pipeline run.
    """

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__("QUALITY GATE", parent)
        self._report: Optional[VisualQualityReport] = None
        self._vmaf_evidence_path: Optional[Path] = None
        self._init_ui()

    def _init_ui(self):
        outer = QVBoxLayout(self)
        outer.setSpacing(0)
        outer.setContentsMargins(0, 4, 0, 4)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll.setStyleSheet("background: transparent;")

        self._content = QWidget()
        self._content.setStyleSheet("background: transparent;")
        self._lay = QVBoxLayout(self._content)
        self._lay.setSpacing(10)
        self._lay.setContentsMargins(4, 4, 8, 8)

        self._placeholder = QLabel("No quality report available yet.\nProcess a video to see the gate verdict.")
        self._placeholder.setAlignment(Qt.AlignCenter)
        self._placeholder.setStyleSheet("color: #555555; font-size: 12px; padding: 40px;")
        self._lay.addWidget(self._placeholder)
        self._lay.addStretch()

        scroll.setWidget(self._content)
        outer.addWidget(scroll)

    def set_report(self, report: VisualQualityReport, vmaf_evidence_path: Optional[Path] = None):
        self._report = report
        self._vmaf_evidence_path = vmaf_evidence_path
        self._rebuild()

    def clear(self):
        self._report = None
        self._vmaf_evidence_path = None
        self._rebuild()

    def _rebuild(self):
        # Clear existing content
        while self._lay.count():
            item = self._lay.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

        if not self._report:
            self._placeholder = QLabel(
                "No quality report available yet.\nProcess a video to see the gate verdict."
            )
            self._placeholder.setAlignment(Qt.AlignCenter)
            self._placeholder.setStyleSheet("color: #555555; font-size: 12px; padding: 40px;")
            self._lay.addWidget(self._placeholder)
            self._lay.addStretch()
            return

        r = self._report
        v = r.three_tier_verdict

        # ── Verdict row ────────────────────────────────────────────── #
        verdict_row = QHBoxLayout()
        verdict_row.setSpacing(10)

        overall_variant = "pass" if v.all_passed else "fail"
        overall_text = f"  {'PASS' if v.all_passed else 'REJECT'}  "
        verdict_lbl = _badge(overall_text, overall_variant)
        verdict_lbl.setStyleSheet(
            verdict_lbl.styleSheet()
            + " font-size: 13px; padding: 5px 18px; border-radius: 3px;"
        )
        verdict_row.addWidget(verdict_lbl)
        verdict_row.addSpacing(12)

        for tier_name, passed in [
            ("Tier 1  Policy", v.tier1_policy_passed),
            ("Tier 2  Fidelity", v.tier2_fidelity_passed),
            ("Tier 3  Temporal", v.tier3_temporal_passed),
        ]:
            verdict_row.addWidget(
                _badge(f"{tier_name}  {'OK' if passed else 'FAIL'}", "pass" if passed else "fail")
            )

        verdict_row.addStretch()

        version_lbl = _meta_label(f"quality-gate-v4.0")
        verdict_row.addWidget(version_lbl)

        self._lay.addLayout(verdict_row)
        self._lay.addWidget(_hline())

        # Violations list (if any)
        all_viols = v.tier1_violations + v.tier2_violations + v.tier3_violations
        if all_viols:
            viols_frame = QFrame()
            viols_frame.setStyleSheet(
                "background: #2e1414; border: 1px solid #4a1818; border-radius: 4px; padding: 4px;"
            )
            viols_lay = QVBoxLayout(viols_frame)
            viols_lay.setSpacing(3)
            viols_lay.setContentsMargins(8, 6, 8, 6)
            for viol in all_viols:
                lbl = QLabel(f"• {viol}")
                lbl.setStyleSheet("color: #d84040; font-size: 11px;")
                lbl.setWordWrap(True)
                viols_lay.addWidget(lbl)
            self._lay.addWidget(viols_frame)

        # ── Rendered Fidelity ──────────────────────────────────────── #
        self._lay.addWidget(_section_label("Rendered Fidelity"))

        provider_row = QHBoxLayout()
        provider_row.addWidget(_meta_label("Provider:"))
        provider_row.addWidget(_value_label("ffmpeg-native", "#6090e0"))
        provider_row.addStretch()
        self._lay.addLayout(provider_row)

        fidelity_frame = QFrame()
        fidelity_frame.setObjectName("innerCard")
        fidelity_frame.setStyleSheet("background: #222222; border: 1px solid #333333; border-radius: 4px;")
        fidelity_lay = QVBoxLayout(fidelity_frame)
        fidelity_lay.setContentsMargins(10, 8, 10, 8)
        fidelity_lay.setSpacing(8)

        fidelity_lay.addWidget(SparkBar(
            "SSIM", r.ssim.mean, r.ssim.p5, r.ssim.min_val,
            min_val=0.0, max_val=1.0, decimals=4,
        ))
        fidelity_lay.addWidget(SparkBar(
            "PSNR", r.psnr.mean, r.psnr.p5, r.psnr.min_val,
            unit=" dB", min_val=0.0, max_val=50.0, decimals=2,
        ))
        self._lay.addWidget(fidelity_frame)

        # ── VMAF Evidence ──────────────────────────────────────────── #
        self._lay.addWidget(_section_label("VMAF Evidence"))

        vmaf_entries = [p for p in (r.provider_results or []) if p.get("metric") == "vmaf"]

        if vmaf_entries:
            vmaf = vmaf_entries[0]
            vmaf_frame = QFrame()
            vmaf_frame.setStyleSheet("background: #222222; border: 1px solid #333333; border-radius: 4px;")
            vmaf_inner = QVBoxLayout(vmaf_frame)
            vmaf_inner.setContentsMargins(10, 8, 10, 8)
            vmaf_inner.setSpacing(8)

            vmaf_inner.addWidget(SparkBar(
                "VMAF",
                vmaf.get("mean", 0.0),
                vmaf.get("p5", 0.0),
                vmaf.get("minimum", 0.0),
                min_val=0.0, max_val=100.0, decimals=1,
            ))

            note_row = QHBoxLayout()
            note_badge = _badge("Measurement only — not a gate input in v1.1", "info")
            note_row.addWidget(note_badge)
            note_row.addStretch()
            vmaf_inner.addLayout(note_row)

            # Evidence file row
            evidence_sha = vmaf.get("evidence_sha256")
            ev_path: Optional[Path] = self._vmaf_evidence_path
            if (not ev_path or not ev_path.exists()) and r.evidence_dir:
                candidate = Path(r.evidence_dir) / "vmaf.json"
                if candidate.exists():
                    ev_path = candidate
            if (not ev_path or not ev_path.exists()) and r.manifest_path:
                candidate = Path(r.manifest_path).parent / "vmaf.json"
                if candidate.exists():
                    ev_path = candidate

            if evidence_sha:
                ev_row = QHBoxLayout()
                ev_row.addWidget(_meta_label("Evidence:"))
                sha_short = evidence_sha[:16] + "…"
                ev_row.addWidget(_value_label(f"vmaf.json  SHA-256: {sha_short}", "#707070"))

                if ev_path and ev_path.exists():
                    btn_open = QPushButton("Open")
                    btn_open.setObjectName("iconBtn")
                    btn_open.setFixedWidth(46)
                    target_file = str(ev_path)

                    def _open_evidence(checked=False, f=target_file):
                        QDesktopServices.openUrl(QUrl.fromLocalFile(f))

                    btn_open.clicked.connect(_open_evidence)
                    ev_row.addWidget(btn_open)

                ev_row.addStretch()
                vmaf_inner.addLayout(ev_row)

            self._lay.addWidget(vmaf_frame)
        else:
            skip_row = QHBoxLayout()
            skip_row.addWidget(
                _badge("libvmaf not available in this FFmpeg build — SKIPPED", "skip")
            )
            skip_row.addStretch()
            self._lay.addLayout(skip_row)

        self._lay.addWidget(_hline())

        # ── Policy Score ───────────────────────────────────────────── #
        self._lay.addWidget(_section_label("Transformation Policy Score"))
        ps = r.policy_score

        policy_bar_row = QHBoxLayout()
        policy_bar_row.addWidget(_meta_label("Aggregate:"))
        pbar = QProgressBar()
        pbar.setRange(0, 1000)
        pbar.setValue(int(min(ps.aggregate_policy_score_pct / ps.policy_ceiling_pct, 1.0) * 1000))
        pbar.setTextVisible(False)
        pbar.setFixedHeight(8)
        pbar.setStyleSheet("""
            QProgressBar { background: #2a2a2a; border: none; border-radius: 3px; height: 6px; }
                    QProgressBar::chunk { background-color: #3570e6;
                        border-radius: 3px; }
        """)
        policy_bar_row.addWidget(pbar, 1)
        policy_bar_row.addWidget(_value_label(
            f"{ps.aggregate_policy_score_pct:.2f}% / {ps.policy_ceiling_pct:.1f}%",
            "#3fb768" if ps.passed else "#d84040",
        ))
        self._lay.addLayout(policy_bar_row)

        dims_grid = QGridLayout()
        dims_grid.setHorizontalSpacing(16)
        dims_grid.setVerticalSpacing(4)
        policy_dims = [
            ("Spatial", ps.spatial_score_pct),
            ("Temporal", ps.temporal_score_pct),
            ("Luminance", ps.luminance_score_pct),
            ("Chroma", ps.chroma_score_pct),
            ("Frequency", ps.frequency_score_pct),
        ]
        for i, (dim, val) in enumerate(policy_dims):
            col, row = i % 3, i // 3
            lbl = _meta_label(f"{dim}:")
            val_lbl = _value_label(f"{val:.2f}%", "#d0d0d0")
            dims_grid.addWidget(lbl, row, col * 2)
            dims_grid.addWidget(val_lbl, row, col * 2 + 1)
        self._lay.addLayout(dims_grid)
        self._lay.addWidget(_hline())

        # ── Temporal Integrity ─────────────────────────────────────── #
        self._lay.addWidget(_section_label("Temporal Integrity"))
        tm = r.temporal_metrics
        temp_row = QHBoxLayout()
        temp_row.setSpacing(8)

        def temp_badge(label: str, val: int, max_ok: int = 0):
            ok = val <= max_ok
            return _badge(f"{label}: {val}", "pass" if ok else "fail")

        temp_row.addWidget(temp_badge("Missing", tm.missing_frames))
        temp_row.addWidget(temp_badge("Duplicate", tm.duplicate_frames))
        temp_row.addWidget(temp_badge("Reordered", tm.reordered_frames))
        drift_ok = tm.timestamp_drift_max_sec <= 0.1
        temp_row.addWidget(
            _badge(f"Max drift: {tm.timestamp_drift_max_sec:.4f}s", "pass" if drift_ok else "fail")
        )
        temp_row.addStretch()
        self._lay.addLayout(temp_row)
        self._lay.addWidget(_hline())

        # ── Manifest Attestation ───────────────────────────────────── #
        self._lay.addWidget(_section_label("Manifest Attestation"))
        attest_row = QHBoxLayout()
        attest_row.setSpacing(12)
        signing_variant = "info" if r.signing_mode == "ephemeral" else "pass"
        attest_row.addWidget(_badge(f"Ed25519 {r.signing_mode}", signing_variant))
        if r.public_key_fingerprint:
            fp_short = r.public_key_fingerprint[:32] + "…"
            attest_row.addWidget(_meta_label("Fingerprint:"))
            attest_row.addWidget(_value_label(fp_short, "#707070"))
        attest_row.addStretch()
        self._lay.addLayout(attest_row)

        self._lay.addStretch()
