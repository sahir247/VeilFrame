"""
VeilFrame — Modern Terminal UI & Formatting Engine.

Provides polished, zero-dependency ANSI styling, box-drawing cards,
aligned comparison tables, live progress rendering, and interactive prompts
inspired by modern developer CLIs (Claude Code, OpenClaw).
"""
import sys
import os
import shutil
import time
from typing import List, Tuple, Optional, Dict, Any


# --------------------------------------------------------------------------- #
# Terminal Color & Styling Constants                                          #
# --------------------------------------------------------------------------- #
def _supports_color() -> bool:
    """Detect if stdout supports ANSI color output."""
    if os.environ.get("NO_COLOR") or os.environ.get("TERM") == "dumb":
        return False
    if not hasattr(sys.stdout, "isatty"):
        return False
    return sys.stdout.isatty()


USE_COLOR = _supports_color()


class Style:
    # Reset
    RESET = "\033[0m" if USE_COLOR else ""
    BOLD = "\033[1m" if USE_COLOR else ""
    DIM = "\033[2m" if USE_COLOR else ""
    ITALIC = "\033[3m" if USE_COLOR else ""
    UNDERLINE = "\033[4m" if USE_COLOR else ""

    # Foreground Colors
    BLACK = "\033[30m" if USE_COLOR else ""
    RED = "\033[31m" if USE_COLOR else ""
    GREEN = "\033[32m" if USE_COLOR else ""
    YELLOW = "\033[33m" if USE_COLOR else ""
    BLUE = "\033[34m" if USE_COLOR else ""
    MAGENTA = "\033[35m" if USE_COLOR else ""
    CYAN = "\033[36m" if USE_COLOR else ""
    WHITE = "\033[37m" if USE_COLOR else ""

    # Bright / Rich Colors
    BRIGHT_BLACK = "\033[90m" if USE_COLOR else ""
    BRIGHT_RED = "\033[91m" if USE_COLOR else ""
    BRIGHT_GREEN = "\033[92m" if USE_COLOR else ""
    BRIGHT_YELLOW = "\033[93m" if USE_COLOR else ""
    BRIGHT_BLUE = "\033[94m" if USE_COLOR else ""
    BRIGHT_MAGENTA = "\033[95m" if USE_COLOR else ""
    BRIGHT_CYAN = "\033[96m" if USE_COLOR else ""
    BRIGHT_WHITE = "\033[97m" if USE_COLOR else ""

    # Backgrounds
    BG_GREEN = "\033[42m" if USE_COLOR else ""
    BG_RED = "\033[41m" if USE_COLOR else ""
    BG_YELLOW = "\033[43m" if USE_COLOR else ""
    BG_BLUE = "\033[44m" if USE_COLOR else ""
    BG_MAGENTA = "\033[45m" if USE_COLOR else ""
    BG_CYAN = "\033[46m" if USE_COLOR else ""
    BG_DARK = "\033[48;5;236m" if USE_COLOR else ""


def get_terminal_width(default: int = 80) -> int:
    """Get the current terminal column width safely."""
    try:
        cols, _ = shutil.get_terminal_size((default, 24))
        return max(40, min(cols, 120))
    except Exception:
        return default


# --------------------------------------------------------------------------- #
# Badges & Status Formatters                                                  #
# --------------------------------------------------------------------------- #
def badge(text: str, bg_color: str = Style.BG_BLUE, fg_color: str = Style.BRIGHT_WHITE) -> str:
    """Format a solid badge e.g. [ PASS ] or [ FAIL ]."""
    if not USE_COLOR:
        return f"[{text}]"
    return f"{bg_color}{fg_color}{Style.BOLD} {text} {Style.RESET}"


def badge_pass(text: str = "PASS") -> str:
    return f"{Style.BRIGHT_GREEN}{Style.BOLD}✔ {text}{Style.RESET}"


def badge_fail(text: str = "FAIL") -> str:
    return f"{Style.BRIGHT_RED}{Style.BOLD}✖ {text}{Style.RESET}"


def badge_warn(text: str = "WARN") -> str:
    return f"{Style.BRIGHT_YELLOW}{Style.BOLD}▲ {text}{Style.RESET}"


def badge_info(text: str = "INFO") -> str:
    return f"{Style.BRIGHT_CYAN}{Style.BOLD}ℹ {text}{Style.RESET}"


def badge_secure(text: str = "SEALED") -> str:
    return f"{Style.BRIGHT_MAGENTA}{Style.BOLD}🔒 {text}{Style.RESET}"


# --------------------------------------------------------------------------- #
# Banner & Branding Header                                                    #
# --------------------------------------------------------------------------- #
def print_banner(version: str = "1.1.0", subtitle: str = "Privacy-Preserving Media Sanitization & Cryptographic Audit"):
    """Print the signature VeilFrame Claude Code/OpenClaw-style terminal header."""
    w = get_terminal_width()
    border = "─" * (w - 2)

    print()
    print(f"{Style.BRIGHT_CYAN}╭{border}╮{Style.RESET}")
    title_line = f"  {Style.BOLD}{Style.BRIGHT_CYAN}◈ VEILFRAME{Style.RESET} {Style.DIM}v{version}{Style.RESET} {Style.BRIGHT_BLACK}│{Style.RESET} {Style.BRIGHT_MAGENTA}3-Tier QualityGate{Style.RESET} {Style.BRIGHT_BLACK}│{Style.RESET} {Style.BRIGHT_GREEN}Ed25519 Signed{Style.RESET}"
    print(title_line)
    if subtitle:
        print(f"  {Style.DIM}{subtitle}{Style.RESET}")
    print(f"{Style.BRIGHT_CYAN}╰{border}╯{Style.RESET}")
    print()


# --------------------------------------------------------------------------- #
# Box-Drawing Cards & Containers                                              #
# --------------------------------------------------------------------------- #
def print_card(title: str, items: List[Tuple[str, str]], color: str = Style.BRIGHT_CYAN):
    """
    Print an elegant key-value card container.
    Example:
    ╭─ Video Analysis ────────────────────────────────────────────────────────╮
    │  Resolution   1920x1080 (16:9)                                          │
    │  Codec        H.264 / AVC                                               │
    ╰─────────────────────────────────────────────────────────────────────────╯
    """
    w = get_terminal_width()
    inner_w = w - 4

    title_clean = f" {title} "
    dash_len = max(2, w - len(title_clean) - 4)
    top = f"╭─{Style.BOLD}{title_clean}{Style.RESET}{color}{'─' * dash_len}╮"
    bot = f"╰{'─' * (w - 2)}╯"

    print(f"{color}{top}{Style.RESET}")
    max_k_len = max((len(k) for k, _ in items), default=12)
    max_k_len = min(max_k_len, 24)

    for k, v in items:
        # Strip any internal formatting when calculating spacing
        k_disp = f"  {Style.DIM}{k:<{max_k_len}}{Style.RESET}"
        val_disp = f"  {v}"
        print(f"{color}│{Style.RESET}{k_disp}{val_disp}")
    print(f"{color}{bot}{Style.RESET}")


def print_section_header(title: str, icon: str = "◆"):
    """Print a clean section divider header."""
    w = get_terminal_width()
    title_str = f" {icon} {title} "
    dashes = "─" * max(2, w - len(title_str) - 2)
    print(f"\n{Style.BRIGHT_CYAN}{title_str}{Style.DIM}{dashes}{Style.RESET}")


# --------------------------------------------------------------------------- #
# Aligned Tables                                                              #
# --------------------------------------------------------------------------- #
def print_table(headers: List[str], rows: List[List[Any]], align_right: Optional[List[bool]] = None):
    """Print a clean Unicode table with proper column alignment."""
    if not rows:
        return

    num_cols = len(headers)
    if align_right is None:
        align_right = [False] * num_cols

    # Calculate column widths
    col_widths = [len(h) for h in headers]
    for row in rows:
        for idx, cell in enumerate(row):
            if idx < num_cols:
                # Strip ANSI for width calc
                raw_len = len(str(cell).split("\033")[0])  # simple approx
                col_widths[idx] = max(col_widths[idx], len(str(cell)))

    # Add 2 chars padding
    col_widths = [w + 2 for w in col_widths]

    # Format header
    hdr_parts = []
    for idx, h in enumerate(headers):
        if align_right[idx]:
            hdr_parts.append(f"{h:>{col_widths[idx]}}")
        else:
            hdr_parts.append(f"{h:<{col_widths[idx]}}")
    print(f"{Style.BOLD}{Style.BRIGHT_CYAN}{''.join(hdr_parts)}{Style.RESET}")

    # Separator
    sep_parts = ["─" * (w - 1) + " " for w in col_widths]
    print(f"{Style.DIM}{''.join(sep_parts)}{Style.RESET}")

    # Rows
    for row in rows:
        row_parts = []
        for idx, cell in enumerate(row):
            val_str = str(cell)
            if idx < num_cols:
                if align_right[idx]:
                    row_parts.append(f"{val_str:>{col_widths[idx]}}")
                else:
                    row_parts.append(f"{val_str:<{col_widths[idx]}}")
        print("".join(row_parts))
    print()


# --------------------------------------------------------------------------- #
# Live Multi-Stage Progress Bar                                               #
# --------------------------------------------------------------------------- #
class ProgressBar:
    """
    Smooth, live terminal progress bar with stage spinner and elapsed timer.
    """
    SPINNERS = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]

    def __init__(self, total_steps: int = 100, title: str = "Sanitizing"):
        self.total_steps = total_steps
        self.title = title
        self.start_time = time.time()
        self.spin_idx = 0
        self.last_pct = 0

    def update(self, percentage: float, stage_msg: str):
        """Update progress on current line."""
        self.spin_idx = (self.spin_idx + 1) % len(self.SPINNERS)
        spinner = self.SPINNERS[self.spin_idx]
        elapsed = time.time() - self.start_time

        pct = max(0.0, min(100.0, float(percentage)))
        self.last_pct = pct

        w = get_terminal_width()
        bar_len = max(10, min(30, w - 50))
        filled = int(round(bar_len * (pct / 100.0)))
        empty = bar_len - filled

        bar = f"{Style.BRIGHT_CYAN}{'█' * filled}{Style.DIM}{'░' * empty}{Style.RESET}"
        pct_str = f"{pct:5.1f}%"
        time_str = f"{elapsed:4.1f}s"

        # Truncate stage_msg if terminal is narrow
        avail_msg = max(15, w - (bar_len + 30))
        if len(stage_msg) > avail_msg:
            stage_msg = stage_msg[: avail_msg - 3] + "..."

        line = (
            f"\r{Style.BRIGHT_MAGENTA}{spinner}{Style.RESET} "
            f"{Style.BOLD}{self.title}{Style.RESET} "
            f"[{bar}] {Style.BOLD}{pct_str}{Style.RESET} "
            f"{Style.DIM}({time_str}){Style.RESET} "
            f"{Style.BRIGHT_WHITE}{stage_msg:<{avail_msg}}{Style.RESET}"
        )

        if USE_COLOR:
            sys.stdout.write(line)
        else:
            sys.stdout.write(f"\r[{pct:3.0f}%] {stage_msg}")
        sys.stdout.flush()

    def finish(self, success: bool = True, final_msg: str = "Complete"):
        """Finish and clear the progress line."""
        elapsed = time.time() - self.start_time
        icon = badge_pass("DONE") if success else badge_fail("FAILED")
        sys.stdout.write(f"\r{' ' * (get_terminal_width() - 1)}\r")
        sys.stdout.write(
            f"{icon} {Style.BOLD}{self.title}{Style.RESET} "
            f"{Style.DIM}in {elapsed:.2f}s{Style.RESET} — {final_msg}\n"
        )
        sys.stdout.flush()


# --------------------------------------------------------------------------- #
# Tree & Key-Value Details Printer                                            #
# --------------------------------------------------------------------------- #
def print_tree(root_name: str, nodes: List[Tuple[str, str]]):
    """
    Print hierarchical key-value details in a clean directory/tree style.
    Example:
    ◈ Output Verification
      ├── Container Atoms    Cleaned (0 tracking tags)
      ├── Elementary Stream  Valid H.264
      └── Audit Manifest     Signed Ed25519
    """
    print(f"{Style.BOLD}{Style.BRIGHT_CYAN}◈ {root_name}{Style.RESET}")
    for idx, (label, val) in enumerate(nodes):
        is_last = (idx == len(nodes) - 1)
        branch = "└── " if is_last else "├── "
        print(f"  {Style.DIM}{branch}{Style.RESET}{Style.BOLD}{label:<22}{Style.RESET} {val}")
    print()


def clear_screen():
    """Clear terminal screen if running in an interactive terminal."""
    if USE_COLOR and hasattr(sys.stdout, "isatty") and sys.stdout.isatty():
        # ANSI clear screen and reset cursor to top left
        sys.stdout.write("\033[2J\033[H")
        sys.stdout.flush()
    else:
        print("\n" + "═" * get_terminal_width() + "\n")


def print_status_bar(items: List[Tuple[str, str, str]]):
    """
    Print a horizontal top status bar with color-coded chips.
    items: List of (label, value, color)
    """
    w = get_terminal_width()
    parts = []
    for lbl, val, col in items:
        parts.append(f"{Style.DIM}{lbl}:{Style.RESET}{col}{val}{Style.RESET}")
    bar_content = "  │  ".join(parts)
    print(f"{Style.DIM}┌─{Style.RESET} {bar_content}")


def render_metric_gauge(name: str, val: float, target: float, unit: str = "", higher_is_better: bool = True, width: int = 16) -> str:
    """Render a visual terminal mini-gauge with pass/fail badge."""
    passed = (val >= target) if higher_is_better else (val <= target)
    pct = min(1.0, max(0.0, val / (target * 1.1 if target > 0 else 1.0)))
    filled = int(round(width * pct))
    empty = width - filled
    col = Style.BRIGHT_GREEN if passed else Style.BRIGHT_RED
    bar = f"{col}{'█' * filled}{Style.DIM}{'░' * empty}{Style.RESET}"
    badge_str = badge_pass() if passed else badge_fail()
    return f"{Style.BOLD}{name:<16}{Style.RESET} [{bar}] {col}{val:.4f}{unit}{Style.RESET} (Target: {target}{unit}) {badge_str}"


def pause_for_user(prompt: str = "Press Enter to return to main menu..."):
    """Pause until user hits Enter, with graceful Ctrl+C handling."""
    print(f"\n{Style.DIM}{prompt}{Style.RESET}", end="", flush=True)
    try:
        input()
    except (EOFError, KeyboardInterrupt):
        print()


# --------------------------------------------------------------------------- #
# Interactive Prompts & Helpers                                               #
# --------------------------------------------------------------------------- #
def prompt_choice(prompt: str, choices: List[str], default_idx: int = 0) -> int:
    """Prompt user to select from a list of choices."""
    print(f"\n{Style.BOLD}{Style.BRIGHT_CYAN}? {prompt}{Style.RESET}")
    for idx, c in enumerate(choices):
        prefix = f"{Style.BRIGHT_GREEN}▸{Style.RESET}" if idx == default_idx else " "
        num = f"{idx + 1}"
        print(f"  {prefix} {Style.BOLD}[{num}]{Style.RESET} {c}")

    while True:
        try:
            val = input(f"\n{Style.DIM}Select option (1-{len(choices)}, default: {default_idx + 1}): {Style.RESET}").strip()
            if not val:
                return default_idx
            if val.lower() in ("q", "quit", "exit"):
                return len(choices) - 1  # Assume last option is Exit
            selected = int(val) - 1
            if 0 <= selected < len(choices):
                return selected
            print(f"{Style.BRIGHT_RED}Invalid choice. Enter 1 to {len(choices)}.{Style.RESET}")
        except (ValueError, EOFError, KeyboardInterrupt):
            print(f"\n{Style.YELLOW}Operation cancelled.{Style.RESET}")
            sys.exit(0)


def prompt_text(prompt: str, default: str = "") -> str:
    """Prompt user for a text input or drag-and-drop file path."""
    def_str = f" {Style.DIM}[{default}]{Style.RESET}" if default else ""
    try:
        val = input(f"{Style.BOLD}{Style.BRIGHT_CYAN}? {prompt}{def_str}:{Style.RESET} ").strip()
        # Handle drag-and-dropped paths that might contain surrounding quotes
        if val.startswith(('"', "'")) and val.endswith(('"', "'")):
            val = val[1:-1]
        return val if val else default
    except (EOFError, KeyboardInterrupt):
        print(f"\n{Style.YELLOW}Operation cancelled.{Style.RESET}")
        sys.exit(0)


def prompt_confirm(prompt: str, default: bool = True) -> bool:
    """Prompt user for yes/no confirmation."""
    def_str = "[Y/n]" if default else "[y/N]"
    try:
        val = input(f"{Style.BOLD}{Style.BRIGHT_CYAN}? {prompt} {Style.DIM}{def_str}:{Style.RESET} ").strip().lower()
        if not val:
            return default
        return val in ("y", "yes", "true", "1")
    except (EOFError, KeyboardInterrupt):
        print(f"\n{Style.YELLOW}Operation cancelled.{Style.RESET}")
        sys.exit(0)
