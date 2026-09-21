# -*- coding: utf-8 -*-
"""Qwen 호출 워커 — 표준 라이브러리만 쓴다. 팀원 노트북에 이 파일과 qwen_prompt.py 만 복사해 돌릴 수 있다.

왜 따로 뗐나(9/18)
  청크가 176만 개라 한 대로는 730시간이다. 우선순위(인기 게임·최근 공지) 7.8만 개만 채워도 32시간이라
  같은 사양 노트북 여러 대로 나눈다. 그쪽에는 pandas·pyarrow·CUDA 파이썬 환경이 없어도 되게
  입력·출력을 JSONL 로 하고 의존을 Ollama + 파이썬 3.10 이상으로 줄였다.

입력  shard JSONL: 한 줄에 청크 하나 {"key": "gid:seq", "gid", "seq", "text"}. 우선순위 순서로 정렬돼 있다.
출력  {out}/facts-<shard이름>.jsonl: {"gid","seq","model","context","text","changes"} 를 요청마다 이어 쓴다.
      중간에 죽어도 그때까지는 남고, 다시 실행하면 이미 쓴 청크는 건너뛴다.
      이 파일을 qwen_backfill.py --apply 에 넘기면 patch_change 를 덮어쓴다.

실행  python qwen_worker.py --shard shard-01.jsonl --out results [--max-seconds 21600]

실측(9/11, 4070 8GB, Q4_K_M): 1.2~1.7초/청크. 동시 요청은 이득 없음(GPU 한 장이 이미 포화).
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from qwen_prompt import FORMAT, MESSAGES, MODEL, MODEL_TAG, OPTIONS  # noqa: E402

CHAR_BUDGET = 2500   # 요청당 본문+문맥 글자 합 상한. 6개 고정이면 긴 불릿에서 4,000자를 넘어 2.1초/청크(9/14)
MAX_ITEMS = 6        # 스키마 items maxItems=6 (qwen_prompt.FORMAT)
DEFAULT_URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")


def split_text(text):
    """patch_chunk.text('title: T | text: Context: C\\nChange: X') → (title, context, change)"""
    title, _, rest = text.partition(" | text: ")
    title = title.removeprefix("title: ")
    if rest.startswith("Context: "):
        ctx, _, chg = rest[len("Context: "):].partition("\nChange: ")
        return title, ctx, chg
    return title, "", rest


def call_qwen(url, items, timeout=300):
    body = {"model": MODEL, "messages": MESSAGES + [{"role": "user", "content": json.dumps(items, ensure_ascii=False)}],
            "format": FORMAT, "options": OPTIONS, "stream": False, "think": False, "keep_alive": "10m"}
    req = urllib.request.Request(url + "/api/chat", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode("utf-8"))
    out = json.loads(resp["message"]["content"])
    return {it["id"]: it.get("changes", []) for it in out.get("items", [])}


def _done_keys(path):
    keys = set()
    if path.exists():
        for line in path.open(encoding="utf-8"):
            if line.strip():
                d = json.loads(line)
                keys.add(f"{d['gid']}:{d['seq']}")
    return keys


def run(shard, out_dir, url=DEFAULT_URL, max_seconds=6 * 3600, limit=0, log=print):
    """shard 의 청크를 Qwen 에 보내고 결과를 out_dir/facts-<shard>.jsonl 에 이어 쓴다. 처리 건수를 돌려준다."""
    shard, out_dir = Path(shard), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"facts-{shard.stem}.jsonl"
    done = _done_keys(out)

    todo = []
    for line in shard.open(encoding="utf-8"):
        if not line.strip():
            continue
        d = json.loads(line)
        if d["key"] in done:
            continue
        _, ctx, body = split_text(d["text"])
        d["context"], d["body"] = ctx, body
        d["h"] = hashlib.sha1(body.lower().strip().encode()).hexdigest()
        todo.append(d)
    if limit:
        todo = todo[:limit]
    log(f"shard={shard.name} chunks={len(todo)} (이미 끝난 {len(done)}) → {out}")
    if not todo:
        return 0

    # 같은 문장(공지 간 중복 16%)은 1회만 보내고 결과를 복사한다
    by_h = {}
    for d in todo:
        by_h.setdefault(d["h"], []).append(d)
    uniq = [v[0] for v in by_h.values()]
    batches, cur, cur_len = [], [], 0
    for r in uniq:
        n = len(r["body"]) + len(r["context"])
        if cur and (cur_len + n > CHAR_BUDGET or len(cur) >= MAX_ITEMS):
            batches.append(cur); cur, cur_len = [], 0
        cur.append(r); cur_len += n
    if cur:
        batches.append(cur)
    log(f"unique={len(uniq)} requests={len(batches)} (avg {len(uniq)/max(len(batches),1):.1f} items)")

    t0, calls, fails, written, seen = time.time(), 0, 0, 0, 0
    with out.open("a", encoding="utf-8") as f:
        for batch in batches:
            if time.time() - t0 > max_seconds:
                log("시간 예산 끝. 여기까지 저장됨 — 다시 실행하면 이어서 한다"); break
            items = [{"id": r["h"][:12], "context": r["context"], "text": r["body"]} for r in batch]
            try:
                got = call_qwen(url, items); calls += 1
            except Exception as e:  # noqa: BLE001
                # 묶음 응답이 잘리거나 깨지면 한 개씩 다시 보낸다. 그래도 실패하면 그 청크만 건너뜀
                got = {}
                for it in items:
                    try:
                        got.update(call_qwen(url, [it])); calls += 1
                    except Exception as e1:  # noqa: BLE001
                        fails += 1; log(f"qwen fail(single) {type(e1).__name__} {str(e1)[:80]} | {it['text'][:60]}")
            for r in batch:
                seen += 1
                if r["h"][:12] not in got:
                    continue
                for d in by_h[r["h"]]:          # 같은 문장의 청크 전부에 결과 복사
                    f.write(json.dumps({"gid": d["gid"], "seq": int(d["seq"]), "model": MODEL_TAG, "context": d["context"],
                                        "text": d["body"], "changes": got[r["h"][:12]]}, ensure_ascii=False) + "\n")
                    written += 1
            f.flush()
            if calls % 50 == 0:
                el = time.time() - t0
                log(f"  {seen}/{len(uniq)} unique · {el/60:.0f}분 · {el/max(seen,1):.2f}초/unique · 청크 {written}")
    el = time.time() - t0
    log(f"끝: 청크 {written} · 요청 {calls} · 실패 {fails} · {el/60:.0f}분 ({el/max(written,1):.2f}초/청크)")
    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shard", required=True, help="shard-0N.jsonl")
    ap.add_argument("--out", default="results", help="결과 폴더")
    ap.add_argument("--ollama", default=DEFAULT_URL)
    ap.add_argument("--max-seconds", type=int, default=6 * 3600, help="시간 예산. 넘으면 저장 후 종료")
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    run(a.shard, a.out, a.ollama, a.max_seconds, a.limit)


if __name__ == "__main__":
    main()
