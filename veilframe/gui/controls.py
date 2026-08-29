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
