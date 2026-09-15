"""
VeilFrame — Package execution entry point (`python -m veilframe`).
"""
from __future__ import annotations

import sys
from veilframe.cli import main

if __name__ == "__main__":
    sys.exit(main())
