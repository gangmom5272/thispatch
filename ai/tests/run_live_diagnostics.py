"""실제 모델 회귀 테스트와 원본 응답을 함께 저장한다. 운영 배치/적재는 실행하지 않는다."""
import argparse
import hashlib
import io
import json
import os
import sys
import unittest
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("qwen-live-diagnostics"))
    args = parser.parse_args()
    run_directory = args.output / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run_directory.mkdir(parents=True, exist_ok=False)
    test_directory = Path(__file__).resolve().parent
    ai_directory = test_directory.parent
    sys.path.insert(0, str(test_directory))
    os.environ["RUN_PLAN_LIVE_TEST"] = "1"
    os.environ["RUN_QWEN_LIVE_TEST"] = "1"

    # 테스트의 모델 요청만 기록한다. 인증 헤더나 환경 변수 전체는 수집하지 않는다.
    original_post = httpx.post
    original_urlopen = urllib.request.urlopen
    response_log = run_directory / "model-responses.jsonl"

    def record_call(client, request_body, response_body=None, error=None):
        record = {"client": client, "request": request_body, "response": response_body}
        if error is not None:
            record["error_type"] = type(error).__name__
        with response_log.open("a", encoding="utf-8") as output:
            output.write(json.dumps(record, ensure_ascii=False) + "\n")

    def recorded_post(url, **kwargs):
        try:
            response = original_post(url, **kwargs)
        except Exception as error:
            record_call("plan", kwargs.get("json"), error=error)
            raise
        record_call("plan", kwargs.get("json"), response.text)
        return response

    def recorded_urlopen(request, **kwargs):
        request_body = json.loads(request.data.decode("utf-8"))
        try:
            with original_urlopen(request, **kwargs) as response:
                body = response.read()
        except Exception as error:
            record_call("batch", request_body, error=error)
            raise
        record_call("batch", request_body, body.decode("utf-8", errors="replace"))
        return io.BytesIO(body)

    # 기본 테스트는 이미 통과했다. 실패 원인 수집에 필요한 실제 모델 테스트만 실행한다.
    suite = unittest.TestSuite([
        unittest.defaultTestLoader.loadTestsFromName("test_plan.PlanLiveTest"),
        unittest.defaultTestLoader.loadTestsFromName("test_qwen_grounding.LiveQwenTest"),
    ])
    source_hashes = {}
    for relative in ("api/plan.py", "api/main.py", "batch/qwen_prompt.py", "batch/qwen_worker.py"):
        source_hashes[relative] = hashlib.sha256((ai_directory / relative).read_bytes()).hexdigest()
    (run_directory / "source-hashes.json").write_text(json.dumps(source_hashes, indent=2), encoding="utf-8")
    print("Diagnostics directory:", run_directory.resolve(), flush=True)
    with (run_directory / "tests.log").open("w", encoding="utf-8") as test_log:
        with patch("httpx.post", recorded_post), patch("urllib.request.urlopen", recorded_urlopen):
            result = unittest.TextTestRunner(stream=test_log, verbosity=2).run(suite)
    summary = {"tests": result.testsRun, "failures": len(result.failures),
               "errors": len(result.errors), "skipped": len(result.skipped)}
    (run_directory / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary), flush=True)
    print("Share all files in this diagnostics directory.", flush=True)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
