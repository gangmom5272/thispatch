import copy
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT / "batch"))
sys.path.insert(0, str(AI_ROOT / "api"))

from plan import PlanFacts, extract_plan, validate_grounding


PROPOSAL = "저격총 반동을 줄이고 연사 속도를 10% 낮춘다. 랭크 게임에만 적용하며, 신규 맵에서는 기존 값을 유지한다."
CONDITIONS = ["랭크 게임에만 적용", "신규 맵에서는 기존 값을 유지한다"]
CHANGES = [
    {"target": "저격총", "target_type": "weapon", "attribute": "반동", "action": "decrease",
     "values": None, "conditions": CONDITIONS, "source_sentence": "저격총 반동을 줄이고"},
    {"target": "저격총", "target_type": "weapon", "attribute": "연사 속도", "action": "decrease",
     "values": "10%", "conditions": CONDITIONS, "source_sentence": "연사 속도를 10% 낮춘다."},
]


def model_response(changes):
    return httpx.Response(200, request=httpx.Request("POST", "http://test/api/chat"),
                          json={"message": {"content": json.dumps({"changes": changes}, ensure_ascii=False)}})


class PlanGroundingTest(unittest.TestCase):
    def test_accepts_source_terms_separate_amounts_and_later_exclusion(self):
        validate_grounding(PlanFacts(changes=CHANGES), "", PROPOSAL)

    def test_rejects_substituted_target_and_borrowed_or_invented_amounts(self):
        for field, value in (("target", "assault rifle"), ("target", "돌격소총"),
                             ("values", "10%"), ("values", "20%"), ("attribute", "damage")):
            with self.subTest(field=field, value=value):
                changes = copy.deepcopy(CHANGES)
                changes[0][field] = value
                with self.assertRaises(ValueError):
                    validate_grounding(PlanFacts(changes=changes), "", PROPOSAL)

    def test_rejects_paraphrased_source_and_changed_conditions(self):
        for field, value in (("source_sentence", "저격총 반동을 10% 줄인다"),
                             ("conditions", ["rank games"]), ("conditions", ["신규 맵에 적용"])):
            with self.subTest(field=field):
                changes = copy.deepcopy(CHANGES)
                changes[0][field] = value
                with self.assertRaises(ValueError):
                    validate_grounding(PlanFacts(changes=changes), "", PROPOSAL)

    def test_accepts_missing_details_and_title_subject(self):
        change = {"target": "Axebot", "target_type": "unknown", "attribute": None,
                  "action": "fix", "values": None, "conditions": [], "source_sentence": "버그를 수정한다."}
        validate_grounding(PlanFacts(changes=[change]), "Axebot", "버그를 수정한다.")
        change["target"] = None
        validate_grounding(PlanFacts(changes=[change]), "", "버그를 수정한다.")

    @patch("plan.httpx.post")
    def test_passes_complete_proposal_and_preserves_grounded_output(self, post):
        post.return_value = model_response(CHANGES)
        changes = extract_plan("", PROPOSAL)
        self.assertEqual([change.target for change in changes], ["저격총", "저격총"])
        self.assertEqual([change.values for change in changes], [None, "10%"])
        self.assertEqual(changes[0].conditions, CONDITIONS)
        post.assert_called_once()
        sent = post.call_args.kwargs["json"]
        self.assertEqual(json.loads(sent["messages"][1]["content"])["text"], PROPOSAL)

    @patch("plan.httpx.post")
    def test_retries_invalid_extraction_once_without_reusing_wrong_target(self, post):
        wrong = copy.deepcopy(CHANGES)
        wrong[0]["target"] = "assault rifle"
        post.side_effect = [model_response(wrong), model_response(CHANGES)]
        self.assertEqual(extract_plan("", PROPOSAL)[0].target, "저격총")
        self.assertEqual(post.call_count, 2)
        self.assertNotIn("assault rifle", json.dumps(post.call_args.kwargs["json"]["messages"]))

    @patch("plan.httpx.post")
    def test_persistent_source_mismatch_is_not_returned_as_valid_changes(self, post):
        wrong = copy.deepcopy(CHANGES)
        wrong[0]["values"] = "10%"
        post.return_value = model_response(wrong)
        with self.assertRaisesRegex(ValueError, "source validation"):
            extract_plan("", PROPOSAL)
        self.assertEqual(post.call_count, 2)

    @patch("plan.httpx.post")
    def test_empty_changes_do_not_trigger_a_rule_based_guess(self, post):
        post.return_value = model_response([])
        self.assertEqual(extract_plan("", "무기 피해량은 기존 값을 유지한다."), [])
        post.assert_called_once()

    @patch("plan.httpx.post")
    def test_does_not_retry_http_failures(self, post):
        post.return_value = httpx.Response(503, request=httpx.Request("POST", "http://test/api/chat"))
        with self.assertRaises(httpx.HTTPStatusError):
            extract_plan("", PROPOSAL)
        post.assert_called_once()

    @patch("plan.time.monotonic", side_effect=[0, 1, 181])
    @patch("plan.httpx.post")
    def test_does_not_start_retry_after_deadline(self, post, clock):
        wrong = copy.deepcopy(CHANGES)
        wrong[0]["target"] = "assault rifle"
        post.return_value = model_response(wrong)
        with self.assertRaisesRegex(ValueError, "deadline"):
            extract_plan("", PROPOSAL)
        post.assert_called_once()


@unittest.skipUnless(os.environ.get("RUN_PLAN_LIVE_TEST") == "1", "실제 Ollama 모델은 명시적으로 검증")
class PlanLiveTest(unittest.TestCase):
    def test_reported_sniper_proposal(self):
        changes = extract_plan("", PROPOSAL)
        self.assertEqual(len(changes), 2)
        by_attribute = {change.attribute: change for change in changes}
        self.assertEqual(set(by_attribute), {"반동", "연사 속도"})
        self.assertIsNone(by_attribute["반동"].values)
        self.assertEqual(by_attribute["연사 속도"].values, "10%")
        for change in changes:
            self.assertEqual(change.target, "저격총")
            self.assertEqual(change.target_type, "weapon")
            self.assertEqual(change.action, "decrease")
            self.assertIn("랭크 게임", " ".join(change.conditions))
            self.assertIn("기존 값을 유지", " ".join(change.conditions))

    def test_independent_target_and_amount(self):
        changes = extract_plan("", "권총 피해량을 5% 높이고 소총 재장전 시간을 8% 줄인다.")
        self.assertEqual(len(changes), 2)
        self.assertEqual({(change.target, change.values, change.action) for change in changes},
                         {("권총", "5%", "increase"), ("소총", "8%", "decrease")})


if __name__ == "__main__":
    unittest.main()
