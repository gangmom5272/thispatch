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

from plan import PlanFacts, extract_plan, source_excerpt, validate_grounding


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
    def test_restores_only_number_unit_spacing_from_source(self):
        cases = [
            ("5 초", "첫 귀환 시간을 5초 앞당긴다.", "5초"),
            ("5초", "첫 귀환 시간을 5 초 앞당긴다.", "5 초"),
            ("10 %", "확률을 10% 높인다.", "10%"),
            ("2.5 초", "대기 시간을 2.5초 줄인다.", "2.5초"),
            ("+5 %p", "확률 +5%p", "+5%p"),
            ("100 ms", "지연 100ms", "100ms"),
            ("5초", "대기 5\t초", "5\t초"),
            ("5초", "대기 5\u00a0초", "5\u00a0초"),
            ("5 초에서 3 초로", "5초에서 3초로 줄인다", "5초에서 3초로"),
        ]
        for excerpt, source, expected in cases:
            with self.subTest(excerpt=excerpt, source=source):
                self.assertEqual(source_excerpt(excerpt, source), expected)

    def test_does_not_relax_words_numbers_units_signs_or_line_breaks(self):
        cases = [
            ("내구성", "내구도"), ("귀환시간", "귀환 시간"),
            ("5 초", "15초"), ("5 초", "0.5초"), ("5 초", "-5초"),
            ("5 초", "+5초"), ("5", "50초"), ("5", "5.5초"),
            ("5 초", "6초"), ("5 초", "5분"), ("5 초", "5\n초"),
            ("1 0 %", "10%"), ("5 초", "5 seconds"), ("5 랭크", "5랭크"),
            ("5 %", "5%p"), ("5 s", "5seconds"),
            ("5 초", "5\v초"), ("5 초", "5\u2028초"),
        ]
        for excerpt, source in cases:
            with self.subTest(excerpt=excerpt, source=source):
                self.assertIsNone(source_excerpt(excerpt, source))

    @patch("plan.httpx.post")
    def test_number_unit_spacing_succeeds_without_retry_and_restores_all_excerpts(self, post):
        text = "첫 귀환 시간을 5초 앞당긴다. 시작 후 10분 이내에만 적용한다."
        change = {"target": "첫 귀환", "target_type": "system", "attribute": "시간",
                  "action": "decrease", "values": "5 초", "conditions": ["시작 후 10 분 이내에만 적용한다."],
                  "source_sentence": "첫 귀환 시간을 5 초 앞당긴다."}
        post.return_value = model_response([change])
        restored = extract_plan("", text)[0]
        self.assertEqual(restored.source_sentence, "첫 귀환 시간을 5초 앞당긴다.")
        self.assertEqual(restored.values, "5초")
        self.assertEqual(restored.conditions, ["시작 후 10분 이내에만 적용한다."])
        post.assert_called_once()

    @patch("plan.httpx.post")
    def test_invalid_synonym_does_not_drop_one_of_two_changes(self, post):
        text = "도끼 내구도를 30% 늘리고 나무 채집 속도를 소폭 상향한다. 하드코어 모드 제외."
        valid = [
            {"target": "도끼", "target_type": "item", "attribute": "내구도", "action": "increase",
             "values": "30%", "conditions": ["하드코어 모드 제외"], "source_sentence": "도끼 내구도를 30% 늘리고"},
            {"target": "나무", "target_type": "other", "attribute": "채집 속도", "action": "increase",
             "values": None, "conditions": ["하드코어 모드 제외"], "source_sentence": "나무 채집 속도를 소폭 상향한다."},
        ]
        invalid = copy.deepcopy(valid)
        invalid[0]["attribute"] = "내구성"
        post.side_effect = [model_response(invalid), model_response(valid)]
        result = extract_plan("", text)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0].attribute, "내구도")
        self.assertEqual(post.call_count, 2)

        post.reset_mock(side_effect=True)
        post.return_value = model_response(invalid)
        result = extract_plan("", text)
        self.assertEqual(len(result), 2)
        self.assertIsNone(result[0].attribute)
        self.assertEqual(result[0].values, "30%")
        self.assertEqual(result[0].source_sentence, valid[0]["source_sentence"])
        self.assertEqual(post.call_count, 2)

    def test_failed_validation_does_not_partially_modify_facts(self):
        changes = copy.deepcopy(CHANGES)
        changes[0]["target"] = "assault rifle"
        changes[1]["values"] = "10 %"
        facts = PlanFacts(changes=list(reversed(changes)))
        original = facts.model_dump()
        with self.assertRaises(ValueError):
            validate_grounding(facts, "", PROPOSAL)
        self.assertEqual(facts.model_dump(), original)

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
    def test_reported_durability_and_small_unspecified_increase(self):
        changes = extract_plan("", "도끼 내구도를 30% 늘리고 나무 채집 속도를 소폭 상향한다. 하드코어 모드 제외.")
        self.assertEqual(len(changes), 2)
        self.assertEqual([change.values for change in changes], ["30%", None])
        self.assertTrue(changes[0].attribute is None or "내구도" in changes[0].attribute)
        self.assertIn("내구도", changes[0].source_sentence)
        for change in changes:
            self.assertIn("하드코어 모드 제외", " ".join(change.conditions))

    def test_reported_jungle_experience_and_return_time(self):
        changes = extract_plan("", "정글 몬스터 경험치를 하향하고 첫 귀환 시간을 5초 앞당긴다.")
        self.assertEqual(len(changes), 2)
        self.assertEqual([change.values for change in changes], [None, "5초"])
        self.assertTrue(all(change.action == "decrease" for change in changes))

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
