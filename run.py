"""
VeilFrame — GUI & Launcher Entry Point.
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

def launcher():
    # If explicit CLI arguments provided (and not just "gui")
    if len(sys.argv) > 1 and sys.argv[1].lower() != "gui":
        if sys.platform == "win32":
            try:
                import ctypes
                # Attach to parent console if running from cmd or powershell
                if ctypes.windll.kernel32.AttachConsole(-1):
                    sys.stdout = open("CONOUT$", "w", encoding="utf-8", errors="replace")
                    sys.stderr = open("CONOUT$", "w", encoding="utf-8", errors="replace")
                    sys.stdin = open("CONIN$", "r", encoding="utf-8", errors="replace")
            except Exception:
                pass
        from veilframe.cli import main as cli_main
        cli_main()
    else:
        from veilframe.app import main as gui_main
        gui_main()


if __name__ == "__main__":
    launcher()
