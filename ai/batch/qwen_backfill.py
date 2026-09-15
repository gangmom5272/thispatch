# -*- coding: utf-8 -*-
"""Qwen 사전 분석 백필: 청크 문장 → 대상·속성·조건 슬롯 → patch_change 덮어쓰기.

입력  {AI_WORK_DIR}/out/embeddings/patch_chunk/dt=D, patch_change/dt=D  (embed_chunks.py 결과)
      {AI_WORK_DIR}/in/news_raw/dt=D  (published_at 으로 우선순위)
출력  같은 두 폴더를 다시 쓴다. 처리한 청크의 규칙 변경점은 Qwen 결과로 교체, model_version=qwen3.5-9b-q4km/<prompt>.
      Qwen 원본 응답은 {AI_WORK_DIR}/out/embeddings/qwen_raw/dt=D/*.jsonl 에 그대로 보관(재매핑용).
      진행 상태 {AI_WORK_DIR}/state/qwen_done.jsonl (재실행 시 건너뜀).
실행  python qwen_backfill.py --dt 2026-09-11 [--max-seconds 28800] [--limit N] [--include-skipped]

실측(2026-09-11, 4070 8GB, Ollama Q4_K_M, 6청크/요청): 1.2초/청크. 동시 요청은 이득 없음.
우선순위: 최근 공지 먼저 → 같은 문장(공지 간 중복 16%)은 1회만 호출하고 결과를 복사.
"""
import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path

import httpx
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from common import (OLLAMA_URL, PATCH_CHANGE_SCHEMA, PATCH_CHUNK_SCHEMA, WORK, in_dir, now_ts,  # noqa: E402
                    out_dir, read_parquet_dir, write_parquet_dir)
from qwen_prompt import FORMAT, MESSAGES, MODEL, MODEL_TAG, OPTIONS  # noqa: E402

CHAR_BUDGET = 2500   # 요청당 본문+문맥 글자 합 상한
MAX_ITEMS = 6        # 스키마 items maxItems=6 (qwen_prompt.FORMAT)
STATE = WORK / "state" / "qwen_done.jsonl"
_FROMTO = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*%?\s*(?:->|→|to)\s*(\d[\d,]*(?:\.\d+)?)", re.I)
TARGET_TYPES = {"player", "enemy", "weapon", "item", "skill", "map", "system", "other", "unknown"}


def split_text(text):
    """patch_chunk.text('title: T | text: Context: C\\nChange: X') → (title, context, change)"""
    title, _, rest = text.partition(" | text: ")
    title = title.removeprefix("title: ")
    if rest.startswith("Context: "):
        ctx, _, chg = rest[len("Context: "):].partition("\nChange: ")
        return title, ctx, chg
    return title, "", rest


def to_codes(change):
    """Qwen action → (change_type, direction). describe 는 변경점이 아니므로 None."""
    a = change.get("action")
    vals = change.get("values") or ""
    if a == "describe" or a is None:
        return None
    if a == "add":
        return "add", "none"
    if a == "remove":
        return "remove", "none"
    if a == "deprecate":
        return "deprecate", "none"
    if a == "fix":
        return "fix", "not_applicable"
    if a == "increase":
        return "modify", "increase"
    if a == "decrease":
        return "modify", "decrease"
    d = "unknown"
    m = _FROMTO.search(str(vals))
    if m:
        x, y = float(m.group(1).replace(",", "")), float(m.group(2).replace(",", ""))
        d = "increase" if y > x else "decrease" if y < x else "none"
    return "modify", d


def call_qwen(client, items):
    body = {"model": MODEL, "messages": MESSAGES + [{"role": "user", "content": json.dumps(items, ensure_ascii=False)}],
            "format": FORMAT, "options": OPTIONS, "stream": False, "think": False, "keep_alive": "10m"}
    r = client.post(OLLAMA_URL + "/api/chat", json=body, timeout=300).json()
    out = json.loads(r["message"]["content"])
    return {it["id"]: it.get("changes", []) for it in out.get("items", [])}


GENERIC_TARGETS = {"player", "players", "game", "system", "ui", "hud", "server", "servers", "client", "menu", "settings",
                   "audio", "performance", "controller", "camera", "matchmaking", "network", "localization", "text"}
_TOK = re.compile(r"[a-z0-9]+")


def is_grounded(target, text, context):
    """근거 검사 3단계 (9/14 완화): ① 없음/일반 대상어 → valid ② 원문 부분문자열 → valid
    ③ 대상 토큰의 60% 이상이 원문·문맥에 있으면 valid ('ship collision bug' → ship, collision). 그 외 needs_review.
    Top50 실측: 정확 일치만 인정하면 21% 가 needs_review 였고 대부분 'player', 'game', 의역이었다."""
    if not target:
        return True
    t = target.lower().strip()
    if t in GENERIC_TARGETS:
        return True
    hay = (text + " " + context).lower()
    if t in hay:
        return True
    toks = [w for w in _TOK.findall(t) if len(w) > 2]
    if not toks:
        return True
    return sum(1 for w in toks if w in hay) / len(toks) >= 0.6


def facts_to_rows(gid, seq, context, text, changes):
    rows, n = [], 0
    for c in changes:
        codes = to_codes(c)
        if not codes:
            continue
        n += 1
        target = (c.get("target") or "").strip() or None
        grounded = is_grounded(target, text, context)
        rows.append({"gid": gid, "seq": seq, "change_seq": n, "change_type": codes[0], "direction": codes[1],
                     "target_type": c.get("target_type") if c.get("target_type") in TARGET_TYPES else "unknown",
                     "target": target, "attribute": (c.get("attribute") or None),
                     "evidence_quote": text, "validation_status": "valid" if grounded else "needs_review"})
    return rows


def load_done():
    if not STATE.exists():
        return {}
    return {json.loads(l)["key"]: json.loads(l) for l in STATE.open(encoding="utf-8") if l.strip()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dt", required=True)
    ap.add_argument("--max-seconds", type=int, default=8 * 3600, help="근무 시간 예산. 넘으면 중간 저장 후 종료")
    ap.add_argument("--limit", type=int, default=0, help="청크 수 제한(테스트)")
    ap.add_argument("--include-skipped", action="store_true", help="규칙이 변경점을 못 찾은 청크도 보냄")
    a = ap.parse_args()

    ck = read_parquet_dir(out_dir("embeddings/patch_chunk", a.dt))
    ch = read_parquet_dir(out_dir("embeddings/patch_change", a.dt))
    news = read_parquet_dir(in_dir("news_raw", a.dt), columns=["gid", "published_at"])
    if ck.empty:
        sys.exit("patch_chunk 없음. embed_chunks.py 먼저")

    done = load_done()
    ck["key"] = ck.gid + ":" + ck.seq.astype(str)
    todo = ck[~ck.key.isin(done) & (ck.model_version != MODEL_TAG)]
    if not a.include_skipped:
        todo = todo[todo.embedding_status != "skipped"]
    todo = todo.merge(news, on="gid", how="left").sort_values("published_at", ascending=False)  # 최근 공지 우선
    if a.limit:
        todo = todo.head(a.limit)
    print(f"chunks total={len(ck)} done={ck.key.isin(done).sum()} todo={len(todo)}")
    if todo.empty:
        return

    parts = todo.text.map(split_text)
    todo = todo.assign(title=[p[0] for p in parts], context=[p[1] for p in parts], body=[p[2] for p in parts])
    todo["h"] = todo.body.str.lower().str.strip().map(lambda s: hashlib.sha1(s.encode()).hexdigest())
    uniq = todo.drop_duplicates("h")
    print(f"unique sentences={len(uniq)} (dup saved {len(todo) - len(uniq)})")

    client = httpx.Client()
    results, t0, calls, fails = {}, time.time(), 0, 0
    rows_u = uniq.to_dict("records")
    # 글자 예산으로 묶는다(9/14): 6개 고정이면 긴 불릿(p90 700자)에서 요청이 4,000자를 넘어 2.1초/청크. 짧은 문장은 더 많이, 긴 문장은 적게
    batches, cur, cur_len = [], [], 0
    for r in rows_u:
        n = len(r["body"]) + len(r["context"])
        if cur and (cur_len + n > CHAR_BUDGET or len(cur) >= MAX_ITEMS):
            batches.append(cur); cur, cur_len = [], 0
        cur.append(r); cur_len += n
    if cur:
        batches.append(cur)
    print(f"requests={len(batches)} (avg {len(rows_u)/max(len(batches),1):.1f} items, budget {CHAR_BUDGET} chars)")
    done_items = 0
    for bi, batch in enumerate(batches):
        if time.time() - t0 > a.max_seconds:
            print("time budget reached; saving partial"); break
        i = done_items; done_items += len(batch)
        items = [{"id": r["h"][:12], "context": r["context"], "text": r["body"]} for r in batch]
        try:
            got = call_qwen(client, items); calls += 1
        except Exception as e:  # noqa: BLE001
            # 묶음 응답이 잘리거나(num_predict 초과) 깨지면 한 개씩 다시 보낸다. 그래도 실패하면 그 청크만 건너뜀
            got = {}
            for it in items:
                try:
                    got.update(call_qwen(client, [it])); calls += 1
                except Exception as e1:  # noqa: BLE001
                    fails += 1; print("qwen fail(single)", type(e1).__name__, str(e1)[:100], "|", it["text"][:60])
        for r in batch:
            if r["h"][:12] in got:
                results[r["h"]] = got[r["h"][:12]]
        if calls % 50 == 0:
            el = time.time() - t0
            print(f"  {i + len(batch)}/{len(rows_u)} unique, {el:.0f}s, {el / (i + len(batch)):.2f}s/unique")

    # Qwen 원본 응답 보관 (DB 아님, HDFS /embeddings/qwen_raw/dt=D). 컬럼·매핑을 바꿔도 재실행 없이 여기서 다시 뽑는다.
    processed = todo[todo.h.isin(results)]
    if processed.empty:
        sys.exit(f"Qwen 결과 0건 (fails={fails}). Ollama 상태 확인: {OLLAMA_URL}")
    raw_dir = out_dir("embeddings/qwen_raw", a.dt); raw_dir.mkdir(parents=True, exist_ok=True)
    with (raw_dir / f"facts-{now_ts()}.jsonl").open("w", encoding="utf-8") as f:
        for r in processed.itertuples(index=False):
            f.write(json.dumps({"gid": r.gid, "seq": int(r.seq), "model": MODEL_TAG, "context": r.context,
                                "text": r.body, "changes": results[r.h]}, ensure_ascii=False) + "\n")
    (raw_dir / "_SUCCESS").touch()
    new_rows = []
    for r in processed.itertuples(index=False):
        new_rows += facts_to_rows(r.gid, int(r.seq), r.context, r.body, results[r.h])
    keys = set(processed.key)
    ch["key"] = ch.gid + ":" + ch.seq.astype(str)
    ch = ch[~ch.key.isin(keys)].drop(columns="key")
    ch = pd.concat([ch, pd.DataFrame(new_rows, columns=PATCH_CHANGE_SCHEMA.names)], ignore_index=True)
    ck.loc[ck.key.isin(keys), ["model_version", "processed_at"]] = [MODEL_TAG, now_ts()]
    ck = ck.drop(columns="key")
    write_parquet_dir(ck, out_dir("embeddings/patch_chunk", a.dt), schema=PATCH_CHUNK_SCHEMA)
    write_parquet_dir(ch.sort_values(["gid", "seq", "change_seq"]), out_dir("embeddings/patch_change", a.dt),
                      schema=PATCH_CHANGE_SCHEMA)
    STATE.parent.mkdir(parents=True, exist_ok=True)
    with STATE.open("a", encoding="utf-8") as f:
        for k in keys:
            f.write(json.dumps({"key": k, "dt": a.dt, "model": MODEL_TAG, "ts": now_ts()}) + "\n")
    el = time.time() - t0
    print(f"qwen done: chunks={len(processed)} unique_calls={calls} fails={fails} rows={len(new_rows)} "
          f"{el:.0f}s ({el / max(len(processed), 1):.2f}s/chunk incl. dup savings)")
    if new_rows:
        df = pd.DataFrame(new_rows)
        print("  target filled", f"{df.target.notna().mean():.0%}", "| attribute filled", f"{df.attribute.notna().mean():.0%}",
              "| needs_review", f"{(df.validation_status == 'needs_review').mean():.0%}")


if __name__ == "__main__":
    main()
