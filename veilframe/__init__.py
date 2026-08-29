"""
VeilFrame — Privacy-focused media sanitization with independent visual-fidelity verification and cryptographically signed audit manifests.
"""
__version__ = "1.1.0"
__all__ = ["__version__", "main"]


def main():
    """Launch the VeilFrame desktop GUI application."""
    from .app import main as app_main
    return app_main()
