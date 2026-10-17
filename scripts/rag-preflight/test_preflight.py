"""Boundary checks without model inference, API calls or application databases."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import preflight as rag
from download_model import verify_file


class PreflightBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.section = {"id": "reviewed", "title": "自造章节", "page": 7,
            "start": "本段开始", "end": "下一章节", "contains": ["直营门店", "授权代理"]}
        self.page = "页眉\n本段开始，公司通过直营门店与授权代理销售。" + "质量管理😀。" * 16 + "下一章节\n页脚"

    def test_chunks_round_trip_actual_page_offsets_and_keep_one_page(self):
        rows = rag.split_section(self.page, self.section, size=40, overlap=8)
        self.assertGreater(len(rows), 1)
        for row in rows:
            self.assertEqual(row["text"], rag.compact(self.page)[row["charStart"]:row["charEnd"]])
            self.assertEqual(row["charUnit"], "UNICODE_CODE_POINT")
            self.assertEqual(row["pdfPage"], 7)
            self.assertLessEqual(len(row["text"]), 40)
        for left, right in zip(rows, rows[1:]):
            self.assertEqual(left["text"][-8:], right["text"][:8])

    def test_duplicate_missing_and_reversed_boundaries_fail_closed(self):
        for text in [self.page + "本段开始", self.page.replace("本段开始", "其他段落"), "下一章节" + self.page.replace("下一章节", "")]:
            with self.assertRaises(ValueError):
                rag.extract_section(text, self.section)

    def test_bad_text_and_changed_identity_are_not_accepted(self):
        for text in [self.page.replace("直营门店", "\ufffd"), self.page.replace("授权代理", "其他信息")]:
            with self.assertRaises(ValueError):
                rag.extract_section(text, self.section)

    def test_invalid_overlap_is_rejected(self):
        with self.assertRaises(ValueError):
            rag.split_section(self.page, self.section, size=8, overlap=8)

    def test_dimension_nan_and_zero_vectors_are_rejected(self):
        for values in [[[1, 2]], [[float("nan"), 1, 0]], [[0, 0, 0]]]:
            with self.assertRaises(ValueError):
                rag.checked_vectors(values, 1, 3)
        self.assertAlmostEqual(float((rag.checked_vectors([[3, 4, 0]], 1, 3) ** 2).sum()), 1.0)

    def test_explicit_stock_version_and_embedding_identity_are_required(self):
        with self.assertRaises(ValueError):
            rag.bound_filter({"symbol": "SAMPLE-A"}, "model-version")
        scope = {"symbol": "SAMPLE-A", "reportVersion": "version-a"}
        fields = {item.key: item.match.value for item in rag.bound_filter(scope, "model-version").must}
        self.assertEqual(fields, {**scope, "embeddingVersion": "model-version", "status": "AVAILABLE", "corpus": "REPORT"})

    def test_forged_index_text_and_locations_are_rejected(self):
        row = rag.synthetic_passages()[0]
        canonical = {row["id"]: row}
        for key, value in [("text", "改写的正文"), ("pdfPage", 99), ("sourceUrl", "https://untrusted.example")]:
            changed = {**row, key: value}
            with self.assertRaises(ValueError):
                rag.validate_hit(SimpleNamespace(id=row["id"], payload=changed, score=0.9), canonical)
        hit = rag.validate_hit(SimpleNamespace(id=row["id"], payload=copy.deepcopy(row), score=0.9), canonical)
        self.assertEqual(hit["text"], row["text"])

    def test_unknown_point_and_corrupt_canonical_hash_are_rejected(self):
        row = rag.synthetic_passages()[0]
        with self.assertRaises(ValueError):
            rag.validate_hit(SimpleNamespace(id="unknown", payload=row, score=1), {row["id"]: row})
        row["textSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            rag.validate_hit(SimpleNamespace(id=row["id"], payload=row, score=1), {row["id"]: row})

    def test_source_directory_traversal_is_rejected(self):
        with self.assertRaises(ValueError):
            rag.source_path({"file": "../outside.pdf", "sha256": "0" * 64})

    def test_model_assets_check_both_bytes_and_pinned_content(self):
        # TemporaryDirectory is a uniquely created workspace child, checked before cleanup.
        with tempfile.TemporaryDirectory(prefix="rag-asset-test-", dir=rag.ROOT / "target") as folder:
            self.assertEqual(Path(folder).resolve().parent, (rag.ROOT / "target").resolve())
            path = Path(folder) / "model.bin"
            path.write_bytes(b"reviewed model")
            spec = {"size": 14, "sha256": hashlib.sha256(b"reviewed model").hexdigest()}
            verify_file(path, spec)
            path.write_bytes(b"changed model!")
            with self.assertRaises(ValueError):
                verify_file(path, spec)

    def test_changed_report_version_is_rejected_before_parsing(self):
        with tempfile.TemporaryDirectory(prefix="rag-source-test-", dir=rag.ROOT / "target") as folder:
            self.assertEqual(Path(folder).resolve().parent, (rag.ROOT / "target").resolve())
            path = Path(folder) / ".local/report-source/pdfs/source.pdf"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"reviewed source bytes")
            source = {"file": "source.pdf", "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            with patch.object(rag, "ROOT", Path(folder)):
                self.assertEqual(rag.source_path(source), path.resolve())
                path.write_bytes(b"a different document version")
                with self.assertRaises(ValueError):
                    rag.source_path(source)

    def test_plan_needs_no_pdf_weights_inference_or_network(self):
        code = """
import sys, runpy
from pathlib import Path
def guard(event, args):
    if event == 'socket.connect': raise RuntimeError('Network forbidden')
    if event == 'open' and str(args[0]).lower().endswith(('.onnx', '.pdf')): raise RuntimeError('Model and PDF access forbidden')
sys.addaudithook(guard)
sys.path.insert(0, str(Path(sys.argv[1]).parent))
sys.argv = [sys.argv[1], '--stage', 'plan']
runpy.run_path(sys.argv[0], run_name='__main__')
"""
        result = subprocess.run([sys.executable, "-X", "utf8", "-c", code, str(rag.HERE / "preflight.py")],
            capture_output=True, text=True, encoding="utf-8", check=False, timeout=10,
            creationflags=0x08000000 if sys.platform == "win32" else 0)
        self.assertEqual(result.returncode, 0, result.stderr)
        plan = json.loads(result.stdout)
        self.assertEqual(plan["paidApiCalls"], 0)
        self.assertEqual(plan["applicationDatabaseWrites"], 0)
        self.assertEqual(plan["dimensions"], 512)

    def test_memory_measurement_is_optional_and_does_not_overflow(self):
        measured = rag.peak_memory()
        self.assertTrue(measured is None or measured > 0)


if __name__ == "__main__":
    unittest.main()
