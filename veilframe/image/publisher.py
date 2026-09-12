"""
veilframe.image.publisher — Transactional publication state machine and audit bundler.

Implements the publication state machine per spec §7:
  STAGED → VERIFIED → COMMITTING → COMMITTED (or QUARANTINED)

Invariants:
  - Publication Integrity Invariant: FinalArtifactHash == CandidateOutputHash.
  - Atomicity: Candidate output is written to a temporary sibling file and replaced.
  - Fail-Closed: Any failure during commit rolls back to QUARANTINED.
  - Audit Artifacts: Writes manifest.json, manifest.sig, manifest.sha256, and pubkey.pem.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .models.audit import ImageAuditManifest, _rfc8785_canonical
from .models.status import CheckStatus, PublicationState
from .gate.image_gate import GateVerdict


class PublicationError(Exception):
    """Raised when publication invariants are violated or commit fails."""


@dataclass
class PublicationResult:
    """Outcome of the publication transaction.

    Fields
    ------
    state : PublicationState
        Final state (COMMITTED or QUARANTINED).
    output_path : Optional[Path]
        Path to the published image file (None if quarantined without saving).
    manifest_path : Optional[Path]
        Path to the written .manifest.json sidecar.
    sig_path : Optional[Path]
        Path to the written .manifest.sig sidecar.
    sha256_path : Optional[Path]
        Path to the written .manifest.sha256 sidecar.
    pubkey_path : Optional[Path]
        Path to the written .pubkey.pem sidecar.
    final_artifact_hash : Optional[str]
        SHA-256 of the committed file on disk.
    error_message : Optional[str]
        Details if publication was quarantined or failed.
    """
    state: PublicationState
    output_path: Optional[Path] = None
    manifest_path: Optional[Path] = None
    sig_path: Optional[Path] = None
    sha256_path: Optional[Path] = None
    pubkey_path: Optional[Path] = None
    final_artifact_hash: Optional[str] = None
    error_message: Optional[str] = None

    @property
    def is_committed(self) -> bool:
        return self.state == PublicationState.COMMITTED

    def to_dict(self) -> dict:
        return {
            "state": self.state.value,
            "output_path": str(self.output_path) if self.output_path else None,
            "manifest_path": str(self.manifest_path) if self.manifest_path else None,
            "final_artifact_hash": self.final_artifact_hash,
            "error_message": self.error_message,
        }


class ImagePublisher:
    """Stateful publication manager executing the 4-phase publication protocol."""

    def __init__(self) -> None:
        self._state: PublicationState = PublicationState.STAGED
        self._candidate_bytes: Optional[bytes] = None
        self._candidate_hash: Optional[str] = None
        self._verdict: Optional[GateVerdict] = None

    @property
    def state(self) -> PublicationState:
        return self._state

    def stage(self, candidate_bytes: bytes) -> str:
        """Stage candidate sanitized image bytes.

        Returns candidate_output_hash (SHA-256 hex).
        """
        if not candidate_bytes:
            self._state = PublicationState.QUARANTINED
            raise PublicationError("Candidate output bytes cannot be empty.")

        self._candidate_bytes = candidate_bytes
        self._candidate_hash = hashlib.sha256(candidate_bytes).hexdigest()
        self._state = PublicationState.STAGED
        return self._candidate_hash

    def verify(self, verdict: GateVerdict) -> PublicationState:
        """Verify the candidate using the QualityGate verdict."""
        self._verdict = verdict
        if (
            verdict.overall_status == CheckStatus.PASS
            and verdict.publication_state == PublicationState.VERIFIED
        ):
            self._state = PublicationState.VERIFIED
        else:
            self._state = PublicationState.QUARANTINED
        return self._state

    def commit(
        self,
        output_path: str | Path,
        manifest: ImageAuditManifest,
        audit_dir: Optional[str | Path] = None,
        quarantine_on_failure: bool = True,
    ) -> PublicationResult:
        """Atomically commit candidate image and write cryptographic audit sidecars.

        Parameters
        ------
        output_path : str | Path
            Target destination for the sanitized image.
        manifest : ImageAuditManifest
            Top-level signed manifest.
        audit_dir : Optional[str | Path]
            Optional directory for audit sidecars. If None, placed alongside output_path.
        quarantine_on_failure : bool
            If True, on gate or invariant failure writes to <output_path>.quarantined.
        """
        target_path = Path(output_path).resolve()
        target_dir = target_path.parent
        target_dir.mkdir(parents=True, exist_ok=True)

        if self._state != PublicationState.VERIFIED:
            self._state = PublicationState.QUARANTINED
            if quarantine_on_failure and self._candidate_bytes is not None:
                quarantine_path = target_dir / f"{target_path.name}.quarantined"
                quarantine_path.write_bytes(self._candidate_bytes)
                return PublicationResult(
                    state=PublicationState.QUARANTINED,
                    output_path=quarantine_path,
                    error_message=f"Gate rejected candidate (overall={self._verdict.overall_status if self._verdict else 'UNKNOWN'}). File quarantined.",
                )
            return PublicationResult(
                state=PublicationState.QUARANTINED,
                error_message="Cannot commit: Candidate is not in VERIFIED state.",
            )

        self._state = PublicationState.COMMITTING
        temp_file = None

        try:
            # 1. Atomic write candidate bytes via temporary sibling
            with tempfile.NamedTemporaryFile(
                dir=str(target_dir),
                prefix=f".tmp_{target_path.stem}_",
                delete=False,
            ) as tmp:
                temp_file = Path(tmp.name)
                tmp.write(self._candidate_bytes)
                tmp.flush()
                os.fsync(tmp.fileno())

            # Replace target atomically
            temp_file.replace(target_path)
            temp_file = None

            # 2. Read back committed bytes and verify Publication Integrity Invariant
            committed_bytes = target_path.read_bytes()
            final_hash = hashlib.sha256(committed_bytes).hexdigest()

            if final_hash != self._candidate_hash:
                raise PublicationError(
                    f"Publication Integrity Invariant violated: candidate hash {self._candidate_hash} "
                    f"does not match final disk artifact hash {final_hash}"
                )

            # 3. Determine sidecar directory
            sidecar_dir = Path(audit_dir).resolve() if audit_dir else target_dir
            sidecar_dir.mkdir(parents=True, exist_ok=True)

            base_name = target_path.name
            manifest_file = sidecar_dir / f"{base_name}.manifest.json"
            sig_file = sidecar_dir / f"{base_name}.manifest.sig"
            sha256_file = sidecar_dir / f"{base_name}.manifest.sha256"
            pubkey_file = sidecar_dir / f"{base_name}.pubkey.pem"

            manifest_dict = manifest.to_dict()
            manifest_json_bytes = _rfc8785_canonical(manifest_dict)

            manifest_file.write_bytes(manifest_json_bytes)
            sig_file.write_text(manifest.ed25519_signature_hex, encoding="utf-8")
            sha256_file.write_text(manifest.evidence_hash, encoding="utf-8")
            pubkey_file.write_text(manifest.public_key_pem, encoding="utf-8")

            self._state = PublicationState.COMMITTED

            return PublicationResult(
                state=PublicationState.COMMITTED,
                output_path=target_path,
                manifest_path=manifest_file,
                sig_path=sig_file,
                sha256_path=sha256_file,
                pubkey_path=pubkey_file,
                final_artifact_hash=final_hash,
            )

        except Exception as exc:
            self._state = PublicationState.QUARANTINED
            if temp_file and temp_file.exists():
                try:
                    temp_file.unlink()
                except OSError:
                    pass
            if target_path.exists():
                try:
                    target_path.unlink()
                except OSError:
                    pass

            return PublicationResult(
                state=PublicationState.QUARANTINED,
                error_message=f"Publication failed during commit: {type(exc).__name__}: {exc}",
            )
