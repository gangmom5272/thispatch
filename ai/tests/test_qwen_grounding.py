import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT / "batch"))

import qwen_worker as worker
import qwen_backfill as backfill
from qwen_prompt import MESSAGES, MODEL_TAG


TEXT = "Reduced damage from 100 to 80. Increased cooldown from 5 to 10 seconds."


def fact(**updates):
    return {"target": "Hatchet", "target_type": "weapon", "attribute": "damage", "action": "decrease",
            "values": "from 100 to 80", "conditions": [], "source_sentence": "Reduced damage from 100 to 80.", **updates}


class GroundingTest(unittest.TestCase):
    def test_stored_examples_from_three_games_keep_exact_evidence(self):
        fixture = json.loads((AI_ROOT / "tests/fixtures/patch_context_examples.json").read_text(encoding="utf-8"))
        self.assertEqual(len({item["game"] for item in fixture["examples"]}), 3)
        for item in fixture["examples"]:
            with self.subTest(game=item["game"]):
                changes = worker.validate_changes([item["reference"]], item["title"], item["context"], item["text"])
                self.assertEqual(changes[0]["source_sentence"], item["text"])

    def test_target_can_come_from_parent_heading_without_game_name_dictionary(self):
        result = worker.validate_changes([fact()], "Patch 1", "Huntress / Hatchet", TEXT)
        self.assertEqual(result[0]["target"], "Hatchet")
        self.assertEqual(backfill.facts_to_rows("1", 1, "Huntress / Hatchet", TEXT, result)[0]["evidence_quote"],
                         "Reduced damage from 100 to 80.")

    def test_sibling_fact_values_attributes_targets_and_conditions_are_rejected(self):
        for updates in [{"values": "from 5 to 10 seconds"}, {"attribute": "cooldown"},
                        {"target": "cooldown"}, {"conditions": ["10 seconds"]}]:
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                worker.validate_changes([fact(**updates)], "", "Hatchet", TEXT)

    def test_generic_name_is_not_a_grounding_exemption(self):
        with self.assertRaises(ValueError):
            worker.validate_changes([fact(target="player", target_type="player")], "", "Hatchet", TEXT)

    def test_unstated_target_stays_unknown(self):
        result = worker.validate_changes([fact(target="", target_type="unknown")], "", "", TEXT)
        self.assertEqual(result[0]["target_type"], "unknown")
        with self.assertRaises(ValueError):
            worker.validate_changes([fact(target="", target_type="weapon")], "", "", TEXT)

    def test_original_spacing_is_restored_and_conditions_are_unique(self):
        text = "Reduced damage from 100 to 80."
        result = worker.validate_changes([fact(conditions=["PvP only", "PvP only"])], "", "Hatchet\nPvP  only", text)
        self.assertEqual(result[0]["conditions"], ["PvP  only"])
        self.assertEqual(worker.source_excerpt("from 100 to 80", "from 100\n to 80"), "from 100\n to 80")

    def test_typographic_quotes_match_but_return_the_original_source(self):
        for quote_group in ("'‘’", '\"“”'):
            for requested_quote in quote_group:
                for source_quote in quote_group:
                    requested = f"the {requested_quote}star{requested_quote} display"
                    source = f"Fixed the {source_quote}star{source_quote} display."
                    with self.subTest(requested=requested, source=source):
                        self.assertEqual(worker.source_excerpt(requested, source),
                                         f"the {source_quote}star{source_quote} display")
        self.assertEqual(worker.source_excerpt("ship's bow", "the ship’s bow"), "ship’s bow")

    def test_quote_matching_does_not_relax_other_characters(self):
        for requested, source in [("'star'", '"star"'), ("'star'", "star"),
                                  ("ship's", "ships"), ("5'", "5′"),
                                  ("-5%", "−5%"), ("-5%", "—5%"),
                                  ("5-10", "5–10"), ("RN Italia - display", "RN Italia — display"),
                                  ("damage", "Damage"), ("50%", "５0%")]:
            with self.subTest(requested=requested, source=source):
                self.assertIsNone(worker.source_excerpt(requested, source))

    def test_partial_words_numbers_and_lost_signs_do_not_count_as_evidence(self):
        for value, source in [("5", "15"), ("5", "-5"), ("5", "5.5"), ("5", "5%"),
                              ("20%", "-20%"), ("bow", "crossbow"), ("player", "players")]:
            with self.subTest(value=value, source=source):
                self.assertIsNone(worker.source_excerpt(value, source))

    def test_invalid_fact_does_not_return_partial_chunk(self):
        with self.assertRaises(ValueError):
            backfill.facts_to_rows("1", 1, "Hatchet", TEXT, [fact(), fact(values="999")])

    def test_korean_particles_do_not_hide_an_exact_source_term(self):
        text = "산탄총의 피해량을 100에서 120으로 증가시켰습니다."
        change = fact(target="산탄총", attribute="피해량", action="increase", values="100에서 120으로", source_sentence=text)
        result = worker.validate_changes([change], "", "", text)
        self.assertEqual(result[0]["target"], "산탄총")
        self.assertEqual(result[0]["attribute"], "피해량")

    def test_numeric_direction_must_match_an_explicit_transition(self):
        with self.assertRaises(ValueError):
            worker.validate_changes([fact(action="increase")], "", "Hatchet", TEXT)
        # 감소를 하향/약화로 해석하지 않으며 숫자가 없는 정성적 변경도 허용한다.
        text = "Reduced cooldown slightly."
        self.assertEqual(worker.validate_changes([fact(attribute="cooldown", values=None, source_sentence=text)],
                                                "", "Hatchet", text)[0]["action"], "decrease")

    def test_malformed_chunk_is_not_silently_treated_as_empty_text(self):
        for text in ["missing header", "title: Patch | text: Context: heading without body separator"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                worker.split_text(text)

    def test_few_shot_examples_follow_the_new_contract(self):
        for index in (1, 3, 5):
            source = json.loads(MESSAGES[index]["content"])[0]
            response = json.loads(MESSAGES[index + 1]["content"])["items"][0]
            self.assertEqual(len(worker.validate_changes(response["changes"], "", source["context"], source["text"])), 1)

    @patch("qwen_worker.urllib.request.urlopen")
    def test_response_must_include_each_requested_id_once(self, request):
        inputs = [{"id": "a", "context": "Hatchet", "text": TEXT}, {"id": "b", "context": "Hatchet", "text": TEXT}]
        for returned in [[{"id": "a", "changes": [fact()]}],
                         [{"id": "a", "changes": []}, {"id": "a", "changes": []}],
                         [{"id": "a", "changes": []}, {"id": "x", "changes": []}]]:
            request.return_value = io.BytesIO(json.dumps({"message": {"content": json.dumps({"items": returned})}}).encode())
            with self.subTest(returned=returned), self.assertRaises(ValueError):
                worker.call_qwen("http://test", inputs)

    @patch("qwen_worker.urllib.request.urlopen")
    def test_http_response_is_grounded_before_becoming_success(self, request):
        invalid = {"items": [{"id": "a", "changes": [fact(values="from 5 to 10 seconds")]}]}
        request.return_value = io.BytesIO(json.dumps({"message": {"content": json.dumps(invalid)}}).encode())
        with self.assertRaises(ValueError):
            worker.call_qwen("http://test", [{"id": "a", "context": "Hatchet", "text": TEXT}])


@unittest.skipUnless(os.environ.get("RUN_QWEN_LIVE_TEST") == "1", "실제 Qwen 모델은 별도 실행")
class LiveQwenTest(unittest.TestCase):
    def test_real_game_examples_keep_source_and_change_kind(self):
        fixture = json.loads((AI_ROOT / "tests/fixtures/patch_context_examples.json").read_text(encoding="utf-8"))
        examples = fixture["examples"]
        # 운영 워커의 묶음 실패 → 개별 재시도까지 검증한다. 첫 게임의 오류가 다른
        # 게임의 결과 확인을 막지 않도록 저장된 결과를 게임별 subTest로 확인한다.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shard = root / "live.jsonl"
            shard.write_text("\n".join(json.dumps({"key": item["id"] + ":1", "gid": item["id"], "seq": 1,
                "text": "title: " + item["title"] + " | text: " + item["text"]}, ensure_ascii=False)
                for item in examples), encoding="utf-8")
            worker.run(shard, root / "out")
            actual = {}
            for line in (root / "out/facts-live.jsonl").read_text(encoding="utf-8").splitlines():
                record = json.loads(line)
                actual[record["gid"]] = record["changes"]
        for item in examples:
            with self.subTest(game=item["game"]):
                self.assertIn(item["id"], actual, "No validated result after individual retry")
                changes = actual[item["id"]]
                self.assertTrue(changes, "An explicit change must not disappear")
                checks = item["semantic_checks"]
                for change in changes:
                    self.assertIn(change["source_sentence"], item["text"])
                    self.assertIn(change["action"], checks["allowed_actions"])
                    self.assertNotIn(change["target_type"], checks["forbidden_target_types"])


class WorkerIsolationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.shard = self.root / "shard-01.jsonl"

    def write_shard(self, contexts):
        self.shard.write_text("".join(json.dumps({"key": f"{index}:1", "gid": str(index), "seq": 1,
            "text": f"title: Patch | text: Context: {context}\nChange: {TEXT}"}) + "\n"
            for index, context in enumerate(contexts)), encoding="utf-8")

    @patch("qwen_worker.call_qwen")
    def test_same_body_under_different_headings_is_not_reused(self, call):
        self.write_shard(["Hatchet", "Rifle", "Hatchet"])
        call.side_effect = lambda url, items: {item["id"]: [fact(target=item["context"])] for item in items}
        self.assertEqual(worker.run(self.shard, self.root / "out", log=lambda message: None), 3)
        self.assertEqual(len(call.call_args.args[1]), 2)
        records = [json.loads(line) for line in (self.root / "out/facts-shard-01.jsonl").read_text().splitlines()]
        self.assertEqual({row["gid"]: row["changes"][0]["target"] for row in records}, {"0": "Hatchet", "1": "Rifle", "2": "Hatchet"})
        call.reset_mock()
        self.assertEqual(worker.run(self.shard, self.root / "out", log=lambda message: None), 0)
        call.assert_not_called()
        self.write_shard(["Dagger", "Rifle", "Hatchet"])
        self.assertEqual(worker.run(self.shard, self.root / "out", log=lambda message: None), 1)

    def test_title_and_prompt_version_are_part_of_reuse_identity(self):
        self.assertNotEqual(worker.input_hash("Patch A", "Hatchet", TEXT), worker.input_hash("Patch B", "Hatchet", TEXT))
        previous = worker.input_hash("Patch A", "Hatchet", TEXT)
        with patch.object(worker, "MODEL_TAG", "another-version"):
            self.assertNotEqual(previous, worker.input_hash("Patch A", "Hatchet", TEXT))

    @patch("qwen_worker.call_qwen", side_effect=ValueError("invalid model response"))
    def test_failed_batch_and_single_retry_write_no_success_records(self, call):
        self.write_shard(["Hatchet"])
        self.assertEqual(worker.run(self.shard, self.root / "out", log=lambda message: None), 0)
        self.assertEqual(call.call_count, 2)
        self.assertEqual((self.root / "out/facts-shard-01.jsonl").read_text(), "")

    def test_done_state_is_scoped_to_prompt_version_and_date(self):
        state = self.root / "done.jsonl"
        state.write_text("\n".join(json.dumps(record) for record in [
            {"key": "old:1", "dt": "2026-09-23", "model": "old-model"},
            {"key": "other-date:1", "dt": "2026-09-22", "model": MODEL_TAG},
            {"key": "current:1", "dt": "2026-09-23", "model": MODEL_TAG, "input_hash": "current-input"}]), encoding="utf-8")
        with patch.object(backfill, "STATE", state):
            self.assertEqual(backfill.load_done("2026-09-23"), {"current:1": "current-input"})


if __name__ == "__main__":
    unittest.main()
