"""
veilframe.folder.scanner.cancellation — Thread-safe cooperative cancellation token.
"""

from __future__ import annotations

import threading


class CancellationToken:
    """Thread-safe cancellation coordinator across scanner, hasher, and bundle builder."""

    def __init__(self) -> None:
        self._event = threading.Event()

    def cancel(self) -> None:
        """Signal cancellation."""
        self._event.set()

    @property
    def is_cancelled(self) -> bool:
        return self._event.is_set()

    def check(self) -> None:
        """Raise InterruptedError if cancellation was requested."""
        if self._event.is_set():
            raise InterruptedError("Operation was cancelled by user.")
