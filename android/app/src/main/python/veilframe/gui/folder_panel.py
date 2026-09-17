"""
veilframe.gui.folder_panel — Interactive Folder Analyzer & Duplicate Scanner UI.

Features:
  • Configurable scan options with predefined profiles (Quick, Full Meta, Integrity, Duplicates, Custom)
  • Progressive live tree animation during active scanning (no freezing)
  • Animated pulsating progress bar with live throughput & item counters
  • Dynamic multi-column Tree & Table results viewers without emoji clutter
  • SQLite-backed instant multi-field search and filter
  • Summary analytics dashboard and duplicate file groups viewer
  • Multi-format report exporter (HTML, JSON, CSV, Markdown, TXT)
"""

from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Dict, List, Optional

from PySide6.QtCore import Qt, QThread, QTimer, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMenu,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QSplitter,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..folder.config import HashAlgorithm, ScanConfig, ScanProfile
from ..folder.database import FolderDatabase
from ..folder.exporter import FolderExporter
from ..folder.models import (
    DuplicateGroup,
    ExtensionStat,
    FileRecord,
    FolderRecord,
    ScanError,
    ScanResult,
    ScanStats,
    format_bytes,
)
from ..folder.scanner import FolderScanner
from .folder.ai_program_lister_panel import AIProgramListerPanel


class FolderScanWorker(QThread):
    """Background QThread worker executing FolderScanner with live progressive streaming."""

    progress = Signal(int, int, str)       # files_count, folders_count, status_message
    folderDiscovered = Signal(object)      # FolderRecord
    fileDiscovered = Signal(object)        # FileRecord
    finished = Signal(object)              # ScanResult
    failed = Signal(str)                   # error_message

    def __init__(self, root_path: str, config: ScanConfig, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.root_path = root_path
        self.config = config
        self.scanner = FolderScanner(config=self.config)

    def cancel(self):
        self.scanner.cancel()

    def run(self):
        try:
            def _on_prog(files_c: int, fld_c: int, msg: str):
                self.progress.emit(files_c, fld_c, msg)

            def _on_file(f_rec: FileRecord):
                # Omit per-file Qt signal to prevent saturating the main UI event loop during large scans
                pass

            def _on_folder(fld_rec: FolderRecord):
                self.folderDiscovered.emit(fld_rec)

            result = self.scanner.scan(
                root_path=self.root_path,
                on_progress=_on_prog,
                on_file_found=_on_file,
                on_folder_found=_on_folder,
            )
            self.finished.emit(result)
        except Exception as e:
            self.failed.emit(str(e))


class FolderAnalyzerPanel(QWidget):
    """Main Folder Analyzer view with profile configuration and live interactive tree."""

    scanRequested = Signal(str, object)  # root_path, ScanConfig

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.current_result: Optional[ScanResult] = None
        self.db: Optional[FolderDatabase] = None
        self.worker: Optional[FolderScanWorker] = None

        # Map for progressive tree node rendering: folder_id -> QTreeWidgetItem
        self._tree_folder_items: Dict[int, QTreeWidgetItem] = {}
        self._scan_start_time: float = 0.0
        self._live_timer = QTimer(self)
        self._live_timer.setInterval(150)
        self._live_timer.timeout.connect(self._on_timer_tick)
        self._files_count_live: int = 0
        self._folders_count_live: int = 0
        self._last_status_msg: str = ""
        self._is_ai_mode: bool = False

        self._init_ui()
        self._apply_profile(ScanProfile.QUICK)

    def _init_ui(self):
        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(0, 0, 0, 0)
        main_lay.setSpacing(10)

        # ----------------------------------------------------
        # 1. Folder Selection Bar
        # ----------------------------------------------------
        folder_box = QFrame()
        folder_box.setStyleSheet("background: #222222; border: 1px solid #333333; border-radius: 6px; padding: 6px;")
        folder_lay = QHBoxLayout(folder_box)
        folder_lay.setContentsMargins(8, 4, 8, 4)
        folder_lay.setSpacing(8)

        lbl_folder = QLabel("Target Directory:")
        lbl_folder.setStyleSheet("color: #a0a0a0; font-size: 11px; font-weight: 600;")
        folder_lay.addWidget(lbl_folder)

        self.txt_folder_path = QLineEdit()
        self.txt_folder_path.setPlaceholderText("Select a directory to analyze...")
        self.txt_folder_path.setStyleSheet(
            "background: #181818; color: #ffffff; border: 1px solid #404040; "
            "padding: 6px 10px; border-radius: 4px; font-size: 11px;"
        )
        folder_lay.addWidget(self.txt_folder_path, 1)

        self.btn_browse = QPushButton("Browse Folder...")
        self.btn_browse.setStyleSheet(
            "background: #2b2b2b; color: #e0e0e0; border: 1px solid #484848; "
            "padding: 6px 14px; border-radius: 4px; font-size: 11px; font-weight: 600;"
        )
        self.btn_browse.clicked.connect(self._on_browse)
        folder_lay.addWidget(self.btn_browse)

        self.btn_start_scan = QPushButton("START SCAN")
        self.btn_start_scan.setStyleSheet(
            "background: #2563eb; color: #ffffff; border: none; "
            "padding: 6px 20px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
        )
        self.btn_start_scan.clicked.connect(self._start_scan)
        folder_lay.addWidget(self.btn_start_scan)

        self.btn_cancel_scan = QPushButton("Cancel Scan")
        self.btn_cancel_scan.setStyleSheet(
            "background: #dc2626; color: #ffffff; border: none; "
            "padding: 6px 14px; border-radius: 4px; font-size: 11px; font-weight: 600;"
        )
        self.btn_cancel_scan.hide()
        self.btn_cancel_scan.clicked.connect(self._cancel_scan)
        folder_lay.addWidget(self.btn_cancel_scan)

        main_lay.addWidget(folder_box)

        # ----------------------------------------------------
        # 2. Config & Profile Section
        # ----------------------------------------------------
        config_frame = QFrame()
        config_frame.setStyleSheet("background: #1e1e1e; border: 1px solid #2e2e2e; border-radius: 6px;")
        cfg_lay = QVBoxLayout(config_frame)
        cfg_lay.setContentsMargins(12, 10, 12, 10)
        cfg_lay.setSpacing(8)

        # Profile Selection Row
        prof_row = QHBoxLayout()
        prof_lbl = QLabel("Scan Profile:")
        prof_lbl.setStyleSheet("color: #38bdf8; font-size: 11px; font-weight: 700;")
        prof_row.addWidget(prof_lbl)

        self.combo_profile = QComboBox()
        self.combo_profile.addItems([
            "Quick Scan (Name + Size + Modified)",
            "Full Metadata (All File Attributes)",
            "Integrity Scan (Metadata + SHA-256)",
            "Duplicate Finder (Staged Hash)",
            "AI-Ready Project Scan",
            "Custom Configuration",
        ])
        self.combo_profile.setStyleSheet(
            "background: #262626; color: #ffffff; border: 1px solid #444444; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        self.combo_profile.currentIndexChanged.connect(self._on_profile_changed)
        prof_row.addWidget(self.combo_profile, 1)

        prof_row.addSpacing(16)
        lbl_workers = QLabel("Hash Workers:")
        lbl_workers.setStyleSheet("color: #888888; font-size: 11px;")
        prof_row.addWidget(lbl_workers)

        self.combo_workers = QComboBox()
        self.combo_workers.addItems(["Auto (Optimized)", "1 Worker", "2 Workers", "4 Workers", "8 Workers"])
        self.combo_workers.setStyleSheet(
            "background: #262626; color: #ffffff; border: 1px solid #444444; "
            "padding: 4px 8px; border-radius: 4px; font-size: 11px;"
        )
        prof_row.addWidget(self.combo_workers)

        cfg_lay.addLayout(prof_row)

        # Active Metadata Checkboxes Grid (All fully clickable and active)
        grid = QGridLayout()
        grid.setHorizontalSpacing(16)
        grid.setVerticalSpacing(6)

        self.chk_ext = QCheckBox("Extension")
        self.chk_ext.setChecked(True)

        self.chk_size = QCheckBox("File Size")
        self.chk_size.setChecked(True)

        self.chk_modified = QCheckBox("Modified Date")
        self.chk_modified.setChecked(True)

        self.chk_created = QCheckBox("Created Date")
        self.chk_accessed = QCheckBox("Accessed Date")
        self.chk_perms = QCheckBox("Permissions")
        self.chk_hidden = QCheckBox("Hidden Files")

        self.chk_hash = QCheckBox("Cryptographic Hash:")
        self.combo_hash_algo = QComboBox()
        self.combo_hash_algo.addItems(["SHA-256", "SHA-1", "MD5"])
        self.combo_hash_algo.setStyleSheet("background: #262626; color: #fff; padding: 2px 6px; font-size: 10px;")

        self.chk_dupes = QCheckBox("Detect Duplicate Files")
        self.chk_recursive = QCheckBox("Include Subfolders")
        self.chk_recursive.setChecked(True)

        chk_style = "QCheckBox { color: #d0d0d0; font-size: 11px; } QCheckBox::indicator { width: 14px; height: 14px; }"
        for chk in (
            self.chk_ext, self.chk_size, self.chk_modified,
            self.chk_created, self.chk_accessed, self.chk_perms, self.chk_hidden,
            self.chk_hash, self.chk_dupes, self.chk_recursive
        ):
            chk.setStyleSheet(chk_style)

        grid.addWidget(self.chk_ext, 0, 0)
        grid.addWidget(self.chk_size, 0, 1)
        grid.addWidget(self.chk_modified, 0, 2)
        grid.addWidget(self.chk_created, 0, 3)

        grid.addWidget(self.chk_accessed, 1, 0)
        grid.addWidget(self.chk_perms, 1, 1)
        grid.addWidget(self.chk_hidden, 1, 2)
        grid.addWidget(self.chk_recursive, 1, 3)

        hash_h = QHBoxLayout()
        hash_h.setSpacing(4)
        hash_h.addWidget(self.chk_hash)
        hash_h.addWidget(self.combo_hash_algo)
        hash_w = QWidget()
        hash_w.setLayout(hash_h)
        grid.addWidget(hash_w, 2, 0, 1, 2)

        grid.addWidget(self.chk_dupes, 2, 2, 1, 2)

        cfg_lay.addLayout(grid)
        main_lay.addWidget(config_frame)

        # ----------------------------------------------------
        # 3. Live Animated Progress Bar & Pulse Status Bar
        # ----------------------------------------------------
        self.prog_box = QFrame()
        self.prog_box.setStyleSheet("""
            QFrame {
                background: #141b26;
                border: 1px solid #1e3a8a;
                border-radius: 6px;
                padding: 6px;
            }
        """)
        self.prog_box.hide()
        prog_lay = QVBoxLayout(self.prog_box)
        prog_lay.setContentsMargins(10, 6, 10, 6)
        prog_lay.setSpacing(6)

        # Header metrics row while scanning
        prog_hdr = QHBoxLayout()
        self.lbl_progress_phase = QLabel("SCANNING FILESYSTEM")
        self.lbl_progress_phase.setStyleSheet("color: #38bdf8; font-size: 10px; font-weight: 800; letter-spacing: 1px;")
        prog_hdr.addWidget(self.lbl_progress_phase)

        self.lbl_progress_counters = QLabel("0 files | 0 folders | 00:00.0")
        self.lbl_progress_counters.setStyleSheet("color: #94a3b8; font-size: 11px; font-family: Consolas, monospace;")
        prog_hdr.addStretch()
        prog_hdr.addWidget(self.lbl_progress_counters)
        prog_lay.addLayout(prog_hdr)

        # Animated Gradient Progress Bar
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 0)  # Animated indeterminate pulse during walk
        self.progress_bar.setStyleSheet("""
            QProgressBar {
                background-color: #0f172a;
                border: 1px solid #334155;
                border-radius: 4px;
                height: 16px;
                text-align: center;
                color: #ffffff;
                font-size: 10px;
                font-weight: 600;
            }
            QProgressBar::chunk {
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                    stop:0 #0284c7, stop:0.5 #38bdf8, stop:1 #0284c7);
                border-radius: 3px;
            }
        """)
        prog_lay.addWidget(self.progress_bar)

        self.lbl_progress_status = QLabel("Initializing scanner...")
        self.lbl_progress_status.setStyleSheet("color: #cbd5e1; font-size: 11px; font-family: Consolas, monospace;")
        prog_lay.addWidget(self.lbl_progress_status)

        main_lay.addWidget(self.prog_box)

        # ----------------------------------------------------
        # 4. Interactive Tabs & Results Viewer
        # ----------------------------------------------------
        self.tabs = QTabWidget()
        self.tabs.setStyleSheet("""
            QTabWidget::pane { border: 1px solid #2e2e2e; background: #181818; }
            QTabBar::tab { background: #222222; color: #888888; padding: 8px 18px; font-size: 11px; font-weight: 600; }
            QTabBar::tab:selected { background: #181818; color: #38bdf8; border-top: 2px solid #38bdf8; }
        """)

        # Tab 1: Directory Explorer (Interactive Tree)
        self.tab_tree = QWidget()
        tree_lay = QVBoxLayout(self.tab_tree)
        tree_lay.setContentsMargins(8, 8, 8, 8)
        tree_lay.setSpacing(6)

        # Search & Export Bar
        search_lay = QHBoxLayout()
        search_lbl = QLabel("Filter:")
        search_lbl.setStyleSheet("color: #888888; font-size: 11px; font-weight: 600;")
        search_lay.addWidget(search_lbl)

        self.txt_search = QLineEdit()
        self.txt_search.setPlaceholderText("Filter files by name, extension, or relative path...")
        self.txt_search.setStyleSheet(
            "background: #222222; color: #ffffff; border: 1px solid #3a3a3a; "
            "padding: 5px 10px; border-radius: 4px; font-size: 11px;"
        )
        self.txt_search.textChanged.connect(self._on_search_text_changed)
        search_lay.addWidget(self.txt_search, 1)

        self.btn_export = QPushButton("Export Report...")
        self.btn_export.setStyleSheet(
            "background: #1e293b; color: #38bdf8; border: 1px solid #0284c7; "
            "padding: 5px 14px; border-radius: 4px; font-size: 11px; font-weight: 600;"
        )
        self.btn_export.clicked.connect(self._on_export_clicked)
        search_lay.addWidget(self.btn_export)

        tree_lay.addLayout(search_lay)

        # Clean Multi-Column Tree Widget
        self.tree_widget = QTreeWidget()
        self.tree_widget.setHeaderLabels(["Name", "Type", "Size", "Extension", "Modified", "SHA-256"])
        self.tree_widget.setStyleSheet("""
            QTreeWidget {
                background: #151515;
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
        self.tree_widget.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tree_widget.header().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.tree_widget.header().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.tree_widget.header().setSectionResizeMode(3, QHeaderView.ResizeToContents)
        self.tree_widget.header().setSectionResizeMode(4, QHeaderView.ResizeToContents)
        self.tree_widget.header().setSectionResizeMode(5, QHeaderView.ResizeToContents)
        self.tree_widget.setContextMenuPolicy(Qt.CustomContextMenu)
        self.tree_widget.customContextMenuRequested.connect(self._on_tree_context_menu)
        self.tree_widget.itemClicked.connect(self._on_tree_item_clicked)
        self.tree_widget.itemDoubleClicked.connect(self._on_tree_item_double_clicked)
        tree_lay.addWidget(self.tree_widget)

        self.tabs.addTab(self.tab_tree, "Directory Explorer")

        # Tab 2: Analytics & Summary Metrics
        self.tab_stats = QWidget()
        stats_scroll = QScrollArea()
        stats_scroll.setWidgetResizable(True)
        stats_scroll.setFrameShape(QFrame.NoFrame)
        stats_content = QWidget()
        self.stats_lay = QVBoxLayout(stats_content)
        self.stats_lay.setContentsMargins(12, 12, 12, 12)
        self.stats_lay.setSpacing(12)

        self._build_stats_view(self.stats_lay)
        stats_scroll.setWidget(stats_content)
        tab_stats_lay = QVBoxLayout(self.tab_stats)
        tab_stats_lay.setContentsMargins(0, 0, 0, 0)
        tab_stats_lay.addWidget(stats_scroll)
        self.tabs.addTab(self.tab_stats, "Summary Metrics")

        # Tab 3: Duplicate Groups
        self.tab_dupes = QWidget()
        dupes_lay = QVBoxLayout(self.tab_dupes)
        dupes_lay.setContentsMargins(8, 8, 8, 8)
        dupes_lay.setSpacing(6)

        self.lbl_dupes_summary = QLabel("Run a duplicate scan to detect identical files.")
        self.lbl_dupes_summary.setStyleSheet("color: #94a3b8; font-size: 11px; font-weight: 600;")
        dupes_lay.addWidget(self.lbl_dupes_summary)

        self.dupes_tree = QTreeWidget()
        self.dupes_tree.setHeaderLabels(["Duplicate Group / Path", "Size", "Copies", "Wasted Space", "Hash"])
        self.dupes_tree.setStyleSheet(self.tree_widget.styleSheet())
        self.dupes_tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.dupes_tree.setContextMenuPolicy(Qt.CustomContextMenu)
        self.dupes_tree.customContextMenuRequested.connect(self._on_dupes_context_menu)
        self.dupes_tree.itemDoubleClicked.connect(self._on_dupes_item_double_clicked)
        dupes_lay.addWidget(self.dupes_tree)

        self.tabs.addTab(self.tab_dupes, "Duplicate Groups")

        # Tab 4: AI Program Lister & Bundle Generator
        self.tab_ai = AIProgramListerPanel()
        self.tabs.addTab(self.tab_ai, "AI Program Lister")

        main_lay.addWidget(self.tabs, 1)

    def _build_stats_view(self, layout: QVBoxLayout):
        """Construct dashboard cards and extension distribution table."""
        cards_frame = QFrame()
        cards_frame.setStyleSheet("background: #1e1e1e; border: 1px solid #303030; border-radius: 6px; padding: 10px;")
        grid = QGridLayout(cards_frame)
        grid.setHorizontalSpacing(16)
        grid.setVerticalSpacing(10)

        self.lbl_stat_files = QLabel("0")
        self.lbl_stat_folders = QLabel("0")
        self.lbl_stat_size = QLabel("0 B")
        self.lbl_stat_wasted = QLabel("0 B")
        self.lbl_stat_speed = QLabel("0 items/s")
        self.lbl_stat_depth = QLabel("0")

        val_style = "color: #38bdf8; font-size: 17px; font-weight: 700; font-family: Consolas, monospace;"
        lbl_style = "color: #888888; font-size: 10px; text-transform: uppercase; font-weight: 600; letter-spacing: 0.5px;"

        items = [
            ("TOTAL FILES", self.lbl_stat_files, 0, 0),
            ("TOTAL FOLDERS", self.lbl_stat_folders, 0, 1),
            ("TOTAL SIZE", self.lbl_stat_size, 0, 2),
            ("DUPLICATE WASTED", self.lbl_stat_wasted, 1, 0),
            ("SCAN SPEED", self.lbl_stat_speed, 1, 1),
            ("MAX DEPTH", self.lbl_stat_depth, 1, 2),
        ]

        for title, widget, r, c in items:
            box = QVBoxLayout()
            lbl = QLabel(title)
            lbl.setStyleSheet(lbl_style)
            widget.setStyleSheet(val_style)
            box.addWidget(lbl)
            box.addWidget(widget)
            w = QWidget()
            w.setLayout(box)
            grid.addWidget(w, r, c)

        layout.addWidget(cards_frame)

        # Extension table
        ext_title = QLabel("FILE TYPE DISTRIBUTION")
        ext_title.setStyleSheet("color: #d0d0d0; font-size: 11px; font-weight: 700; letter-spacing: 1px;")
        layout.addWidget(ext_title)

        self.ext_table = QTableWidget()
        self.ext_table.setColumnCount(4)
        self.ext_table.setHorizontalHeaderLabels(["Extension", "File Count", "Total Size", "% Space"])
        self.ext_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.ext_table.setStyleSheet(self.tree_widget.styleSheet())
        self.ext_table.setFixedHeight(220)
        layout.addWidget(self.ext_table)

    def _on_browse(self):
        folder = QFileDialog.getExistingDirectory(self, "Select Directory to Analyze")
        if folder:
            self.set_folder_path(folder)

    def set_folder_path(self, folder_path: str):
        self.txt_folder_path.setText(folder_path)

    def set_ai_mode(self, is_ai: bool):
        """Switch view configuration and tab focus between general folder analytics and dedicated AI Project Lister."""
        self._is_ai_mode = is_ai
        if is_ai:
            # Move AI Program Lister tab to position 0 so it appears first when user selects it
            idx = self.tabs.indexOf(self.tab_ai)
            if idx != 0 and idx != -1:
                self.tabs.removeTab(idx)
                self.tabs.insertTab(0, self.tab_ai, "AI Program Lister")
            self.tabs.setCurrentIndex(0)

            # Switch profile to AI-Ready Project Scan
            ai_idx = self.combo_profile.findText("AI-Ready Project Scan")
            if ai_idx >= 0 and self.combo_profile.currentIndex() != ai_idx:
                self.combo_profile.setCurrentIndex(ai_idx)

            self.btn_start_scan.setText("SCAN FOR AI BUNDLE")
            self.btn_start_scan.setStyleSheet(
                "background: #10b981; color: #ffffff; border: none; "
                "padding: 6px 20px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
            )
        else:
            # Move AI Program Lister back to last tab if it's currently at index 0
            idx = self.tabs.indexOf(self.tab_ai)
            if idx == 0:
                self.tabs.removeTab(0)
                self.tabs.addTab(self.tab_ai, "AI Program Lister")
                self.tabs.setCurrentIndex(0)

            # If current profile was AI-Ready, switch back to Quick Scan
            if self.combo_profile.currentIndex() == 4:
                self.combo_profile.setCurrentIndex(0)

            self.btn_start_scan.setText("START SCAN")
            self.btn_start_scan.setStyleSheet(
                "background: #2563eb; color: #ffffff; border: none; "
                "padding: 6px 20px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
            )

    def _on_profile_changed(self, idx: int):
        profiles = [
            ScanProfile.QUICK,
            ScanProfile.FULL_METADATA,
            ScanProfile.INTEGRITY,
            ScanProfile.DUPLICATES,
            ScanProfile.AI_READY,
            ScanProfile.CUSTOM,
        ]
        if idx < len(profiles):
            prof = profiles[idx]
            self._apply_profile(prof)
            if prof == ScanProfile.AI_READY:
                # User selected AI-Ready scan: move AI Program Lister to tab 0 and focus it
                idx_ai = self.tabs.indexOf(self.tab_ai)
                if idx_ai != 0 and idx_ai != -1:
                    self.tabs.removeTab(idx_ai)
                    self.tabs.insertTab(0, self.tab_ai, "AI Program Lister")
                self.tabs.setCurrentIndex(0)
                self.btn_start_scan.setText("SCAN FOR AI BUNDLE")
                self.btn_start_scan.setStyleSheet(
                    "background: #10b981; color: #ffffff; border: none; "
                    "padding: 6px 20px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
                )
            elif not getattr(self, "_is_ai_mode", False):
                # Restore AI tab to the end if not in dedicated AI mode
                idx_ai = self.tabs.indexOf(self.tab_ai)
                if idx_ai == 0:
                    self.tabs.removeTab(0)
                    self.tabs.addTab(self.tab_ai, "AI Program Lister")
                    self.tabs.setCurrentIndex(0)
                self.btn_start_scan.setText("START SCAN")
                self.btn_start_scan.setStyleSheet(
                    "background: #2563eb; color: #ffffff; border: none; "
                    "padding: 6px 20px; border-radius: 4px; font-size: 11px; font-weight: 700; letter-spacing: 0.5px;"
                )

    def _apply_profile(self, profile: ScanProfile):
        cfg = ScanConfig.from_profile(profile)
        self.chk_ext.setChecked(cfg.include_extension)
        self.chk_size.setChecked(cfg.include_size)
        self.chk_modified.setChecked(cfg.include_modified)
        self.chk_created.setChecked(cfg.include_created)
        self.chk_accessed.setChecked(cfg.include_accessed)
        self.chk_hidden.setChecked(cfg.include_hidden)
        self.chk_perms.setChecked(cfg.include_permissions)
        self.chk_hash.setChecked(cfg.include_hash)
        self.chk_dupes.setChecked(cfg.detect_duplicates)
        self.chk_recursive.setChecked(cfg.recursive)

        if cfg.hash_algorithm == "sha1":
            self.combo_hash_algo.setCurrentText("SHA-1")
        elif cfg.hash_algorithm == "md5":
            self.combo_hash_algo.setCurrentText("MD5")
        else:
            self.combo_hash_algo.setCurrentText("SHA-256")

    def _build_config_from_ui(self) -> ScanConfig:
        workers_map = [0, 1, 2, 4, 8]
        w_idx = self.combo_workers.currentIndex()
        workers = workers_map[w_idx] if w_idx < len(workers_map) else 0

        algo = self.combo_hash_algo.currentText().lower().replace("-", "")

        return ScanConfig(
            include_name=True,
            include_extension=self.chk_ext.isChecked(),
            include_size=self.chk_size.isChecked(),
            include_created=self.chk_created.isChecked(),
            include_modified=self.chk_modified.isChecked(),
            include_accessed=self.chk_accessed.isChecked(),
            include_permissions=self.chk_perms.isChecked(),
            include_hidden=self.chk_hidden.isChecked(),
            include_hash=self.chk_hash.isChecked(),
            hash_algorithm=algo,
            detect_duplicates=self.chk_dupes.isChecked(),
            recursive=self.chk_recursive.isChecked(),
            hash_workers=workers,
        )

    def _start_scan(self):
        path = self.txt_folder_path.text().strip()
        if not path or not os.path.isdir(path):
            QMessageBox.warning(self, "Invalid Directory", "Please select a valid existing directory.")
            return

        config = self._build_config_from_ui()
        self.btn_start_scan.hide()
        self.btn_cancel_scan.show()
        self.prog_box.show()
        self.lbl_progress_phase.setText("TRAVERSING DIRECTORY TREE")
        self.lbl_progress_status.setText("Discovering entries...")

        # Switch tab focus
        if getattr(self, "_is_ai_mode", False) or self.combo_profile.currentIndex() == 4:
            self.tabs.setCurrentWidget(self.tab_ai)
        else:
            self.tabs.setCurrentWidget(self.tab_tree)
        self.tree_widget.clear()
        self._tree_folder_items.clear()

        # Update columns visibility
        self.tree_widget.setColumnHidden(3, not config.include_extension)
        self.tree_widget.setColumnHidden(4, not config.include_modified)
        self.tree_widget.setColumnHidden(5, not config.include_hash)

        self._scan_start_time = time.time()
        self._files_count_live = 0
        self._folders_count_live = 0
        self._live_timer.start()

        self.worker = FolderScanWorker(root_path=path, config=config, parent=self)
        self.worker.progress.connect(self._on_worker_progress)
        self.worker.folderDiscovered.connect(self._on_live_folder_discovered)
        self.worker.fileDiscovered.connect(self._on_live_file_discovered)
        self.worker.finished.connect(self._on_worker_finished)
        self.worker.failed.connect(self._on_worker_failed)
        self.worker.start()

    def _cancel_scan(self):
        if self.worker:
            self.lbl_progress_phase.setText("ABORTING SCAN")
            self.lbl_progress_status.setText("Stopping active workers...")
            self.worker.cancel()

    def _on_timer_tick(self):
        elapsed = time.time() - self._scan_start_time
        mins, secs = divmod(int(elapsed), 60)
        millis = int((elapsed - int(elapsed)) * 10)
        time_str = f"{mins:02d}:{secs:02d}.{millis:01d}"

        self.lbl_progress_counters.setText(
            f"{self._files_count_live:,} files | {self._folders_count_live:,} folders | {time_str}"
        )

    def _on_worker_progress(self, files_cnt: int, fld_cnt: int, msg: str):
        self._files_count_live = files_cnt
        self._folders_count_live = fld_cnt
        self._last_status_msg = msg

        # Truncate path cleanly if long
        display_msg = msg
        if len(display_msg) > 75:
            display_msg = display_msg[:35] + "..." + display_msg[-35:]

        self.lbl_progress_status.setText(display_msg)
        if "Hash" in msg or "hashing" in msg.lower():
            self.lbl_progress_phase.setText("PARALLEL STREAMING HASHING")
        elif "dupe" in msg.lower() or "duplicate" in msg.lower():
            self.lbl_progress_phase.setText("STAGED DUPLICATE FILTERING")

    def _on_live_folder_discovered(self, fld: FolderRecord):
        """Live progressive addition of discovered folders to the tree view."""
        item = QTreeWidgetItem()
        item.setText(0, fld.name)
        item.setText(1, "Folder")
        item.setText(2, "...")
        item.setForeground(0, QColor("#60a5fa"))
        item.setFont(0, QFont("Segoe UI", 9, QFont.Weight.Bold))

        self._tree_folder_items[fld.id] = item

        if fld.parent_id and fld.parent_id in self._tree_folder_items:
            self._tree_folder_items[fld.parent_id].addChild(item)
            if fld.depth <= 2:
                self._tree_folder_items[fld.parent_id].setExpanded(True)
        else:
            self.tree_widget.addTopLevelItem(item)
            item.setExpanded(True)

    def _on_live_file_discovered(self, f: FileRecord):
        """Live progressive addition of discovered files to the tree view."""
        self._files_count_live += 1
        item = QTreeWidgetItem()
        item.setText(0, f.name)
        item.setText(1, "File")
        item.setText(2, f.format_size() if f.size > 0 else "")
        item.setText(3, f.extension)
        item.setText(4, f.modified_datetime.strftime("%Y-%m-%d %H:%M") if f.modified_datetime else "")
        item.setText(5, "")

        if f.folder_id in self._tree_folder_items:
            self._tree_folder_items[f.folder_id].addChild(item)
        else:
            self.tree_widget.addTopLevelItem(item)

    def _on_worker_finished(self, result: ScanResult):
        self._live_timer.stop()
        self.current_result = result
        self.btn_cancel_scan.hide()
        self.btn_start_scan.show()
        self.prog_box.hide()

        self._populate_results(result)

    def _on_worker_failed(self, err_msg: str):
        self._live_timer.stop()
        self.btn_cancel_scan.hide()
        self.btn_start_scan.show()
        self.prog_box.hide()
        QMessageBox.critical(self, "Scan Failed", f"An error occurred during scanning:\n{err_msg}")

    def _populate_results(self, result: ScanResult):
        stats = result.stats
        if not stats:
            return

        # 1. Update summary cards
        self.lbl_stat_files.setText(f"{stats.total_files:,}")
        self.lbl_stat_folders.setText(f"{stats.total_folders:,}")
        self.lbl_stat_size.setText(stats.format_total_size())
        self.lbl_stat_wasted.setText(stats.format_wasted_size())
        self.lbl_stat_speed.setText(f"{stats.scan_speed_files_per_sec:.1f} items/s")
        self.lbl_stat_depth.setText(str(stats.max_depth))

        # 2. Populate extension table
        self.ext_table.setRowCount(len(stats.extension_distribution))
        for row, e in enumerate(stats.extension_distribution):
            self.ext_table.setItem(row, 0, QTableWidgetItem(e.extension))
            self.ext_table.setItem(row, 1, QTableWidgetItem(f"{e.file_count:,}"))
            self.ext_table.setItem(row, 2, QTableWidgetItem(e.to_dict()["total_size_formatted"]))
            self.ext_table.setItem(row, 3, QTableWidgetItem(f"{e.percentage_size:.1f}%"))

        # 3. Finalize directory tree with completed folder size rollups & hashes
        self.tree_widget.setUpdatesEnabled(False)
        try:
            self.tree_widget.clear()
            folder_items: Dict[int, QTreeWidgetItem] = {}

            for fld in result.folders:
                item = QTreeWidgetItem()
                item.setText(0, fld.name)
                item.setText(1, "Folder")
                item.setText(2, fld.format_size())
                item.setText(3, "")
                item.setText(4, "")
                item.setText(5, "")
                item.setForeground(0, QColor("#60a5fa"))
                item.setFont(0, QFont("Segoe UI", 9, QFont.Weight.Bold))
                item.setData(0, Qt.UserRole, fld.path)
                item.setData(0, Qt.UserRole + 1, fld.relative_path)
                folder_items[fld.id] = item

                if fld.parent_id and fld.parent_id in folder_items:
                    folder_items[fld.parent_id].addChild(item)
                else:
                    self.tree_widget.addTopLevelItem(item)

            for f in result.files:
                item = QTreeWidgetItem()
                item.setText(0, f.name)
                item.setText(1, "File")
                item.setText(2, f.format_size())
                item.setText(3, f.extension)
                item.setText(4, f.modified_datetime.strftime("%Y-%m-%d %H:%M") if f.modified_datetime else "")

                h_str = ""
                if f.hash_value:
                    h_str = f"{f.hash_value[:10]}...{f.hash_value[-6:]}"
                    item.setToolTip(5, f"Click or double-click to copy full SHA-256:\n{f.hash_value}")
                    item.setData(5, Qt.UserRole, f.hash_value)
                item.setText(5, h_str)

                item.setData(0, Qt.UserRole, f.path)
                item.setData(0, Qt.UserRole + 1, f.relative_path)

                if f.folder_id in folder_items:
                    folder_items[f.folder_id].addChild(item)
                else:
                    self.tree_widget.addTopLevelItem(item)

            # Expand top root node
            if result.folders and result.folders[0].id in folder_items:
                folder_items[result.folders[0].id].setExpanded(True)
        finally:
            self.tree_widget.setUpdatesEnabled(True)

        # 4. Populate duplicates tab
        self.dupes_tree.setUpdatesEnabled(False)
        try:
            self.dupes_tree.clear()
            if stats.duplicate_groups:
                self.lbl_dupes_summary.setText(
                    f"Found {len(stats.duplicate_groups)} duplicate groups wasting {stats.format_wasted_size()}:"
                )
                for idx, g in enumerate(stats.duplicate_groups, 1):
                    grp_item = QTreeWidgetItem()
                    grp_item.setText(0, f"Group #{idx} ({g.file_count} copies)")
                    grp_item.setText(1, format_bytes(g.size))
                    grp_item.setText(2, str(g.file_count))
                    grp_item.setText(3, format_bytes(g.wasted_bytes))
                    grp_item.setText(4, f"{g.hash_value[:12]}...")
                    grp_item.setToolTip(4, f"Click or double-click to copy full SHA-256:\n{g.hash_value}")
                    grp_item.setData(4, Qt.UserRole, g.hash_value)
                    grp_item.setForeground(3, QColor("#f43f5e"))
                    grp_item.setFont(0, QFont("Segoe UI", 9, QFont.Weight.Bold))

                    for f in g.files:
                        f_child = QTreeWidgetItem()
                        f_child.setText(0, f.relative_path)
                        f_child.setText(1, f.format_size())
                        f_child.setText(4, f.hash_value or g.hash_value)
                        f_child.setData(4, Qt.UserRole, f.hash_value or g.hash_value)
                        f_child.setData(0, Qt.UserRole, f.path)
                        grp_item.addChild(f_child)

                    self.dupes_tree.addTopLevelItem(grp_item)
                    grp_item.setExpanded(True)
            else:
                self.lbl_dupes_summary.setText("No duplicate files detected.")
        finally:
            self.dupes_tree.setUpdatesEnabled(True)

        # 5. Populate AI Program Lister
        self.tab_ai.set_scan_result(result)
        if getattr(self, "_is_ai_mode", False) or self.combo_profile.currentIndex() == 4:
            self.tabs.setCurrentWidget(self.tab_ai)

    def _on_search_text_changed(self, text: str):
        """Filter tree items based on search query with parent auto-expansion."""
        query = text.strip().lower()

        def _filter_item(item: QTreeWidgetItem) -> bool:
            name = item.text(0).lower()
            ext = item.text(3).lower()
            matches = (query in name) or (query in ext)

            child_matches = False
            for i in range(item.childCount()):
                if _filter_item(item.child(i)):
                    child_matches = True

            visible = matches or child_matches
            item.setHidden(not visible)
            if visible and query:
                item.setExpanded(True)
            return visible

        for i in range(self.tree_widget.topLevelItemCount()):
            _filter_item(self.tree_widget.topLevelItem(i))

    def _on_tree_item_clicked(self, item: QTreeWidgetItem, column: int):
        """Single click on hash column copies full uncut hash."""
        if column == 5:
            h = item.data(5, Qt.UserRole)
            if h:
                QApplication.clipboard().setText(h)
                self.lbl_progress_status.setText(f"Copied SHA-256 to clipboard: {h[:16]}...")

    def _on_tree_item_double_clicked(self, item: QTreeWidgetItem, column: int):
        """Double click copies full uncut hash or path to clipboard."""
        h = item.data(5, Qt.UserRole)
        if h:
            QApplication.clipboard().setText(h)
            self.lbl_progress_status.setText(f"Copied full SHA-256 to clipboard: {h}")
        else:
            path = item.data(0, Qt.UserRole) or item.text(0)
            if path:
                QApplication.clipboard().setText(path)
                self.lbl_progress_status.setText(f"Copied path to clipboard: {path}")

    def _on_tree_context_menu(self, pos):
        """Context menu for Directory Explorer tree."""
        item = self.tree_widget.itemAt(pos)
        if not item:
            return

        menu = QMenu(self)
        h = item.data(5, Qt.UserRole)
        path = item.data(0, Qt.UserRole)
        rel_path = item.data(0, Qt.UserRole + 1)
        name = item.text(0)

        if h:
            act_copy_hash = menu.addAction("Copy Full SHA-256 Hash")
            act_copy_hash.triggered.connect(lambda: QApplication.clipboard().setText(h))

        if name:
            act_copy_name = menu.addAction("Copy Name")
            act_copy_name.triggered.connect(lambda: QApplication.clipboard().setText(name))

        if rel_path:
            act_copy_rel = menu.addAction("Copy Relative Path")
            act_copy_rel.triggered.connect(lambda: QApplication.clipboard().setText(rel_path))

        if path:
            act_copy_path = menu.addAction("Copy Absolute Path")
            act_copy_path.triggered.connect(lambda: QApplication.clipboard().setText(path))

        if path and os.path.exists(path):
            act_open = menu.addAction("Open Containing Folder")
            act_open.triggered.connect(lambda: self._open_in_file_manager(path))

        menu.exec(self.tree_widget.viewport().mapToGlobal(pos))

    def _on_dupes_item_double_clicked(self, item: QTreeWidgetItem, column: int):
        """Double click in duplicates tree copies full hash."""
        h = item.data(4, Qt.UserRole) or item.text(4)
        if h:
            QApplication.clipboard().setText(h)
            self.lbl_progress_status.setText(f"Copied duplicate SHA-256 to clipboard: {h}")

    def _on_dupes_context_menu(self, pos):
        """Context menu for Duplicates tree."""
        item = self.dupes_tree.itemAt(pos)
        if not item:
            return

        menu = QMenu(self)
        h = item.data(4, Qt.UserRole)
        path = item.data(0, Qt.UserRole)

        if h:
            act_copy_hash = menu.addAction("Copy Full SHA-256 Hash")
            act_copy_hash.triggered.connect(lambda: QApplication.clipboard().setText(h))

        if path:
            act_copy_path = menu.addAction("Copy Path")
            act_copy_path.triggered.connect(lambda: QApplication.clipboard().setText(path))

        menu.exec(self.dupes_tree.viewport().mapToGlobal(pos))

    def _open_in_file_manager(self, target_path: str):
        """Open operating system file manager at specified path."""
        import subprocess
        import sys
        try:
            if sys.platform == "win32":
                if os.path.isfile(target_path):
                    subprocess.run(["explorer", f"/select,{os.path.normpath(target_path)}"])
                else:
                    os.startfile(target_path)
            elif sys.platform == "darwin":
                if os.path.isfile(target_path):
                    subprocess.run(["open", "-R", target_path])
                else:
                    subprocess.run(["open", target_path])
            else:
                target_dir = os.path.dirname(target_path) if os.path.isfile(target_path) else target_path
                subprocess.run(["xdg-open", target_dir])
        except Exception:
            pass

    def _on_export_clicked(self):
        if not self.current_result:
            QMessageBox.information(self, "No Results", "Please run a scan before exporting.")
            return

        filter_str = (
            "Interactive HTML Report (*.html);;"
            "Markdown Summary (*.md);;"
            "JSON Data (*.json);;"
            "CSV Spreadsheet (*.csv);;"
            "Plain Text (*.txt)"
        )
        default_name = FolderExporter.generate_default_filename(self.current_result.root_path, "html")
        dest, sel_filter = QFileDialog.getSaveFileName(self, "Export Analysis Report", default_name, filter_str)
        if dest:
            try:
                exporter = FolderExporter(self.current_result)
                out_path = exporter.export(dest)
                QMessageBox.information(self, "Export Complete", f"Report saved successfully to:\n{out_path}")
            except Exception as e:
                QMessageBox.critical(self, "Export Error", f"Failed to export report:\n{e}")
