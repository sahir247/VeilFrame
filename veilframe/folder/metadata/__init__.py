"""
veilframe.folder.metadata — File type, language, and filesystem metadata engines.
"""

from veilframe.folder.metadata.file_type import detect_file_type
from veilframe.folder.metadata.language_detector import detect_language
from veilframe.folder.metadata.metadata_engine import MetadataEngine

__all__ = [
    "detect_file_type",
    "detect_language",
    "MetadataEngine",
]
