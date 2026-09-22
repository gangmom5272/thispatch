import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AI_ROOT / "batch"))
sys.path.insert(0, str(AI_ROOT / "api"))

import main
from test_plan import CHANGES, CONDITIONS, PROPOSAL, model_response


class PlanApiTest(unittest.TestCase):
    def setUp(self):
        # 컨텍스트 매니저를 사용하지 않아 단위 테스트에서 GPU 워밍업을 실행하지 않는다.
        self.client = TestClient(main.app)
        self.addCleanup(self.client.close)

    @patch("plan.httpx.post")
    def test_structure_keeps_response_contract_and_exact_source_terms(self, post):
        post.return_value = model_response(CHANGES)
        response = self.client.post("/plan/structure", json={"title": "", "text": PROPOSAL})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(set(body), {"changes", "model", "prompt_version", "elapsed_ms"})
        self.assertEqual(body["prompt_version"], "plan-grounded-1")
        self.assertEqual([change["change_seq"] for change in body["changes"]], [1, 2])
        for change in body["changes"]:
            self.assertEqual(change["change_type"], "modify")
            self.assertEqual(change["direction"], "decrease")
            self.assertEqual(change["target"], "저격총")
            self.assertEqual(change["conditions"], CONDITIONS)
        self.assertNotIn("10%", body["changes"][0]["restatement"])
        self.assertIn("10%", body["changes"][1]["restatement"])

    @patch("main.extract_plan", side_effect=ValueError("private invalid response"))
    def test_invalid_extraction_uses_existing_error_envelope(self, extract):
        response = self.client.post("/plan/structure", json={"text": PROPOSAL})
        self.assertEqual(response.status_code, 502)
        body = response.json()
        self.assertEqual(set(body), {"code", "message", "responsedAt"})
        self.assertEqual(body["code"], "AI_UPSTREAM_ERROR")
        self.assertNotIn("private", response.text)


if __name__ == "__main__":
    unittest.main()
