"""
tests/image/test_fidelity_and_gate.py — Tests for RegionFidelityEngine, BoundaryAuditor, and ImageQualityGate.
"""

import unittest
import numpy as np

from veilframe.image.models.status import CheckStatus, PublicationState
from veilframe.image.models.coordinates import TransformationGeometryMap, Polygon
from veilframe.image.fidelity.region_fidelity import RegionFidelityEngine
from veilframe.image.fidelity.boundary_audit import BoundaryAuditor
from veilframe.image.verification.geometry import GeometryIntegrityAuditor
from veilframe.image.verification.completeness import CompletenessAuditResult
from veilframe.image.redteam.engine import PrivacyAttackResult
from veilframe.image.gate.image_gate import ImageQualityGate


class TestFidelityAndGate(unittest.TestCase):

    def test_region_fidelity_exact_match(self):
        arr1 = np.ones((64, 64, 3), dtype=np.float32) * 0.5
        arr2 = np.ones((64, 64, 3), dtype=np.float32) * 0.5
        mask = np.zeros((64, 64), dtype=bool)
        mask[10:20, 10:20] = True  # 100 pixels redacted

        engine = RegionFidelityEngine()
        result = engine.compute(arr1, arr2, mask)
        self.assertEqual(result.status, CheckStatus.PASS)
        self.assertAlmostEqual(result.ssim, 1.0, places=3)
        self.assertGreaterEqual(result.psnr_db, 99.0)
        self.assertAlmostEqual(result.mae, 0.0, places=4)

    def test_region_fidelity_rejects_distortion(self):
        arr1 = np.ones((64, 64, 3), dtype=np.float32) * 0.5
        arr2 = np.zeros((64, 64, 3), dtype=np.float32)  # severely distorted
        mask = np.zeros((64, 64), dtype=bool)

        engine = RegionFidelityEngine()
        result = engine.compute(arr1, arr2, mask)
        self.assertEqual(result.status, CheckStatus.FAIL)

    def test_boundary_auditor_zero_leakage(self):
        orig = np.ones((50, 50, 3), dtype=np.float32)
        san = np.ones((50, 50, 3), dtype=np.float32)
        san[10:30, 10:30, :] = 0.0  # clean fill inside polygon
        mask = np.zeros((50, 50), dtype=bool)
        mask[10:30, 10:30] = True

        auditor = BoundaryAuditor(fill_constant=(0.0, 0.0, 0.0))
        res = auditor.audit(orig, san, mask)
        self.assertEqual(res.status, CheckStatus.PASS)
        self.assertEqual(res.leaked_pixels, 0)
        self.assertEqual(res.unfilled_pixels, 0)
        self.assertTrue(res.fill_constant_verified)

    def test_quality_gate_all_pass_gives_verified(self):
        gate = ImageQualityGate()
        geo_map = TransformationGeometryMap(100, 100, 100, 100, 0.0)
        geo_res = GeometryIntegrityAuditor(geo_map).audit(100, 100)
        comp_res = CompletenessAuditResult(
            status=CheckStatus.PASS,
            dag_hash_verified=True,
            plan_hash_verified=True,
            executed_task_ids=frozenset(["t1", "t2"]),
            required_task_ids=frozenset(["t1", "t2"]),
            missing_task_ids=frozenset(),
            unexpected_task_ids=frozenset(),
            redaction_records_valid=True,
        )
        rt_res = PrivacyAttackResult(
            overall_status=CheckStatus.PASS, probes_run=7, probes_failed=0
        )
        fid_engine = RegionFidelityEngine()
        fid_res = fid_engine.compute(
            np.ones((50, 50, 3), dtype=np.float32),
            np.ones((50, 50, 3), dtype=np.float32),
            np.zeros((50, 50), dtype=bool),
        )

        verdict = gate.evaluate(
            geometry_result=geo_res,
            completeness_result=comp_res,
            red_team_result=rt_res,
            independence_status=CheckStatus.PASS,
            fidelity_result=fid_res,
        )

        self.assertEqual(verdict.overall_status, CheckStatus.PASS)
        self.assertEqual(verdict.publication_state, PublicationState.VERIFIED)

    def test_quality_gate_fails_closed_on_any_failure(self):
        gate = ImageQualityGate()
        geo_map = TransformationGeometryMap(100, 100, 100, 100, 0.0)
        geo_res = GeometryIntegrityAuditor(geo_map).audit(100, 100)
        comp_res = CompletenessAuditResult(
            status=CheckStatus.FAIL,
            dag_hash_verified=True,
            plan_hash_verified=True,
            executed_task_ids=frozenset(["t1"]),
            required_task_ids=frozenset(["t1", "t2"]),
            missing_task_ids=frozenset(["t2"]),
            unexpected_task_ids=frozenset(),
            redaction_records_valid=False,
            failure_reasons=["Missing task"],
        )
        rt_res = PrivacyAttackResult(
            overall_status=CheckStatus.PASS, probes_run=7, probes_failed=0
        )

        verdict = gate.evaluate(
            geometry_result=geo_res,
            completeness_result=comp_res,
            red_team_result=rt_res,
            independence_status=CheckStatus.PASS,
            fidelity_result=None,
        )

        self.assertEqual(verdict.overall_status, CheckStatus.FAIL)
        self.assertEqual(verdict.publication_state, PublicationState.QUARANTINED)


if __name__ == "__main__":
    unittest.main()
