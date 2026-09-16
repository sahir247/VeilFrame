"""
pytest session configuration and persistent QApplication lifecycle management.
Ensures headless offscreen execution and prevents Qt process aborts across test runs.
"""
import os
import sys

# Force headless offscreen platform for Qt across all test runners
os.environ["QT_QPA_PLATFORM"] = "offscreen"

import pytest

_SESSION_QAPP = None


def get_or_create_test_qapp():
    """Return the persistent QApplication instance, initializing if necessary."""
    global _SESSION_QAPP
    try:
        from PySide6.QtWidgets import QApplication
        _SESSION_QAPP = QApplication.instance()
        if _SESSION_QAPP is None:
            _SESSION_QAPP = QApplication(["veilframe-test-suite", "-platform", "offscreen"])
        return _SESSION_QAPP
    except Exception:
        return None


@pytest.fixture(scope="session", autouse=True)
def qapp_session():
    """
    Session-wide QApplication instance held alive for the entire pytest run.
    Guarantees stable QApplication lifetime across all test classes and modules.
    """
    app = get_or_create_test_qapp()
    yield app
