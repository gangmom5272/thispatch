"""실제 수신 응답을 재생해 검증 정책을 검사한다. 새 모델 추론 결과는 아니다."""
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT / "batch"))
sys.path.insert(0, str(AI_ROOT / "api"))

import main
import plan
import qwen_worker as worker
from test_plan import model_response

RECORDS = json.loads((AI_ROOT / "tests/fixtures/qwen_observed_responses.json").read_text(encoding="utf-8"))["records"]
BATCH = next(record for record in RECORDS if record["client"] == "batch")
V2_RECORDS = json.loads((AI_ROOT / "tests/fixtures/qwen_observed_v2_responses.json").read_text(encoding="utf-8"))["records"]


class ObservedResponseTest(unittest.TestCase):
    def test_v2_condition_quotes_are_restored_from_war_thunder_source(self):
        record = next(record for record in V2_RECORDS if record["client"] == "batch")
        source = record["input"][1]
        changes = record["response"]["items"][1]["changes"]
        validated = worker.validate_changes(changes, source["title"], source["context"], source["text"])
        self.assertEqual(validated[0]["conditions"], ["on the ship’s bow"])
        self.assertIn(validated[0]["source_sentence"], source["text"])

    def test_all_five_live_scenarios_pass_when_replaying_v2_model_responses(self):
        from test_plan import PlanLiveTest
        from test_qwen_grounding import LiveQwenTest

        plan_records = iter(record for record in V2_RECORDS if record["client"] == "plan")
        batch_records = iter(record for record in V2_RECORDS if record["client"] == "batch")

        def replay_plan(url, **kwargs):
            recorded = next(plan_records)
            requested = json.loads(kwargs["json"]["messages"][1]["content"])
            self.assertEqual(requested, recorded["input"])
            return model_response(recorded["response"]["changes"])

        def replay_batch(request, **kwargs):
            recorded = next(batch_records)
            requested = json.loads(json.loads(request.data)["messages"][-1]["content"])
            self.assertEqual(len(requested), len(recorded["input"]))
            id_mapping = {}
            for previous, current in zip(recorded["input"], requested):
                self.assertEqual({k: v for k, v in previous.items() if k != "id"},
                                 {k: v for k, v in current.items() if k != "id"})
                id_mapping[previous["id"]] = current["id"]
            response = copy.deepcopy(recorded["response"])
            # 판본이 바뀌어 입력 해시만 달라진다. 모델이 반환했던 내용·게임 간 배치는 바꾸지 않는다.
            for item in response["items"]:
                item["id"] = id_mapping[item["id"]]
            return io.BytesIO(json.dumps({"message": {"content": json.dumps(response)}}).encode())

        # 실제 모델 대신 저장된 HTTP 응답을 주입한다. 이 결과를 새 모델 실행으로 보고하지 않는다.
        with patch.object(PlanLiveTest, "__unittest_skip__", False), patch.object(LiveQwenTest, "__unittest_skip__", False), \
                patch("plan.httpx.post", side_effect=replay_plan), patch("qwen_worker.urllib.request.urlopen", side_effect=replay_batch):
            suite = unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(PlanLiveTest),
                                       unittest.defaultTestLoader.loadTestsFromTestCase(LiveQwenTest)])
            result = unittest.TestResult()
            suite.run(result)
        self.assertEqual(result.testsRun, 5)
        self.assertTrue(result.wasSuccessful(), str(result.errors + result.failures))
        self.assertEqual(result.skipped, [])
        self.assertEqual(list(plan_records), [])
        self.assertEqual(list(batch_records), [])

    def test_recorded_rain_world_response_is_grounded_without_being_an_exact_reference_match(self):
        source = BATCH["input"][2]
        changes = BATCH["response"]["items"][2]["changes"]
        validated = worker.validate_changes(changes, source["title"], source["context"], source["text"])
        self.assertEqual(validated[0]["action"], "fix")
        self.assertEqual(validated[0]["target"], "Artificer")

    @patch("plan.httpx.post")
    def test_observed_synonym_retries_then_keeps_source_without_invented_property(self, post):
        attempts = [record for record in RECORDS if record["client"] == "plan" and "도끼" in record["input"]["text"]]
        self.assertEqual(len(attempts), 2)
        post.side_effect = [model_response(record["response"]["changes"]) for record in attempts]
        result = plan.extract_plan("", attempts[0]["input"]["text"])
        self.assertEqual(post.call_count, 2)
        self.assertEqual(len(result), 2)
        self.assertIsNone(result[0].attribute)
        self.assertEqual(result[0].values, "30%")
        self.assertEqual(result[0].source_sentence, "도끼 내구도를 30% 늘리고")
        self.assertIn("하드코어 모드 제외", result[0].conditions)
        restatement = main.restate(result[0].model_dump(), ("modify", "increase"))
        self.assertIn(result[0].source_sentence, restatement)
        self.assertIn("하드코어 모드 제외", restatement)
        self.assertNotIn("내구성", restatement)

    def test_observed_mixed_games_are_rejected_even_when_attribute_can_be_discarded(self):
        inputs = {item["id"]: item for item in BATCH["input"]}
        for returned in BATCH["response"]["items"][:2]:
            source = inputs[returned["id"]]
            with self.subTest(id=returned["id"]), self.assertRaisesRegex(ValueError, "source_sentence"):
                worker.validate_changes(returned["changes"], source["title"], source["context"], source["text"],
                                        discard_ungrounded_attribute=True)

    def test_observed_blade_fact_only_discards_assembled_attribute(self):
        source = BATCH["input"][0]
        fact = BATCH["response"]["items"][0]["changes"][0]
        result = worker.validate_changes([fact], source["title"], source["context"], source["text"],
                                         discard_ungrounded_attribute=True)[0]
        self.assertIsNone(result["attribute"])
        self.assertEqual({k: v for k, v in result.items() if k != "attribute"},
                         {k: v for k, v in fact.items() if k != "attribute"})

    def test_optional_attribute_policy_never_discards_invalid_values_target_or_conditions(self):
        source = BATCH["input"][0]
        original = BATCH["response"]["items"][0]["changes"][0]
        for update in ({"values": "20%"}, {"target": "RN Italia"}, {"conditions": ["PvP only"]}, {"attribute": 3}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                worker.validate_changes([{**original, **update}], source["title"], source["context"], source["text"],
                                        discard_ungrounded_attribute=True)
        record = next(record for record in RECORDS if record["client"] == "plan" and "도끼" in record["input"]["text"])
        for update in ({"values": "99%"}, {"target": "망치"}, {"conditions": ["모든 모드"]}):
            facts = plan.PlanFacts.model_validate(record["response"])
            facts.changes[0] = facts.changes[0].model_copy(update=update)
            before = facts.model_dump()
            with self.subTest(update=update), self.assertRaises(ValueError):
                plan.validate_grounding(facts, "", record["input"]["text"], discard_ungrounded_attribute=True)
            self.assertEqual(facts.model_dump(), before)

    @patch("qwen_worker.urllib.request.urlopen")
    def test_worker_retries_items_separately_without_saving_mixed_sources(self, request):
        def response_for_call(http_request, **kwargs):
            items = json.loads(json.loads(http_request.data)["messages"][-1]["content"])
            if len(items) > 1:
                response = copy.deepcopy(BATCH["response"])
                for returned, requested in zip(response["items"], items):
                    returned["id"] = requested["id"]
            else:
                # 개별 호출도 관측된 혼합 응답을 반환하도록 해, 잘못된 청크가 끝내 저장되지 않는지 검사한다.
                index = next(i for i, source in enumerate(BATCH["input"]) if source["text"] == items[0]["text"])
                returned = copy.deepcopy(BATCH["response"]["items"][index])
                returned["id"] = items[0]["id"]
                response = {"items": [returned]}
            return io.BytesIO(json.dumps({"message": {"content": json.dumps(response)}}).encode())
        request.side_effect = response_for_call
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shard = root / "mixed.jsonl"
            shard.write_text("\n".join(json.dumps({"key": source["id"] + ":1", "gid": source["id"], "seq": 1,
                "text": "title: " + source["title"] + " | text: " + source["text"]}) for source in BATCH["input"]), encoding="utf-8")
            self.assertEqual(worker.run(shard, root / "out", log=lambda _: None), 1)
            saved = [json.loads(line) for line in (root / "out/facts-mixed.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(request.call_count, 4)
        self.assertEqual([record["gid"] for record in saved], [BATCH["input"][2]["id"]])


if __name__ == "__main__":
    unittest.main()
