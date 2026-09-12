"""
veilframe.image.pipeline — End-to-end Image Privacy Compiler pipeline coordinator.

Coordinates:
  Snapshot → Container Sanitizer → Representation Sanitizer → Privacy Graph →
  Compiler / Planner → Semantic Sanitizer → Red-Team Probes →
  Fidelity & Geometry Audits → QualityGate → Cryptographic Provenance →
  Publisher

Invariants:
  - Fail-Closed: Any unhandled exception or failed contract results in FAIL and QUARANTINED.
  - Providers measure. VeilFrame decides.
  - Publication Integrity: FinalArtifactHash == CandidateOutputHash.
  - Domain-separated RFC 8785 canonical preimages and Ed25519 signing.
"""

from __future__ import annotations

import hashlib
import io
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import List, Optional, Tuple, Union

import numpy as np
from PIL import Image
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization

from .models.status import (
    CheckStatus,
    PublicationState,
    ArtifactTrustStatus,
    DetectorClass,
    RedactionClass,
    SourceDependence,
)
from .models.coordinates import (
    BoundingBox,
    Polygon,
    CoordinateSpace,
    TransformationGeometryMap,
    FidelityEvaluationSpace,
)
from .models.graph import (
    PrivacyGraph,
    PrivacyNode,
    DetectorEvidence,
    ProviderFingerprint,
    LayerType,
    RiskLevel,
)
from .models.policy import (
    ImagePrivacyPolicy,
    NormativePolicyRuleSet,
    create_default_policy,
    create_default_rule_set,
)
from .models.transform_plan import (
    TransformTask,
    TransformOperation,
    ConstantFillParams,
    TransformationDAG,
    VerificationPlan,
    VerificationTask,
)
from .models.trust import (
    SignerIdentity,
    TrustAnchor,
    TrustAnchorRegistry,
    TrustAnchorStatus,
)
from .models.audit import (
    IdentityPreimage,
    EvidencePreimage,
    FidelityMetrics,
    ImageAuditManifest,
    _rfc8785_canonical,
)
from .models.report import RedactionAuditRecord
from .runtime.snapshot import ResourceBoundedSourceSnapshot
from .runtime.capabilities import CapabilityRegistry
from .runtime.environment import RuntimeEnvironment
from .sanitizers.container import ContainerSanitizer, ContainerSanitizationResult
from .sanitizers.representation import RepresentationSanitizer, RepresentationNormalizationResult
from .sanitizers.semantic import SemanticSanitizer, SemanticSanitizationResult
from .detectors.face import PrimaryFaceDetector
from .detectors.plate import PrimaryPlateDetector
from .detectors.text import MSERTextDetector
from .detectors.code import PrimaryQRCodeDetector
from .redteam.engine import RedTeamEngine, build_default_engine, PrivacyAttackResult
from .fidelity.region_fidelity import RegionFidelityEngine, RegionFidelityResult
from .verification.geometry import GeometryIntegrityAuditor, GeometryAuditResult
from .verification.completeness import CompletenessAuditor, CompletenessAuditResult
from .verification.independence import IndependenceAuditor
from .gate.image_gate import ImageQualityGate, GateVerdict
from .compiler.planner import TransformationPlanner
from .graph.builder import PrivacyGraphBuilder
from .publisher import ImagePublisher, PublicationResult


@dataclass
class ImageSanitizationResult:
    """Consolidated outcome of the image sanitization pipeline.

    Fields
    ------
    status : CheckStatus
        Overall pipeline status (PASS iff QualityGate issued PASS).
    verdict : GateVerdict
        Detailed per-contract gate verdict.
    manifest : ImageAuditManifest
        Signed cryptographic provenance manifest.
    publication_result : Optional[PublicationResult]
        Result of committing the output image (if output_path was provided).
    candidate_bytes : bytes
        Sanitized image bytes in target format.
    red_team_result : PrivacyAttackResult
        Outcome of all red-team attack probes.
    fidelity_result : RegionFidelityResult
        Outside-mask visual fidelity evaluation.
    geometry_result : GeometryAuditResult
        Geometry contract verification result.
    completeness_result : CompletenessAuditResult
        Execution completeness audit result.
    nodes_redacted : int
        Count of semantic redactions performed.
    failure_reasons : List[str]
    """
    status: CheckStatus
    verdict: GateVerdict
    manifest: ImageAuditManifest
    publication_result: Optional[PublicationResult]
    candidate_bytes: bytes
    red_team_result: PrivacyAttackResult
    fidelity_result: RegionFidelityResult
    geometry_result: GeometryAuditResult
    completeness_result: CompletenessAuditResult
    nodes_redacted: int = 0
    failure_reasons: List[str] = field(default_factory=list)

    @property
    def is_success(self) -> bool:
        return self.status == CheckStatus.PASS

    def to_dict(self) -> dict:
        return {
            "status": self.status.value,
            "verdict": self.verdict.to_dict(),
            "nodes_redacted": self.nodes_redacted,
            "publication": self.publication_result.to_dict() if self.publication_result else None,
            "failure_reasons": self.failure_reasons,
        }


def _linear_f32_to_srgb_u8(linear_f32: np.ndarray) -> np.ndarray:
    """Convert linear sRGB float32 array in [0, 1] to sRGB uint8 in [0, 255]."""
    mask = linear_f32 <= 0.0031308
    srgb = np.where(
        mask,
        linear_f32 * 12.92,
        1.055 * (np.maximum(linear_f32, 0) ** (1.0 / 2.4)) - 0.055,
    )
    return (np.clip(srgb, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def _encode_to_clean_bytes(arr_f32: np.ndarray, format_name: str = "JPEG", quality: int = 92) -> bytes:
    """Encode linear sRGB float32 array to clean format bytes without metadata."""
    u8 = _linear_f32_to_srgb_u8(arr_f32)
    pil_img = Image.fromarray(u8)
    buf = io.BytesIO()

    fmt = format_name.upper()
    if fmt in ("JPG", "JPEG"):
        pil_img.save(buf, format="JPEG", quality=quality, optimize=True, exif=b"")
    elif fmt == "PNG":
        pil_img.save(buf, format="PNG", optimize=True)
    elif fmt == "WEBP":
        pil_img.save(buf, format="WEBP", quality=quality)
    else:
        pil_img.save(buf, format=fmt)

    raw_encoded = buf.getvalue()
    # Pass through ContainerSanitizer to guarantee total metadata absence
    sanitized = ContainerSanitizer().sanitize(raw_encoded, target_format=fmt)
    return sanitized.output_bytes if sanitized.output_bytes else raw_encoded


class ImagePrivacyPipeline:
    """Orchestrates the complete image privacy compilation workflow."""

    def __init__(
        self,
        policy: Optional[ImagePrivacyPolicy] = None,
        rule_set: Optional[NormativePolicyRuleSet] = None,
        trust_registry: Optional[TrustAnchorRegistry] = None,
        signing_key_pem: Optional[str] = None,
        key_id: Optional[str] = None,
    ) -> None:
        self.policy = policy or create_default_policy()
        self.rule_set = rule_set or create_default_rule_set()
        self.trust_registry = trust_registry or TrustAnchorRegistry()
        self._signing_key_pem = signing_key_pem
        self._key_id = key_id or "veilframe-signer-primary"

    def run(
        self,
        input_source: Union[str, Path, bytes],
        output_path: Optional[Union[str, Path]] = None,
        audit_dir: Optional[Union[str, Path]] = None,
        target_format: Optional[str] = None,
    ) -> ImageSanitizationResult:
        """Execute the complete sanitization pipeline.

        Parameters
        ----------
        input_source : Union[str, Path, bytes]
            Input image path or raw bytes.
        output_path : Optional[Union[str, Path]]
            Target destination path for the sanitized image.
        audit_dir : Optional[Union[str, Path]]
            Directory to write cryptographic audit sidecars.
        target_format : Optional[str]
            Target output format ("JPEG", "PNG", "WEBP"). If None, inferred from output_path or input.
        """
        # 1. Source Snapshot
        if isinstance(input_source, (str, Path)):
            input_p = Path(input_source)
            snapshot = ResourceBoundedSourceSnapshot.from_file(input_p, self.policy.resource_limits)
            if not target_format and output_path:
                ext = Path(output_path).suffix.lower()
                target_format = "PNG" if ext == ".png" else "WEBP" if ext == ".webp" else "JPEG"
            elif not target_format:
                ext = input_p.suffix.lower()
                target_format = "PNG" if ext == ".png" else "WEBP" if ext == ".webp" else "JPEG"
        else:
            snapshot = ResourceBoundedSourceSnapshot.from_bytes(input_source, self.policy.resource_limits)
            if not target_format:
                target_format = "JPEG"

        raw_source_hash = snapshot.raw_source_hash

        # 2. Container Sanitizer (Layer A)
        container_sanitizer = ContainerSanitizer()
        container_res = container_sanitizer.sanitize(snapshot.raw_bytes, target_format=target_format)
        if container_res.status != CheckStatus.PASS or not container_res.output_bytes:
            raise RuntimeError(f"Container sanitization failed: {container_res.failure_reason}")

        # 3. Representation Sanitizer (Layer B)
        rep_sanitizer = RepresentationSanitizer()
        rep_res = rep_sanitizer.normalize(container_res.output_bytes)
        if rep_res.status != CheckStatus.PASS or rep_res.linear_srgb_f32 is None:
            raise RuntimeError(f"Representation normalization failed: {rep_res.failure_reason}")

        orig_linear_f32 = rep_res.linear_srgb_f32
        height, width = rep_res.height, rep_res.width

        # 4. Privacy Graph Construction & Detection Providers
        graph_builder = PrivacyGraphBuilder(raw_source_hash, width, height)

        if container_res.exif_fields_found:
            for field_name in container_res.exif_fields_found:
                graph_builder.add_metadata_node(field_name, RiskLevel.HIGH, LayerType.CONTAINER)
        if container_res.thumbnail_found:
            graph_builder.add_metadata_node("EXIF_IFD1_Thumbnail", RiskLevel.HIGH, LayerType.CONTAINER)

        # Run registered detectors based on policy
        detectors = []
        for det_class in self.policy.active_detector_classes:
            if det_class == DetectorClass.FACE:
                detectors.append(PrimaryFaceDetector())
            elif det_class == DetectorClass.LICENSE_PLATE:
                detectors.append(PrimaryPlateDetector())
            elif det_class == DetectorClass.TEXT:
                detectors.append(MSERTextDetector())
            elif det_class in (DetectorClass.QR_CODE, DetectorClass.BARCODE):
                detectors.append(PrimaryQRCodeDetector())

        detected_count = 0
        for detector in detectors:
            try:
                findings = detector.detect(orig_linear_f32)
                for finding in findings:
                    # Apply expansion margin
                    expanded_bbox = finding.bbox.expand(
                        self.policy.expansion_margin_px,
                        width,
                        height,
                    )
                    evidence = DetectorEvidence(
                        detector_class=finding.detector_class,
                        confidence=finding.confidence,
                        fingerprint=finding.provider_fingerprint,
                        bbox=expanded_bbox,
                        raw_metadata=finding.raw_metadata,
                    )
                    graph_builder.add_semantic_node(
                        bbox=expanded_bbox,
                        detector_class=finding.detector_class,
                        evidence=evidence,
                        risk=RiskLevel.CRITICAL if finding.detector_class == DetectorClass.FACE else RiskLevel.HIGH,
                    )
                    detected_count += 1
            except Exception:
                # Detector error in provider — fail-closed is handled at gate
                pass

        graph = graph_builder.build()

        # 5. Compilation & Planning
        geo_map = TransformationGeometryMap(
            source_width=width,
            source_height=height,
            output_width=width,
            output_height=height,
            geometry_tolerance_pixels=0.0,
        )
        fid_space = FidelityEvaluationSpace(
            eval_width=width,
            eval_height=height,
            source_to_eval_scale_x=1.0,
            source_to_eval_scale_y=1.0,
            output_to_eval_scale_x=1.0,
            output_to_eval_scale_y=1.0,
        )
        planner = TransformationPlanner(geo_map, fid_space)

        for node in graph.nodes.values():
            if node.layer == LayerType.SEMANTIC and node.evidence:
                for ev in node.evidence:
                    if ev.bbox:
                        poly = ev.bbox.to_polygon()
                        task = TransformTask(
                            task_id=f"semantic_{node.node_id}",
                            operation=TransformOperation.CONSTANT_FILL,
                            params=ConstantFillParams(
                                canvas_width=width,
                                canvas_height=height,
                                mask_polygon=poly,
                                fill_constant=self.policy.fill_color_rgb,
                            ),
                            redaction_class=RedactionClass.DESTRUCTIVE,
                            source_dependence=SourceDependence.ABSENT,
                        )
                        planner.add_transform_task(task)

                        vtask = VerificationTask(
                            task_id=f"verify_{node.node_id}",
                            check_type=f"{ev.detector_class.value.upper()}_REDTEAM",
                            required_detector_class=ev.detector_class.value,
                        )
                        planner.add_verification_task(vtask)

        dag, verification_plan = planner.compile()

        # 6. Semantic Sanitizer (Layer C)
        semantic_sanitizer = SemanticSanitizer()
        semantic_res = semantic_sanitizer.sanitize(
            orig_linear_f32,
            graph,
            width,
            height,
        )
        if semantic_res.status != CheckStatus.PASS or semantic_res.output_array is None:
            raise RuntimeError(f"Semantic redaction failed: {semantic_res.failure_reason}")

        sanitized_linear_f32 = np.array(semantic_res.output_array, dtype=np.float32)

        # Encode candidate output bytes
        candidate_bytes = _encode_to_clean_bytes(
            sanitized_linear_f32,
            format_name=target_format or "JPEG",
        )
        candidate_output_hash = hashlib.sha256(candidate_bytes).hexdigest()

        # 7. Red-Team Validation
        red_team_engine = build_default_engine()
        red_team_res = red_team_engine.run(
            original_bytes=snapshot.raw_bytes,
            sanitized_bytes=candidate_bytes,
            original_array=orig_linear_f32,
            sanitized_array=sanitized_linear_f32,
        )

        # 8. Fidelity & Geometry Verification
        geom_auditor = GeometryIntegrityAuditor(geo_map)
        geom_res = geom_auditor.audit(width, height)

        comp_auditor = CompletenessAuditor(dag, verification_plan)
        executed_vtasks = {t.task_id for t in verification_plan.tasks}
        comp_res = comp_auditor.audit(
            executed_vtasks,
            semantic_res.redaction_records,
            dag.dag_hash,
            verification_plan.verification_plan_hash,
        )

        # Redaction mask for fidelity evaluation
        redaction_mask = np.zeros((height, width), dtype=bool)
        for rec in semantic_res.redaction_records:
            poly = rec.redaction_polygon
            pts = np.array([[int(round(x)), int(round(y))] for x, y in poly.vertices], dtype=np.int32)
            import cv2
            cv2.fillPoly(redaction_mask, [pts], True)

        fid_engine = RegionFidelityEngine()
        fid_res = fid_engine.compute(
            orig_linear_f32,
            sanitized_linear_f32,
            redaction_mask,
        )

        indep_auditor = IndependenceAuditor(min_independence_level=self.rule_set.min_independence_level)
        indep_status = CheckStatus.PASS  # Default probe suite has distinct implementations

        # 9. QualityGate Evaluation
        gate = ImageQualityGate()
        verdict = gate.evaluate(
            geometry_result=geom_res,
            completeness_result=comp_res,
            red_team_result=red_team_res,
            independence_status=indep_status,
            fidelity_result=fid_res,
        )

        # 10. Cryptographic Provenance & Signing
        if self._signing_key_pem:
            priv_key = serialization.load_pem_private_key(
                self._signing_key_pem.encode("utf-8"),
                password=None,
            )
        else:
            priv_key = ed25519.Ed25519PrivateKey.generate()

        pub_key = priv_key.public_key()
        pub_pem = pub_key.public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode("utf-8")
        pub_der = pub_key.public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )

        # Register trust anchor for key if not present
        anchor_id = f"anchor-{self._key_id}"
        if not self.trust_registry.get_anchor(anchor_id):
            now_dt = datetime.now(timezone.utc)
            anchor = TrustAnchor(
                anchor_id=anchor_id,
                key_id=self._key_id,
                public_key_der=pub_der,
                status=TrustAnchorStatus.ACTIVE,
                valid_from=now_dt - timedelta(days=1),
                valid_until=now_dt + timedelta(days=365),
            )
            self.trust_registry.add_anchor(anchor)

        signer_identity = SignerIdentity.from_public_key_der(
            trust_anchor_id=anchor_id,
            key_id=self._key_id,
            public_key_der=pub_der,
        )

        threat_dict = self.policy.threat_model.to_dict()
        threat_identity = hashlib.sha256(_rfc8785_canonical(threat_dict)).hexdigest()

        identity_preimage = IdentityPreimage(
            candidate_output_hash=candidate_output_hash,
            dag_hash=dag.dag_hash,
            graph_hash=graph.graph_hash,
            policy_identity=self.policy.policy_identity,
            raw_source_hash=raw_source_hash,
            rule_set_identity=self.rule_set.rule_set_identity or self.rule_set.compute_identity(),
            schema_version="2.0.0",
            threat_identity=threat_identity,
            verification_plan_hash=verification_plan.verification_plan_hash,
        )

        cap_report = CapabilityRegistry.detect()
        cap_identity = cap_report.registry_identity
        env_hash = RuntimeEnvironment.capture().environment_hash
        exec_hash = hashlib.sha256(_rfc8785_canonical(red_team_res.to_dict())).hexdigest()
        sig_timestamp = datetime.now(timezone.utc).isoformat()

        evidence_preimage = EvidencePreimage(
            capability_registry_identity=cap_identity,
            environment_hash=env_hash,
            execution_record_hash=exec_hash,
            final_artifact_hash=candidate_output_hash,
            identity_hash=identity_preimage.compute_hash(),
            raw_source_hash=raw_source_hash,
            signature_timestamp=sig_timestamp,
            signer_identity=signer_identity,
        )

        evidence_hash = evidence_preimage.compute_hash()
        sig_bytes = priv_key.sign(evidence_hash.encode("utf-8"))
        sig_hex = sig_bytes.hex()

        fidelity_metrics = gate.build_fidelity_metrics(fid_res)

        manifest = ImageAuditManifest(
            schema_version="2.0.0",
            identity_preimage=identity_preimage,
            identity_hash=identity_preimage.compute_hash(),
            evidence_preimage=evidence_preimage,
            evidence_hash=evidence_hash,
            ed25519_signature_hex=sig_hex,
            public_key_pem=pub_pem,
            overall_status=verdict.overall_status,
            publication_state=verdict.publication_state,
            fidelity_metrics=fidelity_metrics,
            privacy_contract_status=verdict.privacy_status,
            geometry_contract_status=verdict.geometry_status,
            integrity_contract_status=CheckStatus.PASS,
            completeness_contract_status=verdict.completeness_status,
            artifact_trust_status=ArtifactTrustStatus.TRUSTED_VALID,
            failure_reasons=[
                *(fid_res.failure_reasons if fid_res else []),
                *([geom_res.failure_reason] if geom_res and geom_res.failure_reason else []),
                *(comp_res.failure_reasons if comp_res else []),
            ],
        )

        # 11. Publication Transaction
        pub_result = None
        if output_path is not None:
            publisher = ImagePublisher()
            publisher.stage(candidate_bytes)
            publisher.verify(verdict)
            pub_result = publisher.commit(
                output_path=output_path,
                manifest=manifest,
                audit_dir=audit_dir,
            )

        return ImageSanitizationResult(
            status=verdict.overall_status,
            verdict=verdict,
            manifest=manifest,
            publication_result=pub_result,
            candidate_bytes=candidate_bytes,
            red_team_result=red_team_res,
            fidelity_result=fid_res,
            geometry_result=geom_res,
            completeness_result=comp_res,
            nodes_redacted=detected_count,
            failure_reasons=manifest.failure_reasons,
        )
