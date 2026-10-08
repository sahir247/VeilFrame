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


def cleanup_all_qwidgets():
    """Explicitly close and delete all top-level QWidgets and drain the Qt event queue."""
    try:
        from PySide6.QtWidgets import QApplication
        app = QApplication.instance()
        if app is not None:
            for widget in list(QApplication.topLevelWidgets()):
                try:
                    widget.close()
                    widget.deleteLater()
                except Exception:
                    pass
            app.processEvents()
            app.sendPostedEvents()
    except Exception:
        pass


def pytest_configure(config):
    """Ensure headless offscreen platform and initialize QApplication early."""
    os.environ["QT_QPA_PLATFORM"] = "offscreen"
    get_or_create_test_qapp()


@pytest.fixture(scope="session", autouse=True)
def qapp_session():
    """
    Session-wide QApplication instance held alive for the entire pytest run.
    Guarantees stable QApplication lifetime across all test classes and modules.
    Properly cleans up all Qt objects before exiting to prevent segmentation faults.
    """
    app = get_or_create_test_qapp()
    yield app
    cleanup_all_qwidgets()
    if app is not None:
        try:
            app.processEvents()
            app.sendPostedEvents()
        except Exception:
            pass


def pytest_sessionfinish(session, exitstatus):
    """Properly clean up Qt resources after all tests complete."""
    cleanup_all_qwidgets()
    try:
        from PySide6.QtWidgets import QApplication
        app = QApplication.instance()
        if app is not None:
            app.processEvents()
            app.sendPostedEvents()
            app.quit()
    except Exception:
        pass


def pytest_unconfigure(config):
    """Ensure complete drainage and prevent Shiboken pure virtual function call crash."""
    cleanup_all_qwidgets()
    global _SESSION_QAPP
    _SESSION_QAPP = None
