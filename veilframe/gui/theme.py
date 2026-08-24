"""
Modern dark styling and CSS theme definitions for PySide6 GUI.
"""

DARK_THEME_QSS = """
/* Global Window Styling */
QMainWindow, QDialog, QWidget#root {
    background-color: #0f172a;
    color: #f1f5f9;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 13px;
}

/* Card / Group Box Container */
QGroupBox {
    background-color: #1e293b;
    border: 1px solid #334155;
    border-radius: 8px;
    margin-top: 24px;
    padding: 16px 12px 12px 12px;
    font-weight: 600;
    font-size: 13px;
    color: #38bdf8;
}

QGroupBox::title {
    subcontrol-origin: margin;
    subcontrol-position: top left;
    left: 12px;
    top: 4px;
    padding: 0 6px;
    background-color: #0f172a;
    border-radius: 4px;
}

/* Push Buttons */
QPushButton {
    background-color: #334155;
    color: #f8fafc;
    border: 1px solid #475569;
    border-radius: 6px;
    padding: 7px 14px;
    font-weight: 500;
}

QPushButton:hover {
    background-color: #475569;
    border-color: #64748b;
}

QPushButton:pressed {
    background-color: #1e293b;
}

QPushButton:disabled {
    background-color: #1e293b;
    color: #64748b;
    border-color: #334155;
}

/* Primary Action Button */
QPushButton#primaryAction {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #0284c7, stop:1 #0369a1);
    color: #ffffff;
    border: 1px solid #38bdf8;
    border-radius: 6px;
    padding: 10px 20px;
    font-size: 14px;
    font-weight: 700;
    letter-spacing: 0.5px;
}

QPushButton#primaryAction:hover {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #0ea5e9, stop:1 #0284c7);
}

QPushButton#primaryAction:disabled {
    background: #1e293b;
    color: #475569;
    border-color: #334155;
}

/* Inputs & Spin Boxes */
QLineEdit, QSpinBox, QDoubleSpinBox, QComboBox {
    background-color: #0f172a;
    color: #f8fafc;
    border: 1px solid #334155;
    border-radius: 6px;
    padding: 5px 8px;
    selection-background-color: #0284c7;
}

QLineEdit:focus, QSpinBox:focus, QDoubleSpinBox:focus, QComboBox:focus {
    border: 1px solid #38bdf8;
}

QLineEdit:disabled, QSpinBox:disabled, QDoubleSpinBox:disabled, QComboBox:disabled {
    background-color: #1e293b;
    color: #64748b;
    border-color: #334155;
}

QComboBox::drop-down {
    subcontrol-origin: padding;
    subcontrol-position: top right;
    width: 20px;
    border-left-width: 0px;
}

QComboBox QAbstractItemView {
    background-color: #1e293b;
    color: #f8fafc;
    border: 1px solid #475569;
    selection-background-color: #0284c7;
}

/* Sliders */
QSlider::groove:horizontal {
    height: 6px;
    background: #334155;
    border-radius: 3px;
}

QSlider::sub-page:horizontal {
    background: #0284c7;
    border-radius: 3px;
}

QSlider::handle:horizontal {
    background: #38bdf8;
    border: 1px solid #0284c7;
    width: 16px;
    margin-top: -5px;
    margin-bottom: -5px;
    border-radius: 8px;
}

QSlider::handle:horizontal:hover {
    background: #7dd3fc;
}

/* Checkboxes & Radio Buttons */
QCheckBox, QRadioButton {
    color: #e2e8f0;
    spacing: 6px;
    font-size: 13px;
}

QCheckBox::indicator, QRadioButton::indicator {
    width: 16px;
    height: 16px;
    border-radius: 4px;
    border: 1px solid #475569;
    background: #0f172a;
}

QRadioButton::indicator {
    border-radius: 8px;
}

QCheckBox::indicator:checked, QRadioButton::indicator:checked {
    background-color: #0284c7;
    border-color: #38bdf8;
}

QCheckBox::indicator:disabled, QRadioButton::indicator:disabled {
    background-color: #1e293b;
    border-color: #334155;
}

/* Progress Bar */
QProgressBar {
    background-color: #1e293b;
    border: 1px solid #334155;
    border-radius: 6px;
    text-align: center;
    color: #f8fafc;
    font-weight: 600;
    height: 18px;
}

QProgressBar::chunk {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #0284c7, stop:1 #38bdf8);
    border-radius: 5px;
}

/* Scrollbars & TextEdit */
QTextEdit, QPlainTextEdit {
    background-color: #090d16;
    color: #94a3b8;
    border: 1px solid #334155;
    border-radius: 6px;
    font-family: "Cascadia Code", "Consolas", "Courier New", monospace;
    font-size: 12px;
    padding: 8px;
}

QScrollBar:vertical {
    border: none;
    background: #0f172a;
    width: 8px;
    border-radius: 4px;
}

QScrollBar::handle:vertical {
    background: #334155;
    border-radius: 4px;
    min-height: 20px;
}

QScrollBar::handle:vertical:hover {
    background: #475569;
}

/* Tabs */
QTabWidget::pane {
    border: 1px solid #334155;
    background-color: #1e293b;
    border-radius: 8px;
    padding: 10px;
}

QTabBar::tab {
    background: #0f172a;
    color: #94a3b8;
    border: 1px solid #334155;
    border-bottom: none;
    border-top-left-radius: 6px;
    border-top-right-radius: 6px;
    padding: 8px 16px;
    margin-right: 4px;
    font-weight: 500;
}

QTabBar::tab:selected {
    background: #1e293b;
    color: #38bdf8;
    font-weight: 600;
}
"""
