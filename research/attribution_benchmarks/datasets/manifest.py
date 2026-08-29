"""
Dataset manifest specification and cryptographic integrity verification.
"""
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
import json

from ..common.io import compute_file_sha256


@dataclass
class DatasetEntry:
    entry_id: str
    camera_id: str
    clip_type: str  # "reference", "transformed", "different_camera"
    file_path: str
    sha256: str
    duration_sec: float = 0.0
    tags: Dict[str, Any] = field(default_factory=dict)


@dataclass
class DatasetManifest:
    manifest_version: str = "1.0.0"
    dataset_name: str = ""
    description: str = ""
    cameras: List[str] = field(default_factory=list)
    entries: List[DatasetEntry] = field(default_factory=list)
    manifest_sha256: Optional[str] = None

    def verify_integrity(self, base_dir: Optional[Path] = None) -> Tuple[bool, List[str]]:
        """Verifies that all files exist and match their recorded SHA-256 hashes."""
        errors: List[str] = []
        for entry in self.entries:
            path = Path(entry.file_path)
            if base_dir and not path.is_absolute():
                path = base_dir / path

            if not path.exists():
                errors.append(f"Missing file: {path}")
                continue

            actual_sha = compute_file_sha256(path)
            if actual_sha.lower() != entry.sha256.lower():
                errors.append(f"SHA-256 mismatch for {path}: expected {entry.sha256}, got {actual_sha}")

        return (len(errors) == 0), errors

    def to_dict(self) -> Dict[str, Any]:
        return {
            "manifest_version": self.manifest_version,
            "dataset_name": self.dataset_name,
            "description": self.description,
            "cameras": self.cameras,
            "entries": [
                {
                    "entry_id": e.entry_id,
                    "camera_id": e.camera_id,
                    "clip_type": e.clip_type,
                    "file_path": e.file_path,
                    "sha256": e.sha256,
                    "duration_sec": e.duration_sec,
                    "tags": e.tags,
                }
                for e in self.entries
            ],
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DatasetManifest":
        entries = [
            DatasetEntry(
                entry_id=e["entry_id"],
                camera_id=e["camera_id"],
                clip_type=e.get("clip_type", "reference"),
                file_path=e["file_path"],
                sha256=e["sha256"],
                duration_sec=e.get("duration_sec", 0.0),
                tags=e.get("tags", {}),
            )
            for e in data.get("entries", [])
        ]
        return cls(
            manifest_version=data.get("manifest_version", "1.0.0"),
            dataset_name=data.get("dataset_name", ""),
            description=data.get("description", ""),
            cameras=data.get("cameras", []),
            entries=entries,
        )
