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
            _console_handles = []
            try:
                import ctypes
                import msvcrt
                import atexit

                # 1. Try connecting to inherited piped handles (e.g. when stdout is redirected)
                if sys.stdout is None:
                    h_out = ctypes.windll.kernel32.GetStdHandle(-11)  # STD_OUTPUT_HANDLE
                    if h_out and h_out != -1 and h_out != 0:
                        try:
                            fd = msvcrt.open_osfhandle(h_out, os.O_WRONLY)
                            sys.stdout = open(fd, "w", encoding="utf-8", errors="replace", closefd=False)
                        except Exception:
                            pass

                if sys.stderr is None:
                    h_err = ctypes.windll.kernel32.GetStdHandle(-12)  # STD_ERROR_HANDLE
                    if h_err and h_err != -1 and h_err != 0:
                        try:
                            fd = msvcrt.open_osfhandle(h_err, os.O_WRONLY)
                            sys.stderr = open(fd, "w", encoding="utf-8", errors="replace", closefd=False)
                        except Exception:
                            pass

                # 2. If still unattached, attach to parent terminal console
                if sys.stdout is None or sys.stdout.closed:
                    if ctypes.windll.kernel32.AttachConsole(-1):
                        _stdout = open("CONOUT$", "w", encoding="utf-8", errors="replace")
                        _stderr = open("CONOUT$", "w", encoding="utf-8", errors="replace")
                        _stdin = open("CONIN$", "r", encoding="utf-8", errors="replace")
                        _console_handles = [_stdout, _stderr, _stdin]
                        sys.stdout = _stdout
                        sys.stderr = _stderr
                        sys.stdin = _stdin

                        def _close_console_handles():
                            for h in _console_handles:
                                try:
                                    h.close()
                                except Exception:
                                    pass

                        atexit.register(_close_console_handles)
            except Exception:
                pass

        # Safe fallback if stdout/stderr are still None
        import io
        if sys.stdout is None:
            sys.stdout = io.StringIO()
        if sys.stderr is None:
            sys.stderr = io.StringIO()

        from veilframe.cli import main as cli_main
        cli_main()
    else:
        from veilframe.app import main as gui_main
        gui_main()


if __name__ == "__main__":
    launcher()
