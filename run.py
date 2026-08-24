"""
Launcher entry point for Video Privacy Cleaner.
"""
import os
import sys

# Ensure UTF-8 decoding across all subprocess and thread pipes on Windows
os.environ["PYTHONIOENCODING"] = "utf-8"
os.environ["PYTHONUTF8"] = "1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from privacy_cleaner.app import main

if __name__ == "__main__":
    main()
