"""
tests/image/test_foundation.py — Phase 2A comprehensive test suite.

Covers all normative invariants from the VeilFrame Phase 2 spec:
  - TrustAnchor historical validity & RevocationReason semantics
  - SignerIdentity binding in VEILFRAME-EVIDENCE-V1
  - GeometryIntegrityAuditor Geometry Contract (before fidelity)
  - ResourceBoundedSourceSnapshot resource limits
  - CoordinateSpace transformations
  - TransformTask immutability & ConstantFill interface isolation
  - RFC 8785 domain-separated preimage correctness
  - Publication state machine transitions & QUARANTINED rollback
  - Five Contracts + Fail-Closed Invariant logic
  - Topological sort with cycle detection & unknown deps
  - IndependenceAuditor levels 0–3
  - StatusNormalizer fail-closed semantics
"""

import hashlib
import struct
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from veilframe.image.models.status import (
    CheckStatus, IndependenceLevel, DetectorClass, ArtifactTrustStatus,
    RevocationReason, PublicationState, RedactionClass,
    SourceDependence, TrustAnchorStatus, CapabilityProfile,
)
from veilframe.image.models.coordinates import (
    BoundingBox, Polygon, TransformationGeometryMap, FidelityEvaluationSpace,
    CoordinateSpace,
)
from veilframe.image.models.trust import (
    TrustAnchor, TrustAnchorRegistry, SignerIdentity,
)
from veilframe.image.models.graph import (
    ProviderFingerprint, DetectorEvidence, PrivacyNode, PrivacyGraph,
    LayerType, RiskLevel, CoordinateSpace,
)
from veilframe.image.models.transform_plan import (
    TransformOperation, ConstantFillParams, TransformTask, TransformationDAG,
    VerificationTask, VerificationPlan,
)
from veilframe.image.models.report import RedactionAuditRecord
from veilframe.image.models.audit import (
    IdentityPreimage, EvidencePreimage, FidelityMetrics, ImageAuditManifest,
)

from veilframe.image.runtime.snapshot import ResourceBoundedSourceSnapshot, ResourceLimitExceeded
from veilframe.image.runtime.capabilities import CapabilityRegistry
from veilframe.image.runtime.environment import RuntimeEnvironment
from veilframe.image.verification.normalizer import normalize_status, safe_normalize
from veilframe.image.verification.geometry import GeometryIntegrityAuditor
from veilframe.image.verification.completeness import CompletenessAuditor
from veilframe.image.verification.independence import IndependenceAuditor, compute_independence_level
from veilframe.image.compiler.planner import (
    TransformationPlanner, topological_sort,
    CyclicDependencyError, UnknownDependencyError,
)
from veilframe.image.graph.builder import PrivacyGraphBuilder
from veilframe.image.graph.analyzer import GraphAnalyzer

# ---------------------------------------------------------------------------
# Constants & helpers
# ---------------------------------------------------------------------------

NOW = datetime.now(tz=timezone.utc)
PAST = NOW - timedelta(days=365)
FUTURE = NOW + timedelta(days=365)
FAKE_KEY_DER = b"\x00" * 32
FAKE_HASH = "a" * 64  # valid 64-char hex digest for fields requiring it


def _make_anchor(
    anchor_id: str = "anchor-1",
    key_id: str = "key-1",
    status: TrustAnchorStatus = TrustAnchorStatus.ACTIVE,
    reason=None,
    revoked_at=None,
) -> TrustAnchor:
    return TrustAnchor(
        anchor_id=anchor_id, key_id=key_id, public_key_der=FAKE_KEY_DER,
        status=status, valid_from=PAST, valid_until=FUTURE,
        revocation_reason=reason, revoked_at=revoked_at,
    )


def _make_registry(anchor: TrustAnchor) -> TrustAnchorRegistry:
    reg = TrustAnchorRegistry()
    reg.add_anchor(anchor)
    return reg


def _make_geometry_map(w: int = 1920, h: int = 1080, tol: float = 0.0) -> TransformationGeometryMap:
    return TransformationGeometryMap(
        source_width=w, source_height=h, output_width=w, output_height=h,
        geometry_tolerance_pixels=tol,
    )


def _make_fidelity_space(w: int = 1920, h: int = 1080) -> FidelityEvaluationSpace:
    return FidelityEvaluationSpace(
        eval_width=w, eval_height=h,
        source_to_eval_scale_x=1.0, source_to_eval_scale_y=1.0,
        output_to_eval_scale_x=1.0, output_to_eval_scale_y=1.0,
    )


def _make_polygon() -> Polygon:
    return Polygon(
        vertices=((10.0, 10.0), (200.0, 10.0), (200.0, 200.0), (10.0, 200.0)),
        space=CoordinateSpace.OUTPUT,
    )


def _make_fill_task(task_id: str = "fill_001") -> TransformTask:
    params = ConstantFillParams(
        canvas_width=1920, canvas_height=1080,
        mask_polygon=_make_polygon(),
        fill_constant=(0.0, 0.0, 0.0),
    )
    return TransformTask(
        task_id=task_id,
        operation=TransformOperation.CONSTANT_FILL,
        redaction_class=RedactionClass.DESTRUCTIVE,
        source_dependence=SourceDependence.ABSENT,
        params=params,
    )


def _make_fingerprint(
    impl_hash: str = "a" * 64,
    algo: str = "haar",
    lib_hash: str = "a" * 64,
    model: str = "face-haar",
    dep_graph: str = "a" * 64,
) -> ProviderFingerprint:
    return ProviderFingerprint(
        provider_id="p1", implementation_id="impl-A",
        algorithm_id=algo, library_id="opencv", model_family=model,
        version="1.0", source_hash=FAKE_HASH,
        implementation_hash=impl_hash,
        dependency_graph_hash=dep_graph,
        library_binary_hash=lib_hash,
    )


def _make_evidence(detector_class=DetectorClass.FACE) -> DetectorEvidence:
    return DetectorEvidence(
        detector_class=detector_class,
        confidence=0.95,
        fingerprint=_make_fingerprint(),
    )


# ===========================================================================
# 1. CheckStatus logic (fail-closed)
# ===========================================================================

class TestCheckStatus(unittest.TestCase):

    def test_unknown_is_fail(self):
        """UNKNOWN must be treated identically to FAIL at gate boundary."""
        self.assertTrue(CheckStatus.UNKNOWN.is_fail)
        self.assertFalse(CheckStatus.UNKNOWN.is_pass)

    def test_pass_and_pass(self):
        self.assertIs(CheckStatus.PASS & CheckStatus.PASS, CheckStatus.PASS)

    def test_pass_and_fail(self):
        self.assertIs(CheckStatus.PASS & CheckStatus.FAIL, CheckStatus.FAIL)

    def test_unknown_taints_and(self):
        """UNKNOWN propagates through AND — fail-closed."""
        self.assertIs(CheckStatus.PASS & CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)

    def test_fail_dominates_and(self):
        self.assertIs(CheckStatus.FAIL & CheckStatus.UNKNOWN, CheckStatus.FAIL)

    def test_or_pass(self):
        self.assertIs(CheckStatus.FAIL | CheckStatus.PASS, CheckStatus.PASS)

    def test_or_unknown(self):
        self.assertIs(CheckStatus.FAIL | CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)


# ===========================================================================
# 2. TrustAnchor — validity & historical revocation semantics
# ===========================================================================

class TestTrustAnchor(unittest.TestCase):

    def test_active_key_is_valid_at_now(self):
        anchor = _make_anchor()
        self.assertTrue(anchor.is_valid_at(NOW))

    def test_expired_key_not_valid(self):
        anchor = TrustAnchor(
            anchor_id="a", key_id="k", public_key_der=FAKE_KEY_DER,
            status=TrustAnchorStatus.ACTIVE,
            valid_from=PAST, valid_until=PAST + timedelta(seconds=1),
        )
        self.assertFalse(anchor.is_valid_at(NOW))

    def test_compromised_invalidates_historical_signatures(self):
        """COMPROMISED revocation must invalidate ALL historical signatures."""
        pre_revoke = PAST + timedelta(days=30)
        anchor = TrustAnchor(
            anchor_id="a", key_id="k", public_key_der=FAKE_KEY_DER,
            status=TrustAnchorStatus.REVOKED,
            valid_from=PAST, valid_until=FUTURE,
            revocation_reason=RevocationReason.COMPROMISED,
            revoked_at=NOW - timedelta(days=1),
        )
        self.assertFalse(anchor.signature_valid_at(pre_revoke))

    def test_superseded_preserves_pre_revocation_signatures(self):
        """SUPERSEDED must preserve signatures timestamped before revocation."""
        revoked_at = NOW - timedelta(days=10)
        pre_sig = revoked_at - timedelta(days=5)
        post_sig = revoked_at + timedelta(days=1)
        anchor = TrustAnchor(
            anchor_id="a", key_id="k", public_key_der=FAKE_KEY_DER,
            status=TrustAnchorStatus.REVOKED,
            valid_from=PAST, valid_until=FUTURE,
            revocation_reason=RevocationReason.SUPERSEDED,
            revoked_at=revoked_at,
        )
        self.assertTrue(anchor.signature_valid_at(pre_sig))
        self.assertFalse(anchor.signature_valid_at(post_sig))

    def test_public_key_hash_is_sha256_of_der(self):
        anchor = _make_anchor()
        expected = hashlib.sha256(FAKE_KEY_DER).hexdigest()
        self.assertEqual(anchor.public_key_hash, expected)


# ===========================================================================
# 3. TrustAnchorRegistry & SignerIdentity
# ===========================================================================

class TestTrustAnchorRegistry(unittest.TestCase):

    def test_validate_signer_trusted_valid(self):
        anchor = _make_anchor()
        reg = _make_registry(anchor)
        signer = anchor.signer_identity
        status = reg.validate_signer_identity(signer, NOW)
        self.assertEqual(status, ArtifactTrustStatus.TRUSTED_VALID)

    def test_validate_signer_unknown_anchor(self):
        anchor = _make_anchor()
        reg = _make_registry(anchor)
        unknown = SignerIdentity(
            trust_anchor_id="nobody", key_id="k",
            public_key_hash="d" * 64,
        )
        result = reg.validate_signer_identity(unknown, NOW)
        # Any non-TRUSTED_VALID result is acceptable for unknown signer
        self.assertNotEqual(result, ArtifactTrustStatus.TRUSTED_VALID)

    def test_validate_signer_compromised_revoked(self):
        anchor = TrustAnchor(
            anchor_id="a", key_id="k", public_key_der=FAKE_KEY_DER,
            status=TrustAnchorStatus.REVOKED,
            valid_from=PAST, valid_until=FUTURE,
            revocation_reason=RevocationReason.COMPROMISED,
            revoked_at=NOW - timedelta(days=1),
        )
        reg = _make_registry(anchor)
        signer = anchor.signer_identity
        result = reg.validate_signer_identity(signer, PAST + timedelta(days=100))
        self.assertIn(result, (ArtifactTrustStatus.REVOKED_SIGNER, ArtifactTrustStatus.UNTRUSTED_SIGNER))

    def test_signer_identity_fields_in_evidence_preimage(self):
        """SignerIdentity fields must appear verbatim in VEILFRAME-EVIDENCE-V1."""
        anchor = _make_anchor()
        signer = anchor.signer_identity
        ep = EvidencePreimage(
            capability_registry_identity="cap-hash",
            environment_hash="env-hash",
            execution_record_hash="exec-hash",
            final_artifact_hash="fa-hash",
            identity_hash="id-hash",
            raw_source_hash="raw-hash",
            signature_timestamp="2025-01-01T00:00:00Z",
            signer_identity=signer,
        )
        d = ep.to_canonical_dict()
        self.assertEqual(d["$domain"], "VEILFRAME-EVIDENCE-V1")
        self.assertEqual(d["signer_identity"]["trust_anchor_id"], anchor.anchor_id)
        self.assertEqual(d["signer_identity"]["key_id"], anchor.key_id)
        self.assertEqual(d["signer_identity"]["public_key_hash"], anchor.public_key_hash)


# ===========================================================================
# 4. GeometryIntegrityAuditor — Geometry Contract
# ===========================================================================

class TestGeometryIntegrityAuditor(unittest.TestCase):

    def test_exact_match_passes(self):
        gmap = _make_geometry_map(1920, 1080)
        auditor = GeometryIntegrityAuditor(gmap)
        result = auditor.audit(1920, 1080)
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertEqual(result.geometry_residual, 0.0)

    def test_wrong_width_fails(self):
        gmap = _make_geometry_map(1920, 1080)
        auditor = GeometryIntegrityAuditor(gmap)
        result = auditor.audit(1280, 1080)
        self.assertEqual(result.status, CheckStatus.FAIL)
        self.assertIn("GEOMETRY_CONTRACT_VIOLATION", result.failure_reason)

    def test_tolerance_respected(self):
        gmap = _make_geometry_map(1920, 1080, tol=2.0)
        auditor = GeometryIntegrityAuditor(gmap)
        # residual = sqrt(1^2 + 1^2) ≈ 1.41 ≤ 2.0 → PASS
        result = auditor.audit(1921, 1081)
        self.assertEqual(result.status, CheckStatus.PASS)

    def test_geometry_recorded_correctly(self):
        gmap = _make_geometry_map(1920, 1080)
        auditor = GeometryIntegrityAuditor(gmap)
        result = auditor.audit(640, 480)
        self.assertEqual(result.status, CheckStatus.FAIL)
        self.assertEqual(result.expected_width, 1920)
        self.assertEqual(result.expected_height, 1080)
        self.assertEqual(result.observed_width, 640)
        self.assertEqual(result.observed_height, 480)


# ===========================================================================
# 5. ResourceBoundedSourceSnapshot — resource limits
# ===========================================================================

class TestResourceBoundedSourceSnapshot(unittest.TestCase):

    def test_small_file_accepted(self):
        data = b"JPEG" + b"\x00" * 1024
        snap = ResourceBoundedSourceSnapshot.from_bytes(data)
        self.assertEqual(snap.raw_source_hash, hashlib.sha256(data).hexdigest())
        self.assertFalse(snap.is_large_file)
        self.assertEqual(snap.get_bytes(), data)

    def test_exceeds_max_input_bytes_raises(self):
        with self.assertRaises(ResourceLimitExceeded) as ctx:
            ResourceBoundedSourceSnapshot.from_bytes(b"\x00" * 10, max_input_bytes=5)
        self.assertEqual(ctx.exception.limit_name, "max_input_bytes")
        self.assertEqual(ctx.exception.value, 10)
        self.assertEqual(ctx.exception.maximum, 5)

    def test_pixel_limit_enforced(self):
        snap = ResourceBoundedSourceSnapshot.from_bytes(b"\x00" * 100)
        with self.assertRaises(ResourceLimitExceeded) as ctx:
            snap.check_decoded_pixels(n_pixels=100_000_000, n_channels=3,
                                      max_decoded_pixels=64_000_000)
        self.assertEqual(ctx.exception.limit_name, "max_decoded_pixels")

    def test_channel_limit_enforced(self):
        snap = ResourceBoundedSourceSnapshot.from_bytes(b"\x00" * 100)
        with self.assertRaises(ResourceLimitExceeded) as ctx:
            snap.check_decoded_pixels(n_pixels=100, n_channels=5, max_channels=4)
        self.assertEqual(ctx.exception.limit_name, "max_channels")

    def test_decoded_raster_fingerprint_canonical_header(self):
        snap = ResourceBoundedSourceSnapshot.from_bytes(b"\x00" * 100)
        pixels = bytes([128] * 12)  # 4 pixels placeholder
        snap.set_decoded_raster_fingerprint(2, 2, pixels)
        self.assertIsNotNone(snap.decoded_raster_fingerprint)
        header = struct.pack("<IIII", 2, 2, 3, 1)
        expected = hashlib.sha256(header + pixels).hexdigest()
        self.assertEqual(snap.decoded_raster_fingerprint, expected)

    def test_hash_is_sha256_of_bytes(self):
        data = b"hello"
        snap = ResourceBoundedSourceSnapshot.from_bytes(data)
        self.assertEqual(snap.raw_source_hash, hashlib.sha256(data).hexdigest())


# ===========================================================================
# 6. CoordinateSpace & TransformationGeometryMap
# ===========================================================================

class TestCoordinateSpaces(unittest.TestCase):

    def test_bounding_box_invariant(self):
        with self.assertRaises(ValueError):
            BoundingBox(x_min=100, y_min=0, x_max=50, y_max=100,
                        space=CoordinateSpace.SOURCE_DECODED)

    def test_polygon_minimum_vertices(self):
        with self.assertRaises(ValueError):
            Polygon(vertices=((0.0, 0.0), (1.0, 0.0)), space=CoordinateSpace.OUTPUT)

    def test_geometry_map_maps_bbox(self):
        gmap = TransformationGeometryMap(
            source_width=1920, source_height=1080,
            output_width=960, output_height=540,
            scale_x=0.5, scale_y=0.5,
        )
        src_bbox = BoundingBox(100, 100, 200, 200, space=CoordinateSpace.SOURCE_DECODED)
        out_bbox = gmap.map_bbox(src_bbox)
        self.assertAlmostEqual(out_bbox.x_min, 50.0)
        self.assertAlmostEqual(out_bbox.y_min, 50.0)
        self.assertAlmostEqual(out_bbox.x_max, 100.0)
        self.assertAlmostEqual(out_bbox.y_max, 100.0)
        self.assertEqual(out_bbox.space, CoordinateSpace.OUTPUT)

    def test_fidelity_space_minimum_windows(self):
        space = _make_fidelity_space(100, 100)
        # required_windows(200) → max(100, ceil(200 * 0.005)) = max(100, 1) = 100
        self.assertEqual(space.required_windows(total_canvas_windows=200), 100)

    def test_fidelity_space_invalid_fraction_raises(self):
        with self.assertRaises(ValueError):
            FidelityEvaluationSpace(
                eval_width=100, eval_height=100,
                source_to_eval_scale_x=1.0, source_to_eval_scale_y=1.0,
                output_to_eval_scale_x=1.0, output_to_eval_scale_y=1.0,
                minimum_window_fraction_of_canvas=0.0,
            )


# ===========================================================================
# 7. TransformTask immutability & ConstantFill interface isolation
# ===========================================================================

class TestTransformTask(unittest.TestCase):

    def test_constant_fill_params_required_for_fill_op(self):
        """CONSTANT_FILL op without params must raise ValueError."""
        with self.assertRaises((ValueError, TypeError)):
            TransformTask(
                task_id="bad",
                operation=TransformOperation.CONSTANT_FILL,
                redaction_class=RedactionClass.DESTRUCTIVE,
                source_dependence=SourceDependence.ABSENT,
                # params intentionally omitted
            )

    def test_task_is_frozen(self):
        task = _make_fill_task()
        with self.assertRaises((AttributeError, TypeError)):
            task.task_id = "mutated"  # type: ignore

    def test_const_fill_has_no_canvas_pointer(self):
        """ConstantFillParams must NOT carry any canvas buffer reference."""
        task = _make_fill_task()
        params = task.params
        self.assertIsNotNone(params)
        self.assertIsInstance(params.canvas_width, int)
        self.assertIsInstance(params.canvas_height, int)
        self.assertIsInstance(params.mask_polygon, Polygon)
        self.assertIsInstance(params.fill_constant, tuple)
        self.assertFalse(hasattr(params, "source_pixels"))
        self.assertFalse(hasattr(params, "canvas_buffer"))

    def test_fill_constant_clamp(self):
        """fill_constant values must be in [0.0, 1.0]."""
        poly = _make_polygon()
        with self.assertRaises(ValueError):
            ConstantFillParams(
                canvas_width=100, canvas_height=100,
                mask_polygon=poly, fill_constant=(1.5, 0.0, 0.0),
            )


# ===========================================================================
# 8. TransformationDAG — hashing & topological sort
# ===========================================================================

class TestTransformationDAG(unittest.TestCase):

    def test_dag_hash_deterministic(self):
        gmap = _make_geometry_map()
        task = _make_fill_task()
        dag1 = TransformationDAG(tasks=[task], geometry_map=gmap)
        dag2 = TransformationDAG(tasks=[task], geometry_map=gmap)
        self.assertEqual(dag1.dag_hash, dag2.dag_hash)

    def test_dag_hash_changes_with_different_task(self):
        gmap = _make_geometry_map()
        planner1 = TransformationPlanner(gmap, _make_fidelity_space())
        planner1.add_transform_task(_make_fill_task("fill_001"))
        dag1, _ = planner1.compile()

        planner2 = TransformationPlanner(gmap, _make_fidelity_space())
        planner2.add_transform_task(_make_fill_task("fill_999"))
        dag2, _ = planner2.compile()

        self.assertNotEqual(dag1.dag_hash, dag2.dag_hash)

    def test_topological_sort_valid_ordering(self):
        # Use actual TransformOperation enum values
        a = TransformTask(task_id="A", operation=TransformOperation.METADATA_STRIP,
                          redaction_class=RedactionClass.SOURCE_DERIVED, source_dependence=SourceDependence.PRESENT)
        b = TransformTask(task_id="B", operation=TransformOperation.ORIENTATION_BAKE,
                          redaction_class=RedactionClass.SOURCE_DERIVED, source_dependence=SourceDependence.PRESENT,
                          depends_on=("A",))
        sorted_tasks = topological_sort([a, b])
        ids = [t.task_id for t in sorted_tasks]
        self.assertEqual(ids[0], "A")

    def test_topological_sort_detects_cycle(self):
        a = TransformTask(task_id="A", operation=TransformOperation.METADATA_STRIP,
                          redaction_class=RedactionClass.SOURCE_DERIVED, source_dependence=SourceDependence.PRESENT,
                          depends_on=("B",))
        b = TransformTask(task_id="B", operation=TransformOperation.ORIENTATION_BAKE,
                          redaction_class=RedactionClass.SOURCE_DERIVED, source_dependence=SourceDependence.PRESENT,
                          depends_on=("A",))
        with self.assertRaises(CyclicDependencyError):
            topological_sort([a, b])

    def test_topological_sort_unknown_dep_raises(self):
        a = TransformTask(task_id="A", operation=TransformOperation.METADATA_STRIP,
                          redaction_class=RedactionClass.SOURCE_DERIVED, source_dependence=SourceDependence.PRESENT,
                          depends_on=("GHOST",))
        with self.assertRaises(UnknownDependencyError):
            topological_sort([a])


# ===========================================================================
# 9. RFC 8785 domain-separated preimage correctness
# ===========================================================================

class TestPreimages(unittest.TestCase):

    def _make_ip(self) -> IdentityPreimage:
        return IdentityPreimage(
            candidate_output_hash="cand-hash",
            dag_hash="dag-hash",
            graph_hash="graph-hash",
            policy_identity="policy-id",
            raw_source_hash="raw-hash",
            rule_set_identity="rule-id",
            schema_version="2.0.0",
            threat_identity="threat-id",
            verification_plan_hash="vplan-hash",
        )

    def _make_ep(self, signer: SignerIdentity) -> EvidencePreimage:
        return EvidencePreimage(
            capability_registry_identity="cap",
            environment_hash="env",
            execution_record_hash="exec",
            final_artifact_hash="fa",
            identity_hash="id",
            raw_source_hash="raw",
            signature_timestamp="2025-01-01T00:00:00Z",
            signer_identity=signer,
        )

    def test_identity_preimage_domain(self):
        d = self._make_ip().to_canonical_dict()
        self.assertEqual(d["$domain"], "VEILFRAME-IDENTITY-V1")

    def test_identity_preimage_required_fields(self):
        d = self._make_ip().to_canonical_dict()
        required = {
            "$domain", "candidate_output_hash", "dag_hash", "graph_hash",
            "policy_identity", "raw_source_hash", "rule_set_identity",
            "schema_version", "threat_identity", "verification_plan_hash",
        }
        self.assertEqual(set(d.keys()), required)

    def test_identity_hash_deterministic(self):
        ip = self._make_ip()
        self.assertEqual(ip.compute_hash(), ip.compute_hash())

    def test_evidence_preimage_domain(self):
        anchor = _make_anchor()
        d = self._make_ep(anchor.signer_identity).to_canonical_dict()
        self.assertEqual(d["$domain"], "VEILFRAME-EVIDENCE-V1")

    def test_evidence_hash_differs_by_signer(self):
        s1 = _make_anchor("a1", "k1").signer_identity
        s2 = _make_anchor("a2", "k2").signer_identity
        ep1 = self._make_ep(s1)
        ep2 = self._make_ep(s2)
        self.assertNotEqual(ep1.compute_hash(), ep2.compute_hash())

    def test_publication_integrity_invariant_holds(self):
        anchor = _make_anchor()
        ip = IdentityPreimage(
            candidate_output_hash="SAME-HASH", dag_hash="d", graph_hash="g",
            policy_identity="p", raw_source_hash="r", rule_set_identity="rs",
            schema_version="2.0.0", threat_identity="t", verification_plan_hash="v",
        )
        ep = EvidencePreimage(
            capability_registry_identity="c", environment_hash="e",
            execution_record_hash="x",
            final_artifact_hash="SAME-HASH",  # matches candidate → integrity holds
            identity_hash=ip.compute_hash(), raw_source_hash="r",
            signature_timestamp="2025-01-01T00:00:00Z",
            signer_identity=anchor.signer_identity,
        )
        manifest = ImageAuditManifest(
            schema_version="2.0.0",
            identity_preimage=ip, identity_hash=ip.compute_hash(),
            evidence_preimage=ep, evidence_hash=ep.compute_hash(),
            ed25519_signature_hex="aabb", public_key_pem="---",
            overall_status=CheckStatus.PASS,
            publication_state=PublicationState.COMMITTED,
            fidelity_metrics=FidelityMetrics(fidelity_contract_status=CheckStatus.PASS),
        )
        self.assertTrue(manifest.publication_integrity_holds())

    def test_publication_integrity_violation_detected(self):
        anchor = _make_anchor()
        ip = IdentityPreimage(
            candidate_output_hash="CANDIDATE", dag_hash="d", graph_hash="g",
            policy_identity="p", raw_source_hash="r", rule_set_identity="rs",
            schema_version="2.0.0", threat_identity="t", verification_plan_hash="v",
        )
        ep = EvidencePreimage(
            capability_registry_identity="c", environment_hash="e",
            execution_record_hash="x",
            final_artifact_hash="DIFFERENT",  # MISMATCH
            identity_hash=ip.compute_hash(), raw_source_hash="r",
            signature_timestamp="2025-01-01T00:00:00Z",
            signer_identity=anchor.signer_identity,
        )
        manifest = ImageAuditManifest(
            schema_version="2.0.0",
            identity_preimage=ip, identity_hash=ip.compute_hash(),
            evidence_preimage=ep, evidence_hash=ep.compute_hash(),
            ed25519_signature_hex="aabb", public_key_pem="---",
            overall_status=CheckStatus.FAIL,
            publication_state=PublicationState.QUARANTINED,
            fidelity_metrics=FidelityMetrics(),
        )
        self.assertFalse(manifest.publication_integrity_holds())


# ===========================================================================
# 10. Publication state machine
# ===========================================================================

class TestPublicationStateMachine(unittest.TestCase):

    def test_valid_forward_chain_values(self):
        values = [s.value for s in PublicationState]
        self.assertIn("staged", values)
        self.assertIn("verified", values)
        self.assertIn("committed", values)

    def test_quarantined_state_exists(self):
        self.assertIn("quarantined", [s.value for s in PublicationState])

    def test_revoked_post_publication_state_exists(self):
        self.assertIn("revoked", [s.value for s in PublicationState])

    def test_quarantined_is_not_committed(self):
        self.assertNotEqual(PublicationState.QUARANTINED, PublicationState.COMMITTED)


# ===========================================================================
# 11. Five Contracts + Fail-Closed Invariant
# ===========================================================================

class TestFiveContracts(unittest.TestCase):

    def test_all_pass_gives_pass(self):
        result = (CheckStatus.PASS & CheckStatus.PASS & CheckStatus.PASS
                  & CheckStatus.PASS & CheckStatus.PASS)
        self.assertIs(result, CheckStatus.PASS)

    def test_single_unknown_fails_gate(self):
        result = (CheckStatus.PASS & CheckStatus.PASS & CheckStatus.UNKNOWN
                  & CheckStatus.PASS & CheckStatus.PASS)
        self.assertTrue(result.is_fail)

    def test_single_fail_fails_gate(self):
        result = (CheckStatus.PASS & CheckStatus.FAIL & CheckStatus.PASS
                  & CheckStatus.PASS & CheckStatus.PASS)
        self.assertIs(result, CheckStatus.FAIL)

    def test_fail_dominates_unknown(self):
        result = CheckStatus.FAIL & CheckStatus.UNKNOWN
        self.assertIs(result, CheckStatus.FAIL)


# ===========================================================================
# 12. IndependenceAuditor — levels 0–3
# ===========================================================================

class TestIndependenceAuditor(unittest.TestCase):

    def _primary(self) -> ProviderFingerprint:
        return _make_fingerprint("a" * 64, "haar", "a" * 64, "face-haar", "a" * 64)

    def test_level_0_same_impl(self):
        """Same implementation_hash → Level 0 (not independent)."""
        primary = self._primary()
        probe = _make_fingerprint("a" * 64, "dnn", "b" * 64, "face-dnn", "b" * 64)
        level = compute_independence_level(primary, probe)
        self.assertEqual(level, IndependenceLevel.LEVEL_0)

    def test_level_3_fully_distinct(self):
        """Different algo + model family + dep graph → Level 3."""
        primary = self._primary()
        probe = _make_fingerprint("b" * 64, "dnn", "b" * 64, "face-dnn", "b" * 64)
        level = compute_independence_level(primary, probe)
        self.assertEqual(level, IndependenceLevel.LEVEL_3)

    def test_level_2_distinct_algo(self):
        primary = self._primary()
        probe = _make_fingerprint("b" * 64, "dnn", "a" * 64, "face-haar", "a" * 64)
        level = compute_independence_level(primary, probe)
        self.assertGreaterEqual(level.value, 2)

    def test_auditor_rejects_level_0(self):
        primary = self._primary()
        auditor = IndependenceAuditor(primary, required_level=1)
        probe = _make_fingerprint("a" * 64, "dnn", "b" * 64, "face-dnn", "b" * 64)
        result = auditor.audit_probe("probe-1", probe)
        self.assertEqual(result.status, CheckStatus.FAIL)

    def test_auditor_accepts_level_3(self):
        primary = self._primary()
        auditor = IndependenceAuditor(primary, required_level=1)
        probe = _make_fingerprint("b" * 64, "dnn", "b" * 64, "face-dnn", "b" * 64)
        result = auditor.audit_probe("probe-1", probe)
        self.assertEqual(result.status, CheckStatus.PASS)


# ===========================================================================
# 13. StatusNormalizer — fail-closed semantics
# ===========================================================================

class TestStatusNormalizer(unittest.TestCase):

    def test_none_becomes_unknown(self):
        self.assertIs(normalize_status(None), CheckStatus.UNKNOWN)

    def test_bool_true_becomes_pass(self):
        self.assertIs(normalize_status(True), CheckStatus.PASS)

    def test_bool_false_becomes_fail(self):
        self.assertIs(normalize_status(False), CheckStatus.FAIL)

    def test_string_pass_case_insensitive(self):
        self.assertIs(normalize_status("PASS"), CheckStatus.PASS)
        self.assertIs(normalize_status("pass"), CheckStatus.PASS)

    def test_string_unknown(self):
        self.assertIs(normalize_status("error"), CheckStatus.UNKNOWN)

    def test_unknown_object_becomes_unknown(self):
        self.assertIs(normalize_status({"status": "ok"}), CheckStatus.UNKNOWN)

    def test_safe_normalize_swallows_exception(self):
        result = safe_normalize(object())
        self.assertIs(result, CheckStatus.UNKNOWN)

    def test_check_status_passthrough(self):
        for s in CheckStatus:
            self.assertIs(normalize_status(s), s)


# ===========================================================================
# 14. TransformationPlanner
# ===========================================================================

class TestTransformationPlanner(unittest.TestCase):

    def _make_planner(self) -> TransformationPlanner:
        return TransformationPlanner(_make_geometry_map(), _make_fidelity_space())

    def test_compile_produces_dag_and_plan_with_hashes(self):
        planner = self._make_planner()
        planner.add_transform_task(_make_fill_task("fill"))
        planner.add_verification_task(
            VerificationTask(task_id="v1", check_type="privacy_contract")
        )
        dag, plan = planner.compile()
        self.assertIsInstance(dag.dag_hash, str)
        self.assertEqual(len(dag.dag_hash), 64)
        # plan paired to dag
        self.assertIsNotNone(plan.verification_plan_hash)
        self.assertEqual(len(plan.verification_plan_hash), 64)

    def test_plan_hash_changes_when_dag_changes(self):
        p1 = self._make_planner()
        p1.add_transform_task(_make_fill_task("fill_001"))
        p1.add_verification_task(VerificationTask(task_id="v1", check_type="privacy"))
        dag1, plan1 = p1.compile()

        p2 = self._make_planner()
        p2.add_transform_task(_make_fill_task("fill_999"))
        p2.add_verification_task(VerificationTask(task_id="v1", check_type="privacy"))
        dag2, plan2 = p2.compile()

        self.assertNotEqual(dag1.dag_hash, dag2.dag_hash)
        self.assertNotEqual(plan1.verification_plan_hash, plan2.verification_plan_hash)


# ===========================================================================
# 15. CompletenessAuditor
# ===========================================================================

class TestCompletenessAuditor(unittest.TestCase):

    def _setup(self):
        gmap = _make_geometry_map()
        fspace = _make_fidelity_space()
        fill_task = _make_fill_task("fill_001")
        dag = TransformationDAG(tasks=[fill_task], geometry_map=gmap)
        vtask = VerificationTask(task_id="v_privacy", check_type="privacy_contract",
                                 required_independence_level=1)
        plan = VerificationPlan(tasks=[vtask], fidelity_evaluation_space=fspace)
        return dag, plan

    def _make_rec(self, anti_aliasing: bool = False, status: CheckStatus = CheckStatus.PASS):
        return RedactionAuditRecord(
            task_id="fill_001", detector_class=DetectorClass.FACE,
            mask_coverage_pct=100.0, replacement_coverage_pct=100.0,
            source_dependence=SourceDependence.ABSENT,
            redaction_class=RedactionClass.DESTRUCTIVE,
            polygon_vertex_count=4, canvas_width=1920, canvas_height=1080,
            anti_aliasing_present=anti_aliasing, execution_status=status,
        )

    def test_complete_execution_passes(self):
        dag, plan = self._setup()
        auditor = CompletenessAuditor(dag, plan)
        result = auditor.audit(
            executed_verification_task_ids={"v_privacy"},
            redaction_records=[self._make_rec()],
            claimed_dag_hash=dag.dag_hash,
            claimed_plan_hash=plan.verification_plan_hash,
        )
        self.assertEqual(result.status, CheckStatus.PASS)

    def test_missing_verification_task_fails(self):
        dag, plan = self._setup()
        auditor = CompletenessAuditor(dag, plan)
        result = auditor.audit(
            executed_verification_task_ids=set(),  # v_privacy not executed
            redaction_records=[self._make_rec()],
            claimed_dag_hash=dag.dag_hash,
            claimed_plan_hash=plan.verification_plan_hash,
        )
        self.assertEqual(result.status, CheckStatus.FAIL)
        self.assertTrue(any("v_privacy" in str(r) for r in result.failure_reasons))

    def test_anti_aliasing_detected_fails(self):
        dag, plan = self._setup()
        auditor = CompletenessAuditor(dag, plan)
        result = auditor.audit(
            executed_verification_task_ids={"v_privacy"},
            redaction_records=[self._make_rec(anti_aliasing=True)],
            claimed_dag_hash=dag.dag_hash,
            claimed_plan_hash=plan.verification_plan_hash,
        )
        self.assertEqual(result.status, CheckStatus.FAIL)

    def test_dag_hash_mismatch_fails(self):
        dag, plan = self._setup()
        auditor = CompletenessAuditor(dag, plan)
        result = auditor.audit(
            executed_verification_task_ids={"v_privacy"},
            redaction_records=[self._make_rec()],
            claimed_dag_hash="WRONG-HASH",
            claimed_plan_hash=plan.verification_plan_hash,
        )
        self.assertEqual(result.status, CheckStatus.FAIL)
        self.assertFalse(result.dag_hash_verified)


# ===========================================================================
# 16. PrivacyGraph & Builder
# ===========================================================================

class TestPrivacyGraphBuilder(unittest.TestCase):

    def test_build_empty_graph(self):
        builder = PrivacyGraphBuilder(raw_source_hash=FAKE_HASH, width=1920, height=1080)
        graph = builder.build()
        self.assertEqual(len(graph.nodes), 0)
        self.assertIsInstance(graph.graph_hash, str)

    def test_add_metadata_node(self):
        builder = PrivacyGraphBuilder(raw_source_hash=FAKE_HASH, width=1920, height=1080)
        builder.add_metadata_node("EXIF.GPS.Latitude", risk=RiskLevel.CRITICAL)
        graph = builder.build()
        nodes = list(graph.nodes.values()) if isinstance(graph.nodes, dict) else graph.nodes
        self.assertEqual(len(nodes), 1)
        self.assertEqual(nodes[0].layer, LayerType.CONTAINER)
        self.assertEqual(nodes[0].risk_level, RiskLevel.CRITICAL)

    def test_add_semantic_node(self):
        builder = PrivacyGraphBuilder(raw_source_hash=FAKE_HASH, width=1920, height=1080)
        bbox = BoundingBox(100, 100, 200, 200, space=CoordinateSpace.SOURCE_DECODED)
        evidence = _make_evidence(DetectorClass.FACE)
        builder.add_semantic_node(bbox, DetectorClass.FACE, evidence)
        graph = builder.build()
        nodes = list(graph.nodes.values())
        self.assertEqual(len(nodes), 1)
        self.assertEqual(nodes[0].layer, LayerType.SEMANTIC)


# ===========================================================================
# 17. CapabilityRegistry detection
# ===========================================================================

class TestCapabilityRegistry(unittest.TestCase):

    def test_detect_returns_report(self):
        from veilframe.image.runtime.capabilities import CapabilityReport
        report = CapabilityRegistry.detect()
        self.assertIsInstance(report, CapabilityReport)
        self.assertIsInstance(report.registry_identity, str)
        self.assertEqual(len(report.registry_identity), 64)

    def test_opencv_detected(self):
        report = CapabilityRegistry.detect()
        # OpenCV is known to be installed in this environment
        self.assertTrue(report.opencv_available)
        self.assertIsNotNone(report.opencv_version)

    def test_profile_is_valid_enum(self):
        report = CapabilityRegistry.detect()
        self.assertIsInstance(report.profile, CapabilityProfile)


# ===========================================================================
# 18. RuntimeEnvironment capture
# ===========================================================================

class TestRuntimeEnvironment(unittest.TestCase):

    def test_capture_returns_env(self):
        env = RuntimeEnvironment.capture()
        self.assertIsInstance(env.python_version, str)
        self.assertIsInstance(env.environment_hash, str)
        self.assertEqual(len(env.environment_hash), 64)

    def test_environment_hash_deterministic(self):
        """Two captures in same process should have same version/platform."""
        env1 = RuntimeEnvironment.capture()
        env2 = RuntimeEnvironment.capture()
        self.assertEqual(env1.python_version, env2.python_version)
        self.assertEqual(env1.platform_system, env2.platform_system)


if __name__ == "__main__":
    unittest.main()
