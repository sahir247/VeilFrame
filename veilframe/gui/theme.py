"""
VeilFrame GUI — Design System & QSS Theme
==========================================

Professional charcoal dark palette. No gradients on text, no emoji,
no gaming aesthetics. Designed for daily use by a practitioner.

Palette
-------
  BG-0:      #141414   deepest background (window chrome)
  BG-1:      #1c1c1c   main surface
  BG-2:      #242424   cards / group-box fill
  BG-3:      #2c2c2c   inputs, inner panels
  Border-lo: #333333   default border
  Border-hi: #4a4a4a   hover / focused border
  Accent:    #3570e6   primary action (clear blue, not neon)
  Accent-hv: #4880f5   hover
  Text-hi:   #e2e2e2   primary text
  Text-md:   #909090   secondary / labels
  Text-lo:   #555555   disabled / muted
  Green:     #2ea04f   PASS
  Red:       #d84040   REJECT / error
  Amber:     #c97f1a   warning
"""

DARK_THEME_QSS = """

/* ── Global ─────────────────────────────────────────────────────── */
QMainWindow, QDialog {
    background-color: #141414;
    color: #e2e2e2;
}

QWidget {
    background-color: transparent;
    color: #e2e2e2;
    font-family: "Segoe UI", "SF Pro Text", Roboto, Helvetica, Arial, sans-serif;
    font-size: 12px;
}

QWidget#root {
    background-color: #1c1c1c;
}

/* ── Group boxes (used as section cards) ─────────────────────────── */
QGroupBox {
    background-color: #222222;
    border: 1px solid #333333;
    border-radius: 6px;
    margin-top: 20px;
    padding: 14px 12px 12px 12px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1.5px;
    color: #666666;
}

QGroupBox::title {
    subcontrol-origin: margin;
    subcontrol-position: top left;
    left: 12px;
    top: 3px;
    padding: 0 6px;
    background-color: #222222;
    color: #606060;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1.5px;
}

/* ── Frames ──────────────────────────────────────────────────────── */
QFrame#card {
    background-color: #222222;
    border: 1px solid #333333;
    border-radius: 6px;
}

QFrame#innerCard {
    background-color: #2a2a2a;
    border: 1px solid #333333;
    border-radius: 4px;
}

QFrame#hline {
    background-color: #2e2e2e;
    max-height: 1px;
    border: none;
}

/* ── Tabs ────────────────────────────────────────────────────────── */
QTabWidget::pane {
    border: 1px solid #333333;
    border-top: 2px solid #333333;
    background-color: #222222;
    border-radius: 0 0 6px 6px;
    padding: 12px;
}

QTabBar {
    background: transparent;
}

QTabBar::tab {
    background: transparent;
    color: #606060;
    border: none;
    border-bottom: 2px solid transparent;
    padding: 8px 20px;
    margin-right: 2px;
    font-size: 11px;
    font-weight: 600;
}

QTabBar::tab:hover {
    color: #a0a0a0;
}

QTabBar::tab:selected {
    color: #e2e2e2;
    border-bottom: 2px solid #3570e6;
    font-weight: 700;
}

/* ── Buttons ─────────────────────────────────────────────────────── */
QPushButton {
    background-color: #2a2a2a;
    color: #c0c0c0;
    border: 1px solid #404040;
    border-radius: 4px;
    padding: 6px 14px;
    font-weight: 500;
    font-size: 12px;
    min-height: 26px;
}

QPushButton:hover {
    background-color: #333333;
    color: #e2e2e2;
    border-color: #555555;
}

QPushButton:pressed {
    background-color: #222222;
}

QPushButton:disabled {
    background-color: #1e1e1e;
    color: #404040;
    border-color: #2a2a2a;
}

QPushButton#primaryAction {
    background-color: #1f55d0;
    color: #ffffff;
    border: none;
    border-radius: 4px;
    padding: 9px 28px;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.5px;
    min-width: 160px;
    min-height: 34px;
}

QPushButton#primaryAction:hover {
    background-color: #2a63e0;
}

QPushButton#primaryAction:pressed {
    background-color: #1748b8;
}

QPushButton#primaryAction:disabled {
    background-color: #1e2a40;
    color: #3d5080;
    border: none;
}

QPushButton#cancelAction {
    background-color: transparent;
    color: #909090;
    border: 1px solid #404040;
    border-radius: 4px;
    padding: 7px 18px;
    font-size: 12px;
    min-height: 32px;
}

QPushButton#cancelAction:hover {
    background-color: #2a2a2a;
    color: #c0c0c0;
    border-color: #606060;
}

QPushButton#iconBtn {
    background: transparent;
    border: none;
    color: #3570e6;
    font-size: 12px;
    padding: 2px 6px;
}

QPushButton#iconBtn:hover {
    color: #4880f5;
}

/* ── Inputs ──────────────────────────────────────────────────────── */
QLineEdit, QSpinBox, QDoubleSpinBox, QComboBox {
    background-color: #2a2a2a;
    color: #e2e2e2;
    border: 1px solid #3a3a3a;
    border-radius: 4px;
    padding: 5px 9px;
    selection-background-color: #1f55d0;
    font-size: 12px;
    min-height: 24px;
}

QLineEdit:focus, QSpinBox:focus, QDoubleSpinBox:focus, QComboBox:focus {
    border: 1px solid #4a4a4a;
    background-color: #2e2e2e;
    outline: none;
}

QLineEdit:disabled, QSpinBox:disabled, QDoubleSpinBox:disabled, QComboBox:disabled {
    background-color: #222222;
    color: #484848;
    border-color: #2e2e2e;
}

QComboBox::drop-down {
    width: 20px;
    border: none;
}

QComboBox QAbstractItemView {
    background-color: #2a2a2a;
    color: #e2e2e2;
    border: 1px solid #404040;
    selection-background-color: #1f55d0;
    outline: none;
}

/* ── Sliders ─────────────────────────────────────────────────────── */
QSlider::groove:horizontal {
    height: 4px;
    background: #333333;
    border-radius: 2px;
}

QSlider::sub-page:horizontal {
    background: #3570e6;
    border-radius: 2px;
}

QSlider::handle:horizontal {
    background: #e2e2e2;
    border: 2px solid #3570e6;
    width: 14px;
    margin-top: -5px;
    margin-bottom: -5px;
    border-radius: 7px;
}

QSlider::handle:horizontal:hover {
    background: #4880f5;
    border-color: #6090ff;
}

/* ── Checkboxes / Radio ──────────────────────────────────────────── */
QCheckBox, QRadioButton {
    color: #c0c0c0;
    spacing: 7px;
    font-size: 12px;
}

QCheckBox::indicator, QRadioButton::indicator {
    width: 15px;
    height: 15px;
    border-radius: 3px;
    border: 1px solid #484848;
    background: #2a2a2a;
}

QRadioButton::indicator { border-radius: 8px; }

QCheckBox::indicator:checked, QRadioButton::indicator:checked {
    background-color: #1f55d0;
    border-color: #3570e6;
}

QCheckBox::indicator:hover, QRadioButton::indicator:hover {
    border-color: #606060;
}

QCheckBox::indicator:disabled, QRadioButton::indicator:disabled {
    background-color: #1e1e1e;
    border-color: #2e2e2e;
}

/* ── Progress bar ────────────────────────────────────────────────── */
QProgressBar {
    background-color: #2a2a2a;
    border: none;
    border-radius: 3px;
    text-align: center;
    color: #909090;
    font-weight: 600;
    font-size: 10px;
    height: 6px;
}

QProgressBar::chunk {
    background-color: #3570e6;
    border-radius: 3px;
}

/* ── Text areas / log ────────────────────────────────────────────── */
QTextEdit, QPlainTextEdit {
    background-color: #181818;
    color: #a0a0a0;
    border: 1px solid #2e2e2e;
    border-radius: 4px;
    font-family: "Cascadia Code", "Consolas", "Courier New", monospace;
    font-size: 11px;
    padding: 8px;
    selection-background-color: #1f3a6e;
}

/* ── Scrollbars ──────────────────────────────────────────────────── */
QScrollBar:vertical {
    border: none;
    background: transparent;
    width: 7px;
}

QScrollBar::handle:vertical {
    background: #3a3a3a;
    border-radius: 3px;
    min-height: 28px;
}

QScrollBar::handle:vertical:hover { background: #4a4a4a; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }

QScrollBar:horizontal {
    border: none;
    background: transparent;
    height: 7px;
}

QScrollBar::handle:horizontal {
    background: #3a3a3a;
    border-radius: 3px;
    min-width: 28px;
}

QScrollBar::handle:horizontal:hover { background: #4a4a4a; }
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal { width: 0; }

/* ── Labels ──────────────────────────────────────────────────────── */
QLabel#sectionTitle {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1.5px;
    color: #606060;
}

QLabel#metaLabel {
    color: #606060;
    font-size: 11px;
}

QLabel#metaValue {
    color: #d0d0d0;
    font-size: 12px;
    font-weight: 500;
}

/* ── Verdict badges ──────────────────────────────────────────────── */
QLabel#badgePass {
    background-color: #162a1e;
    color: #3fb768;
    border: 1px solid #1e4a2e;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
}

QLabel#badgeFail {
    background-color: #2e1414;
    color: #d84040;
    border: 1px solid #4a1818;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
}

QLabel#badgeWarn {
    background-color: #2a1f0e;
    color: #c97f1a;
    border: 1px solid #4a3010;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
}

QLabel#badgeSkip {
    background-color: #202020;
    color: #555555;
    border: 1px solid #303030;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 10px;
    font-weight: 600;
}

QLabel#badgeInfo {
    background-color: #172040;
    color: #4a80e0;
    border: 1px solid #1e2e58;
    border-radius: 3px;
    padding: 2px 8px;
    font-size: 10px;
    font-weight: 600;
}

/* ── Tooltip ─────────────────────────────────────────────────────── */
QToolTip {
    background-color: #2a2a2a;
    color: #e2e2e2;
    border: 1px solid #404040;
    border-radius: 4px;
    padding: 4px 8px;
    font-size: 11px;
}

"""


def badge_style(variant: str) -> str:
    """Inline stylesheet for a verdict badge QLabel.

    Variants: 'pass', 'fail', 'warn', 'skip', 'info'
    Returns a valid Qt stylesheet string.
    """
    configs = {
        "pass": ("#162a1e", "#3fb768", "#1e4a2e"),
        "fail": ("#2e1414", "#d84040", "#4a1818"),
        "warn": ("#2a1f0e", "#c97f1a", "#4a3010"),
        "skip": ("#202020", "#555555", "#303030"),
        "info": ("#172040", "#4a80e0", "#1e2e58"),
    }
    bg, fg, bd = configs.get(variant, configs["info"])
    return (
        f"background-color: {bg}; color: {fg}; border: 1px solid {bd};"
        f" border-radius: 3px; padding: 2px 8px;"
        f" font-size: 10px; font-weight: 700; letter-spacing: 1px;"
    )


# Status dot helpers (replace emoji status indicators)
DOT_OK   = "\u25cf"   # filled circle  ●
DOT_MISS = "\u25cb"   # open circle    ○
DOT_WARN = "\u25cf"   # same circle, styled via QSS color
