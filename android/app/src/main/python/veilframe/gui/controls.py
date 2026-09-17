"""
VeilFrame GUI Custom Controls & Event Filters.
=============================================

UX Behaviors enforced:
1. Dropdowns (QComboBox) are controlled via mouse click or keyboard arrow keys, NOT mouse wheel scrolling.
2. Numerical inputs (QSpinBox, QDoubleSpinBox, QSlider) ignore mouse wheel scrolling unless explicitly focused / tapped.
3. Modular section reset buttons for one-click restoration of section defaults.
"""
from typing import Callable, Optional
from PySide6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QComboBox,
    QSpinBox,
    QDoubleSpinBox,
    QSlider,
    QPushButton,
    QAbstractSpinBox,
)
from PySide6.QtCore import Qt, QObject, QEvent
from PySide6.QtGui import QWheelEvent


class NoWheelComboBox(QComboBox):
    """
    QComboBox that ignores mouse wheel events to prevent accidental item changes
    while scrolling through panels. Item selection is performed via mouse click or keyboard arrows.
    """
    def __init__(self, parent: Optional[QObject] = None):
        super().__init__(parent)
        self.setFocusPolicy(Qt.StrongFocus)

    def wheelEvent(self, event: QWheelEvent):
        # Ignore mouse wheel over the closed combobox so parent container scrolls instead
        event.ignore()


class FocusWheelSpinBox(QSpinBox):
    """
    QSpinBox that only responds to mouse wheel scrolling when actively focused / tapped.
    """
    def __init__(self, parent: Optional[QObject] = None):
        super().__init__(parent)
        self.setFocusPolicy(Qt.StrongFocus)

    def wheelEvent(self, event: QWheelEvent):
        if not self.hasFocus():
            event.ignore()
        else:
            super().wheelEvent(event)


class FocusWheelDoubleSpinBox(QDoubleSpinBox):
    """
    QDoubleSpinBox that only responds to mouse wheel scrolling when actively focused / tapped.
    """
    def __init__(self, parent: Optional[QObject] = None):
        super().__init__(parent)
        self.setFocusPolicy(Qt.StrongFocus)

    def wheelEvent(self, event: QWheelEvent):
        if not self.hasFocus():
            event.ignore()
        else:
            super().wheelEvent(event)


class FocusWheelSlider(QSlider):
    """
    QSlider that only responds to mouse wheel scrolling when actively focused / tapped.
    """
    def __init__(self, orientation: Qt.Orientation = Qt.Horizontal, parent: Optional[QObject] = None):
        super().__init__(orientation, parent)
        self.setFocusPolicy(Qt.StrongFocus)

    def wheelEvent(self, event: QWheelEvent):
        if not self.hasFocus():
            event.ignore()
        else:
            super().wheelEvent(event)


class UXWheelEventFilter(QObject):
    """
    Global application event filter ensuring:
    - Any QComboBox ignores mouse wheel scrolling.
    - Any QAbstractSpinBox or QSlider ignores mouse wheel scrolling unless focused.
    """
    def eventFilter(self, obj: QObject, event: QEvent) -> bool:
        if event.type() == QEvent.Wheel:
            # 1. ComboBox: never change selection on wheel scroll
            if isinstance(obj, QComboBox):
                event.ignore()
                return True

            # 2. Spinboxes and Sliders: only accept wheel when focused
            elif isinstance(obj, (QAbstractSpinBox, QSlider)):
                if not obj.hasFocus():
                    event.ignore()
                    return True

        return super().eventFilter(obj, event)


def create_section_reset_button(
    on_reset: Callable[[], None],
    tooltip: str = "Reset this section to default values",
) -> QPushButton:
    """
    Creates a compact, styled section reset button (↺ Reset).
    """
    btn = QPushButton("↺ Reset")
    btn.setObjectName("sectionResetBtn")
    btn.setToolTip(tooltip)
    btn.setCursor(Qt.PointingHandCursor)
    btn.clicked.connect(on_reset)
    return btn


class CollapsibleSection(QWidget):
    """
    A sleek collapsible section container with a toggle chevron arrow header (▼ / ▶).
    Keeps complex/manual configuration tucked away until requested by the user.
    """
    def __init__(
        self,
        title: str = "ADVANCED SETTINGS",
        parent: Optional[QWidget] = None,
        initially_expanded: bool = False,
    ):
        super().__init__(parent)
        self._is_expanded = initially_expanded
        self._title = title

        main_lay = QVBoxLayout(self)
        main_lay.setContentsMargins(0, 4, 0, 4)
        main_lay.setSpacing(0)

        # Header Toggle Button
        self.toggle_btn = QPushButton()
        self.toggle_btn.setCheckable(True)
        self.toggle_btn.setChecked(self._is_expanded)
        self.toggle_btn.setCursor(Qt.PointingHandCursor)
        self.toggle_btn.setStyleSheet("""
            QPushButton {
                background-color: #202020;
                border: 1px solid #363636;
                border-radius: 4px;
                text-align: left;
                padding: 8px 12px;
                font-size: 11px;
                font-weight: 700;
                color: #b8b8b8;
                letter-spacing: 1px;
            }
            QPushButton:hover {
                background-color: #282828;
                color: #ffffff;
                border-color: #4a4a4a;
            }
            QPushButton:checked {
                border-bottom-left-radius: 0px;
                border-bottom-right-radius: 0px;
                border-bottom: 1px solid #282828;
                background-color: #242424;
            }
        """)
        self._update_header_text()
        self.toggle_btn.clicked.connect(self._on_toggle_clicked)
        main_lay.addWidget(self.toggle_btn)

        # Content Widget
        self.content_widget = QWidget()
        self.content_widget.setObjectName("collapsibleContent")
        self.content_widget.setStyleSheet("""
            QWidget#collapsibleContent {
                background-color: #1a1a1a;
                border: 1px solid #363636;
                border-top: none;
                border-bottom-left-radius: 4px;
                border-bottom-right-radius: 4px;
            }
        """)
        self.content_layout = QVBoxLayout(self.content_widget)
        self.content_layout.setContentsMargins(14, 12, 14, 14)
        self.content_layout.setSpacing(10)
        self.content_widget.setVisible(self._is_expanded)
        main_lay.addWidget(self.content_widget)

    def _update_header_text(self):
        arrow = "▼" if self._is_expanded else "▶"
        self.toggle_btn.setText(f" {arrow}  {self._title}")

    def _on_toggle_clicked(self):
        self._is_expanded = self.toggle_btn.isChecked()
        self.content_widget.setVisible(self._is_expanded)
        self._update_header_text()

    def add_widget(self, widget: QWidget):
        self.content_layout.addWidget(widget)

    def add_layout(self, layout):
        self.content_layout.addLayout(layout)

    def set_expanded(self, expanded: bool):
        self._is_expanded = expanded
        self.toggle_btn.setChecked(expanded)
        self.content_widget.setVisible(expanded)
        self._update_header_text()

    def is_expanded(self) -> bool:
        return self._is_expanded


