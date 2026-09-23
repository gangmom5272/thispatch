import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT / "batch"))

import qwen_backfill as backfill
import qwen_worker as worker
from common import PATCH_CHANGE_SCHEMA, PATCH_CHUNK_SCHEMA
from qwen_prompt import MODEL_TAG
from test_qwen_grounding import TEXT, fact


class ApplyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.date = "2026-09-23"
        self.state = self.root / "state/done.jsonl"
        self.out_dir = lambda name, date: self.root / name / ("dt=" + date)
        self.patch_path = patch.object(backfill, "out_dir", self.out_dir)
        self.patch_state = patch.object(backfill, "STATE", self.state)
        self.patch_path.start()
        self.patch_state.start()
        self.addCleanup(self.patch_path.stop)
        self.addCleanup(self.patch_state.stop)
        self.chunk_dir = self.out_dir("embeddings/patch_chunk", self.date)
        self.change_dir = self.out_dir("embeddings/patch_change", self.date)
        self.chunk_dir.mkdir(parents=True)
        self.change_dir.mkdir(parents=True)
        self.chunk_file = self.chunk_dir / "part-00000.parquet"
        self.change_file = self.change_dir / "part-00000.parquet"
        pq.write_table(pa.Table.from_pylist([
            {"gid": "1", "seq": 1, "text": "title: Patch | text: Context: Hatchet\nChange: " + TEXT,
             "extraction_status": "succeeded", "embedding_status": "skipped", "embedding": None,
             "embedding_model": None, "model_version": "rule-v2", "processed_at": 1},
        ], schema=PATCH_CHUNK_SCHEMA), self.chunk_file)
        pq.write_table(pa.Table.from_pylist([
            {"gid": "1", "seq": 1, "change_seq": 1, "change_type": "modify", "direction": "unknown",
             "target_type": "unknown", "target": None, "attribute": None,
             "evidence_quote": TEXT, "validation_status": "valid", "model_version": "rule-v2"},
        ], schema=PATCH_CHANGE_SCHEMA), self.change_file)
        self.raw_dir = self.root / "worker-results"
        self.raw_dir.mkdir()

    def write_result(self, **updates):
        record = {"gid": "1", "seq": 1, "title": "Patch", "context": "Hatchet", "text": TEXT,
                  "input_hash": worker.input_hash("Patch", "Hatchet", TEXT), "model": MODEL_TAG,
                  "changes": [fact()], **updates}
        (self.raw_dir / "facts-shard-01.jsonl").write_text(json.dumps(record), encoding="utf-8")

    def test_valid_result_keeps_parquet_schema_and_stores_only_fact_evidence(self):
        self.write_result()
        backfill.apply(self.date, [self.raw_dir])
        changes = pq.read_table(self.change_file).to_pylist()
        self.assertEqual(len(changes), 1)
        self.assertEqual(changes[0]["evidence_quote"], "Reduced damage from 100 to 80.")
        self.assertEqual(changes[0]["model_version"], MODEL_TAG)
        self.assertTrue(pq.read_schema(self.change_file).equals(PATCH_CHANGE_SCHEMA, check_metadata=False))
        self.assertTrue(pq.read_schema(self.chunk_file).equals(PATCH_CHUNK_SCHEMA, check_metadata=False))
        self.assertEqual(pq.read_table(self.chunk_file)["text"][0].as_py(), "title: Patch | text: Context: Hatchet\nChange: " + TEXT)
        self.assertEqual(backfill.load_done(self.date), {"1:1": worker.input_hash("Patch", "Hatchet", TEXT)})

    def test_invalid_fact_preserves_existing_files_and_does_not_mark_done(self):
        self.write_result(changes=[fact(), fact(values="invented")])
        before = (self.chunk_file.read_bytes(), self.change_file.read_bytes())
        with self.assertRaises(ValueError):
            backfill.apply(self.date, [self.raw_dir])
        self.assertEqual(before, (self.chunk_file.read_bytes(), self.change_file.read_bytes()))
        self.assertFalse(self.state.exists())

    def test_stale_context_cannot_replace_a_current_chunk(self):
        self.write_result(context="Rifle", input_hash=worker.input_hash("Patch", "Rifle", TEXT), changes=[fact(target="Rifle")])
        before = self.change_file.read_bytes()
        with self.assertRaises(ValueError):
            backfill.apply(self.date, [self.raw_dir])
        self.assertEqual(self.change_file.read_bytes(), before)

    def test_previous_model_results_are_not_relabelled_as_new_model(self):
        self.write_result(model="qwen3.5-9b-q4km/service-facts-2")
        before = self.change_file.read_bytes()
        backfill.apply(self.date, [self.raw_dir])
        self.assertEqual(self.change_file.read_bytes(), before)
        self.assertFalse(self.state.exists())

    def test_previous_version_done_state_does_not_skip_new_extraction(self):
        self.state.parent.mkdir()
        self.state.write_text(json.dumps({"key": "1:1", "dt": self.date, "model": "old"}), encoding="utf-8")
        news = pd.DataFrame([{"gid": "1", "appid": 1, "published_ts": 1}])
        with patch.object(backfill, "read_news", return_value=news), patch.object(backfill, "APP_RANK", self.root / "missing.csv"):
            selected = backfill.select_todo(self.date, include_skipped=True)
        self.assertEqual(selected["gid"].tolist(), ["1"])

    def test_same_version_done_state_for_another_input_does_not_skip(self):
        self.state.parent.mkdir()
        self.state.write_text(json.dumps({"key": "1:1", "dt": self.date, "model": MODEL_TAG,
                                          "input_hash": worker.input_hash("Patch", "Rifle", TEXT)}), encoding="utf-8")
        news = pd.DataFrame([{"gid": "1", "appid": 1, "published_ts": 1}])
        with patch.object(backfill, "read_news", return_value=news), patch.object(backfill, "APP_RANK", self.root / "missing.csv"):
            self.assertEqual(backfill.select_todo(self.date, include_skipped=True)["gid"].tolist(), ["1"])

    def test_cached_worker_results_are_applied_without_a_new_model_call(self):
        todo = pd.DataFrame([{"gid": "1"}])
        with patch.object(sys, "argv", ["qwen_backfill.py", "--dt", self.date]), \
                patch.object(backfill, "select_todo", return_value=todo), \
                patch.object(backfill, "write_shards"), \
                patch.object(worker, "run", return_value=0), \
                patch.object(backfill, "apply", return_value=1) as apply_results:
            backfill.main()
        apply_results.assert_called_once_with(self.date, [self.out_dir("embeddings/qwen_raw", self.date)])


if __name__ == "__main__":
    unittest.main()
