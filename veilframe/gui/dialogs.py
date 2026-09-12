"""
VeilFrame GUI Dialogs: About, Environment Doctor, and Automated Dependency Installer.
=====================================================================================
"""
import os
import sys
from pathlib import Path
from typing import Optional, Callable

from PySide6.QtWidgets import (
    QDialog,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QProgressBar,
    QTableWidget,
    QTableWidgetItem,
    QHeaderView,
    QMessageBox,
    QFrame,
    QScrollArea,
    QGroupBox,
)
from PySide6.QtCore import Qt, QThread, Signal, QUrl
from PySide6.QtGui import QIcon, QPixmap, QDesktopServices, QColor

from ..core.deps_manager import (
    audit_environment,
    download_and_install_ffmpeg,
    is_ffmpeg_installed,
    EnvironmentReport,
)


# ── Dependency Downloader / Installer Worker Thread ──────────────────────── #

class DependencyInstallerWorker(QThread):
    progress = Signal(int, str)
    finished = Signal(bool, str)

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self._is_cancelled = False

    def cancel(self):
        self._is_cancelled = True

    def run(self):
        success, msg = download_and_install_ffmpeg(
            progress_callback=lambda pct, txt: self.progress.emit(pct, txt),
            cancel_check=lambda: self._is_cancelled,
        )
        self.finished.emit(success, msg)


# ── Dependency Installer Dialog ─────────────────────────────────────────── #

class DependencyInstallerDialog(QDialog):
    """Progress dialog for downloading and setting up missing external binaries."""

    def __init__(self, dependency_name: str = "FFmpeg", parent: Optional[QWidget] = None, auto_start: bool = True):
        super().__init__(parent)
        self.setWindowTitle(f"Installing {dependency_name} — VeilFrame")
        self.resize(520, 220)
        self.setModal(True)
        self.setWindowFlags(self.windowFlags() & ~Qt.WindowContextHelpButtonHint)

        self.dependency_name = dependency_name
        self.worker = DependencyInstallerWorker(self)
        self.worker.progress.connect(self._on_progress)
        self.worker.finished.connect(self._on_finished)
        self.install_success = False

        self._init_ui()
        if auto_start:
            self.worker.start()


    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setContentsMargins(24, 20, 24, 20)
        lay.setSpacing(14)

        self.lbl_title = QLabel(f"Downloading & Installing {self.dependency_name}")
        self.lbl_title.setStyleSheet("font-size: 14px; font-weight: 700; color: #ffffff;")
        lay.addWidget(self.lbl_title)

        self.lbl_desc = QLabel(
            f"VeilFrame is automatically fetching the official static binary release and installing it to "
            f"your local user environment (~/.veilframe/bin/)."
        )
        self.lbl_desc.setStyleSheet("color: #a0a0a0; font-size: 11px;")
        self.lbl_desc.setWordWrap(True)
        lay.addWidget(self.lbl_desc)

        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.setStyleSheet("""
            QProgressBar {
                border: 1px solid #333333;
                border-radius: 4px;
                text-align: center;
                background-color: #1a1a1a;
                color: #e0e0e0;
                height: 22px;
                font-size: 11px;
                font-weight: 600;
            }
            QProgressBar::chunk {
                background-color: #2563eb;
                border-radius: 3px;
            }
        """)
        lay.addWidget(self.progress_bar)

        self.lbl_status = QLabel("Initializing connection...")
        self.lbl_status.setStyleSheet("color: #707070; font-size: 11px;")
        lay.addWidget(self.lbl_status)

        btn_row = QHBoxLayout()
        btn_row.addStretch()

        self.btn_cancel = QPushButton("Cancel")
        self.btn_cancel.setStyleSheet("background-color: #262626; color: #d0d0d0; border: 1px solid #404040; padding: 6px 16px;")
        self.btn_cancel.clicked.connect(self._on_cancel)
        btn_row.addWidget(self.btn_cancel)

        self.btn_close = QPushButton("Close")
        self.btn_close.setStyleSheet("background-color: #2563eb; color: #ffffff; font-weight: 600; padding: 6px 16px; border: none; border-radius: 4px;")
        self.btn_close.hide()
        self.btn_close.clicked.connect(self.accept)
        btn_row.addWidget(self.btn_close)

        lay.addLayout(btn_row)

    def _on_progress(self, pct: int, status_msg: str):
        self.progress_bar.setValue(pct)
        self.lbl_status.setText(status_msg)

    def _on_finished(self, success: bool, message: str):
        self.install_success = success
        self.btn_cancel.hide()
        self.btn_close.show()

        if success:
            self.lbl_title.setText(f"{self.dependency_name} Installed Successfully")
            self.lbl_title.setStyleSheet("font-size: 14px; font-weight: 700; color: #3fb768;")
            self.lbl_status.setText(message)
            self.lbl_status.setStyleSheet("color: #3fb768; font-size: 11px; font-weight: 600;")
            self.progress_bar.setValue(100)
        else:
            self.lbl_title.setText(f"Installation Failed")
            self.lbl_title.setStyleSheet("font-size: 14px; font-weight: 700; color: #e53935;")
            self.lbl_status.setText(message)
            self.lbl_status.setStyleSheet("color: #e53935; font-size: 11px;")

    def _on_cancel(self):
        self.lbl_status.setText("Cancelling installation...")
        self.worker.cancel()
        self.btn_cancel.setEnabled(False)


# ── Interactive Missing Dependency Prompt ───────────────────────────────── #

def prompt_missing_dependency(
    parent: QWidget,
    dependency_name: str = "FFmpeg",
    on_success: Optional[Callable[[], None]] = None,
) -> bool:
    """
    Shows a prompt:
    '<dependency_name> is missing. Do you want to install it?'
    With [Cancel] on the left and [OK] on the right.
    
    If OK is chosen, opens DependencyInstallerDialog and triggers on_success if installation succeeds.
    """
    msg_box = QMessageBox(parent)
    msg_box.setWindowTitle(f"{dependency_name} Required")
    msg_box.setText(f"<b>{dependency_name} is missing.</b><br><br>Do you want to install it automatically?")
    msg_box.setIcon(QMessageBox.Question)
    
    # Place Cancel button on the left, OK button on the right
    btn_cancel = msg_box.addButton("Cancel", QMessageBox.RejectRole)
    btn_ok = msg_box.addButton("OK", QMessageBox.AcceptRole)
    msg_box.setDefaultButton(btn_ok)
    
    msg_box.exec()
    
    if msg_box.clickedButton() == btn_ok:
        installer_dlg = DependencyInstallerDialog(dependency_name, parent)
        installer_dlg.exec()
        if installer_dlg.install_success:
            if on_success:
                on_success()
            return True
        return False

    return False


# ── Environment Doctor Dialog ───────────────────────────────────────────── #

class EnvironmentDoctorDialog(QDialog):
    """Comprehensive environment diagnostics and hardware capabilities dialog."""

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("Environment Doctor & Hardware Diagnostics — VeilFrame")
        self.resize(740, 580)
        self.setModal(True)
        self.setWindowFlags(self.windowFlags() & ~Qt.WindowContextHelpButtonHint)

        self._init_ui()
        self.refresh_diagnostics()

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(20, 18, 20, 18)
        main_lay.setSpacing(12)

        # Header
        hdr = QHBoxLayout()
        title_col = QVBoxLayout()
        lbl_title = QLabel("SYSTEM ENVIRONMENT DOCTOR")
        lbl_title.setStyleSheet("font-size: 15px; font-weight: 800; letter-spacing: 1px; color: #ffffff;")
        lbl_sub = QLabel("Comprehensive audit of multimedia encoders, cryptographic providers, and GPU accelerators.")
        lbl_sub.setStyleSheet("font-size: 11px; color: #707070;")
        title_col.addWidget(lbl_title)
        title_col.addWidget(lbl_sub)
        hdr.addLayout(title_col)
        hdr.addStretch()

        self.btn_refresh = QPushButton("Re-scan Environment")
        self.btn_refresh.setStyleSheet("background-color: #262626; color: #e0e0e0; border: 1px solid #404040; padding: 6px 14px; border-radius: 4px;")
        self.btn_refresh.clicked.connect(self.refresh_diagnostics)
        hdr.addWidget(self.btn_refresh)
        main_lay.addLayout(hdr)

        # Table for dependencies
        self.table = QTableWidget()
        self.table.setColumnCount(4)
        self.table.setHorizontalHeaderLabels(["Component", "Version", "Status", "Details"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(3, QHeaderView.Stretch)
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.setStyleSheet("""
            QTableWidget {
                background-color: #141414;
                border: 1px solid #2e2e2e;
                border-radius: 4px;
                gridline-color: #222222;
                color: #e0e0e0;
                font-size: 11px;
            }
            QHeaderView::section {
                background-color: #1e1e1e;
                color: #a0a0a0;
                font-weight: 700;
                font-size: 11px;
                border: 1px solid #282828;
                padding: 6px;
            }
        """)
        main_lay.addWidget(self.table, 1)

        # Hardware & Acceleration Group
        self.hw_box = QGroupBox("HARDWARE ACCELERATION & GPU UTILIZATION")
        hw_lay = QVBoxLayout(self.hw_box)
        hw_lay.setSpacing(6)

        self.lbl_gpus = QLabel("Physical GPUs: Probing...")
        self.lbl_gpus.setStyleSheet("font-size: 11px; color: #d0d0d0;")
        hw_lay.addWidget(self.lbl_gpus)

        self.lbl_encoders = QLabel("Verified Encoders: Probing...")
        self.lbl_encoders.setStyleSheet("font-size: 11px; color: #3fb768;")
        hw_lay.addWidget(self.lbl_encoders)

        self.lbl_privacy_engine = QLabel("Default Privacy Engine: libx264 (Deterministic CPU — RFC Compliance)")
        self.lbl_privacy_engine.setStyleSheet("font-size: 11px; color: #888888; font-style: italic;")
        hw_lay.addWidget(self.lbl_privacy_engine)

        main_lay.addWidget(self.hw_box)

        # Bottom actions
        bot_row = QHBoxLayout()
        self.btn_install_missing = QPushButton("Install Missing Dependencies (FFmpeg)")
        self.btn_install_missing.setStyleSheet("background-color: #2563eb; color: #ffffff; font-weight: 600; padding: 7px 18px; border: none; border-radius: 4px;")
        self.btn_install_missing.clicked.connect(self._install_missing)
        bot_row.addWidget(self.btn_install_missing)

        bot_row.addStretch()

        btn_close = QPushButton("Close")
        btn_close.setStyleSheet("background-color: #262626; color: #d0d0d0; border: 1px solid #404040; padding: 7px 18px; border-radius: 4px;")
        btn_close.clicked.connect(self.accept)
        bot_row.addWidget(btn_close)

        main_lay.addLayout(bot_row)

    def refresh_diagnostics(self):
        report = audit_environment()
        self.table.setRowCount(len(report.items))

        for row, item in enumerate(report.items):
            # Component Name
            item_name = QTableWidgetItem(item.name)
            item_name.setForeground(QColor("#ffffff"))

            # Version
            item_ver = QTableWidgetItem(item.version)
            item_ver.setForeground(QColor("#c0c0c0"))

            # Status Badge
            item_stat = QTableWidgetItem(item.status)
            item_stat.setTextAlignment(Qt.AlignCenter)
            if item.is_ok:
                item_stat.setForeground(QColor("#3fb768"))
            else:
                item_stat.setForeground(QColor("#e53935"))

            # Details
            item_det = QTableWidgetItem(item.details)
            item_det.setForeground(QColor("#888888"))

            self.table.setItem(row, 0, item_name)
            self.table.setItem(row, 1, item_ver)
            self.table.setItem(row, 2, item_stat)
            self.table.setItem(row, 3, item_det)

        # GPUs
        gpu_txt = ", ".join(report.physical_gpus) if report.physical_gpus else "Integrated / CPU Display Controller"
        self.lbl_gpus.setText(f"Physical GPUs: {gpu_txt}")

        # Encoders
        enc_names = [e["codec"] for e in report.verified_encoders]
        enc_txt = ", ".join(enc_names) if enc_names else "None (libx264 CPU fallback)"
        self.lbl_encoders.setText(f"Verified GPU Encoders: {enc_txt}")

        # Missing button visibility
        has_missing_ffmpeg = not is_ffmpeg_installed()
        self.btn_install_missing.setVisible(has_missing_ffmpeg)

    def _install_missing(self):
        installer = DependencyInstallerDialog("FFmpeg", self)
        installer.exec()
        self.refresh_diagnostics()


# ── About Dialog with Clickable GitHub Logo & Link ──────────────────────── #

class AboutDialog(QDialog):
    """Modern About dialog featuring official GitHub repository link with clickable SVG logo."""

    REPO_URL = "https://github.com/sahir247/VeilFrame"

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setWindowTitle("About VeilFrame v2.0")
        self.resize(580, 520)
        self.setModal(True)
        self.setWindowFlags(self.windowFlags() & ~Qt.WindowContextHelpButtonHint)

        self._init_ui()

    def _init_ui(self):
        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 24, 28, 24)
        lay.setSpacing(14)

        # Header card with logo
        hdr_row = QHBoxLayout()
        hdr_row.setSpacing(16)

        # Icon
        icon_path = Path(__file__).resolve().parent.parent / "resources" / "icon.svg"
        lbl_icon = QLabel()
        if icon_path.exists():
            pix = QPixmap(str(icon_path)).scaled(64, 64, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            lbl_icon.setPixmap(pix)
        lbl_icon.setFixedSize(64, 64)
        hdr_row.addWidget(lbl_icon)

        title_col = QVBoxLayout()
        title_col.setSpacing(2)

        lbl_app_name = QLabel("VEILFRAME")
        lbl_app_name.setStyleSheet("font-size: 20px; font-weight: 900; letter-spacing: 2px; color: #ffffff;")
        title_col.addWidget(lbl_app_name)

        lbl_version = QLabel("Version 2.0.0 — Auditable Multimedia Privacy Compiler")
        lbl_version.setStyleSheet("font-size: 11px; color: #38bdf8; font-weight: 600;")
        title_col.addWidget(lbl_version)

        lbl_tagline = QLabel("Providers measure. VeilFrame decides.")
        lbl_tagline.setStyleSheet("font-size: 11px; color: #888888; font-style: italic;")
        title_col.addWidget(lbl_tagline)

        hdr_row.addLayout(title_col)
        hdr_row.addStretch()
        lay.addLayout(hdr_row)

        # Divider
        line = QFrame()
        line.setFrameShape(QFrame.HLine)
        line.setStyleSheet("color: #2e2e2e;")
        lay.addWidget(line)

        # Summary Description
        lbl_desc = QLabel(
            "VeilFrame is a zero-leakage, cryptographically auditable multimedia privacy sanitizer. "
            "It strips forensic metadata, destroys tracking artifacts, applies irreversible constant-fill "
            "redactions, runs 7 adversarial red-team attack probes, and signs every output with Ed25519 digital signatures."
        )
        lbl_desc.setStyleSheet("color: #cccccc; font-size: 12px; line-height: 1.4;")
        lbl_desc.setWordWrap(True)
        lay.addWidget(lbl_desc)

        # Core Guarantees Card
        guar_box = QFrame()
        guar_box.setStyleSheet("background-color: #141414; border: 1px solid #282828; border-radius: 6px; padding: 10px;")
        guar_lay = QVBoxLayout(guar_box)
        guar_lay.setSpacing(6)

        lbl_g_title = QLabel("CORE NORMATIVE GUARANTEES:")
        lbl_g_title.setStyleSheet("font-size: 10px; font-weight: 800; color: #707070; letter-spacing: 1px;")
        guar_lay.addWidget(lbl_g_title)

        items = [
            "100% Offline & Local — Zero cloud transmission, zero telemetry.",
            "Fail-Closed QualityGate — UNKNOWN or partial verification == QUARANTINED.",
            "Deterministic Video Pipeline — Bayer CFA PRNU dither, DCT noise, audio ENF notch.",
            "Image Privacy Compiler — Multi-layer container scrubbing & red-team probe verification.",
            "Cryptographic Provenance — RFC 8785 JSON manifest with Ed25519 signatures.",
        ]
        for item in items:
            lbl_item = QLabel(f"• {item}")
            lbl_item.setStyleSheet("font-size: 11px; color: #a8a8a8;")
            lbl_item.setWordWrap(True)
            guar_lay.addWidget(lbl_item)

        lay.addWidget(guar_box)

        # GitHub Repository Section with Clickable Logo
        gh_box = QFrame()
        gh_box.setStyleSheet("background-color: #181c24; border: 1px solid #233044; border-radius: 6px; padding: 10px 14px;")
        gh_lay = QHBoxLayout(gh_box)
        gh_lay.setSpacing(12)

        # GitHub Icon
        gh_icon_path = Path(__file__).resolve().parent.parent / "resources" / "github_icon.svg"
        lbl_gh_icon = QLabel()
        if gh_icon_path.exists():
            gh_pix = QPixmap(str(gh_icon_path)).scaled(24, 24, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            lbl_gh_icon.setPixmap(gh_pix)
        lbl_gh_icon.setFixedSize(24, 24)
        gh_lay.addWidget(lbl_gh_icon)

        gh_text_col = QVBoxLayout()
        gh_text_col.setSpacing(2)
        lbl_gh_head = QLabel("Open Source on GitHub")
        lbl_gh_head.setStyleSheet("font-size: 12px; font-weight: 700; color: #ffffff;")
        lbl_gh_url = QLabel(self.REPO_URL)
        lbl_gh_url.setStyleSheet("font-size: 11px; color: #38bdf8; text-decoration: underline;")
        lbl_gh_url.setCursor(Qt.PointingHandCursor)
        lbl_gh_url.mousePressEvent = lambda ev: QDesktopServices.openUrl(QUrl(self.REPO_URL))
        gh_text_col.addWidget(lbl_gh_head)
        gh_text_col.addWidget(lbl_gh_url)
        gh_lay.addLayout(gh_text_col)
        gh_lay.addStretch()

        btn_view_repo = QPushButton("View Repository")
        btn_view_repo.setStyleSheet("background-color: #2563eb; color: #ffffff; font-weight: 600; padding: 6px 14px; border: none; border-radius: 4px;")
        btn_view_repo.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(self.REPO_URL)))
        gh_lay.addWidget(btn_view_repo)

        lay.addWidget(gh_box)

        # Bottom Close button
        bot_row = QHBoxLayout()
        bot_row.addStretch()
        btn_close = QPushButton("Close")
        btn_close.setStyleSheet("background-color: #262626; color: #d0d0d0; border: 1px solid #404040; padding: 6px 20px; border-radius: 4px;")
        btn_close.clicked.connect(self.accept)
        bot_row.addWidget(btn_close)
        lay.addLayout(bot_row)
