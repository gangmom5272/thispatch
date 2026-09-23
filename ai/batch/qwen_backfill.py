# -*- coding: utf-8 -*-
"""Qwen 사전 분석 백필: 청크 문장 → 대상·속성·조건 슬롯 → patch_change 덮어쓰기.

입력  {AI_WORK_DIR}/out/embeddings/patch_chunk/dt=D, patch_change/dt=D  (embed_chunks.py 결과)
      {AI_WORK_DIR}/in/news_raw/dt=*  (gid → appid·published_ts, 우선순위용)
      {AI_WORK_DIR}/state/app_rank.csv  (appid,reviews,rank — 게임 인기 순위. 없으면 최신순만)
출력  같은 두 폴더를 다시 쓴다. 처리한 청크의 규칙 변경점은 Qwen 결과로 교체, model_version=qwen3.5-9b-q4km/<prompt>.
      Qwen 원본 응답은 {AI_WORK_DIR}/out/embeddings/qwen_raw/dt=D/*.jsonl 에 그대로 보관(재매핑용).
      진행 상태 {AI_WORK_DIR}/state/qwen_done.jsonl (재실행 시 건너뜀).

실행
  한 대로            python qwen_backfill.py --dt D [--max-seconds 14400] [--top-apps 500 --since-days 730]
  여러 대로 나눌 때  python qwen_backfill.py --dt D --make-shards 5 --top-apps 500 --since-days 730
                     → state/shards/shard-01..05.jsonl 을 각 노트북에서 qwen_worker.py 로 돌린다
                     python qwen_backfill.py --dt D --apply results1 results2 ...   ← 결과 jsonl 폴더들 반영

우선순위(9/18): 인기 게임(app_rank) 순 → 같은 게임 안에서 최근 공지 순. --top-apps K 로 상위 K 게임만,
--since-days N 으로 최근 N 일 공지만 남긴다. 176만 청크 전량은 730시간이라 처음부터 못 채우는 양이고,
사례로 뽑히는 패치는 사람들이 많이 하는 게임의 최근 패치이므로 상위 500 게임 × 2년(7.8만 청크, 4.4%)이 기본 권장값.
같은 문장(공지 간 중복 16%)은 1회만 호출하고 결과를 복사한다(qwen_worker).

큰 파일 주의: patch_chunk 는 embedding 이 있어 pandas 로 통째 올리면 죽는다(2.8M행 × 512).
그래서 고를 때는 필요한 칼럼만 읽고, 덮어쓸 때는 part 파일 하나씩 pyarrow 로 두 칼럼만 바꿔 쓴다.
"""
import argparse
import json
import re
import shutil
import sys
import time
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq

sys.path.insert(0, str(Path(__file__).parent))
from common import (PATCH_CHANGE_SCHEMA, SUCCESS, WORK, now_ts, out_dir, read_news,  # noqa: E402
                    read_parquet_dir, write_parquet_dir)
from qwen_prompt import MODEL_TAG  # noqa: E402
from rules import RULE_VERSION  # noqa: E402
import qwen_worker  # noqa: E402

STATE = WORK / "state" / "qwen_done.jsonl"
APP_RANK = WORK / "state" / "app_rank.csv"
SHARDS = WORK / "state" / "shards"
_FROMTO = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*%?\s*(?:->|→|to)\s*(\d[\d,]*(?:\.\d+)?)", re.I)
TARGET_TYPES = {"player", "enemy", "weapon", "item", "skill", "map", "system", "other", "unknown"}


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


def facts_to_rows(gid, seq, context, text, changes, title=""):
    rows, n = [], 0
    for c in qwen_worker.validate_changes(changes, title, context, text):
        codes = to_codes(c)
        if not codes:
            continue
        n += 1
        target = (c.get("target") or "").strip() or None
        rows.append({"gid": gid, "seq": seq, "change_seq": n, "change_type": codes[0], "direction": codes[1],
                     "target_type": c.get("target_type") if c.get("target_type") in TARGET_TYPES else "unknown",
                     "target": target, "attribute": (c.get("attribute") or None),
                     "evidence_quote": c["source_sentence"], "validation_status": "valid",
                     "model_version": MODEL_TAG})
    return rows


def load_done(dt):
    if not STATE.exists():
        return {}
    records = [json.loads(line) for line in STATE.read_text(encoding="utf-8").splitlines() if line.strip()]
    return {record["key"]: record["input_hash"] for record in records
            if record.get("model") == MODEL_TAG and record.get("dt") == dt and record.get("input_hash")}


# ---- 1. 고르기 ----
def select_todo(dt, include_skipped=False, top_apps=0, since_days=0):
    """아직 Qwen 을 안 거친 청크를 우선순위 순으로. embedding 칼럼은 읽지 않는다."""
    ck = read_parquet_dir(out_dir("embeddings/patch_chunk", dt),
                          columns=["gid", "seq", "text", "embedding_status", "model_version"])
    if ck.empty:
        sys.exit("patch_chunk 없음. embed_chunks.py 먼저")
    done = load_done(dt)
    ck["key"] = ck.gid + ":" + ck.seq.astype(str)
    ck["input_hash"] = ck.text.map(lambda text: qwen_worker.input_hash(*qwen_worker.split_text(text)))
    completed_input = ck.key.map(done).eq(ck.input_hash)
    todo = ck[~completed_input & (ck.model_version != MODEL_TAG)]
    if not include_skipped:
        todo = todo[todo.embedding_status != "skipped"]   # 규칙이 변경점을 못 찾은 청크는 기본 제외(호출 1/3 절약)
    print(f"chunks total={len(ck):,} done={len(done):,} todo={len(todo):,}")

    news = read_news(None, columns=["gid", "appid", "published_ts"], patch_only=False)[["gid", "appid", "published_ts"]]
    todo = todo.merge(news, on="gid", how="left")
    if since_days:
        cut = time.time() - since_days * 86400
        todo = todo[todo.published_ts.fillna(0) >= cut]
        print(f"  최근 {since_days}일 공지만: {len(todo):,}")
    if APP_RANK.exists():
        rank = pd.read_csv(APP_RANK)[["appid", "rank"]]
        todo = todo.merge(rank, on="appid", how="left")
        todo["rank"] = todo["rank"].fillna(10**9)
        if top_apps:
            todo = todo[todo["rank"] <= top_apps]
            print(f"  인기 상위 {top_apps} 게임만: {len(todo):,} (게임 {todo.appid.nunique():,})")
        todo = todo.sort_values(["rank", "published_ts"], ascending=[True, False])
    else:
        if top_apps:
            print(f"  경고: {APP_RANK} 없음 — --top-apps 무시, 최신순만")
        todo = todo.sort_values("published_ts", ascending=False)
    print(f"  대상 {len(todo):,} 청크 ≈ {len(todo)*1.5/3600:.0f}시간(1.5초/청크, 한 대)")
    return todo[["key", "gid", "seq", "text", "appid", "published_ts"]].reset_index(drop=True)


def write_shards(todo, n, d=SHARDS):
    """우선순위 순서를 유지하며 n 개로 돌려 나눈다(각 조각이 인기·최신 순으로 같은 분포)."""
    d = Path(d); d.mkdir(parents=True, exist_ok=True)
    for f in d.glob("shard-*.jsonl"):
        f.unlink()
    files = [(d / f"shard-{i+1:02d}.jsonl").open("w", encoding="utf-8") for i in range(n)]
    for i, r in enumerate(todo.itertuples(index=False)):
        files[i % n].write(json.dumps({"key": r.key, "gid": r.gid, "seq": int(r.seq), "text": r.text},
                                      ensure_ascii=False) + "\n")
    for f in files:
        f.close()
    each = len(todo) / n
    print(f"shards={n} × {each:,.0f} 청크 ≈ 대당 {each*1.5/3600:.1f}시간 → {d}")


def verify_sources(chunk_dir, records):
    """응답이 현재 입력 청크에서 만들어졌는지 확인한다. 검증 전에는 파일을 교체하지 않는다."""
    keys = pa.array(sorted(records))
    found = set()
    for path in sorted(chunk_dir.glob("part-*.parquet")):
        table = pq.read_table(path, columns=["gid", "seq", "text"])
        row_keys = pc.binary_join_element_wise(table["gid"], pc.cast(table["seq"], pa.string()), ":")
        for chunk in table.filter(pc.is_in(row_keys, value_set=keys)).to_pylist():
            key = f"{chunk['gid']}:{chunk['seq']}"
            record = records[key]
            title, context, text = qwen_worker.split_text(chunk["text"])
            expected = qwen_worker.input_hash(title, context, text)
            actual = qwen_worker.input_hash(record.get("title", ""), record["context"], record["text"])
            if expected != actual or record.get("input_hash") != expected:
                raise ValueError("Qwen result does not match the current source chunk: " + key)
            found.add(key)
    if found != set(records):
        raise ValueError("Qwen result references missing source chunks")


# ---- 3. 반영 ----
def apply(dt, raw_dirs):
    """워커 결과 jsonl 을 읽어 patch_change 를 교체하고 patch_chunk.model_version 을 바꾼다."""
    recs = {}
    files = [f for d in raw_dirs for f in sorted(Path(d).glob("facts-*.jsonl"))]
    for f in files:
        with f.open(encoding="utf-8") as source:
            for line in source:
                if line.strip():
                    r = json.loads(line)
                    if r.get("model") == MODEL_TAG:
                        recs[f"{r['gid']}:{r['seq']}"] = r      # 같은 판본의 결과만 현재 판본으로 반영
    done = load_done(dt)
    new = {k: r for k, r in recs.items() if not r.get("input_hash") or done.get(k) != r["input_hash"]}
    print(f"결과 파일 {len(files)}개 · 청크 {len(recs):,} · 새로 반영 {len(new):,}")
    if not new:
        return 0

    verify_sources(out_dir("embeddings/patch_chunk", dt), new)

    # 원본 보관과 Parquet 교체 전에 전 청크를 검사한다. 한 청크의 불완전한 결과로 기존 행을 지우지 않는다.
    rows = []
    for r in new.values():
        rows += facts_to_rows(r["gid"], int(r["seq"]), r["context"], r["text"], r["changes"], r.get("title", ""))

    # 원본 응답 보관 (HDFS /embeddings/qwen_raw/dt=D). 컬럼·매핑을 바꿔도 재실행 없이 여기서 다시 뽑는다
    raw_dir = out_dir("embeddings/qwen_raw", dt); raw_dir.mkdir(parents=True, exist_ok=True)
    for f in files:
        if f.parent.resolve() != raw_dir.resolve():
            shutil.copy2(f, raw_dir / f"{f.parent.name}-{f.name}")
    (raw_dir / SUCCESS).touch()

    keys = set(new)

    ch_dir = out_dir("embeddings/patch_change", dt)
    ch = read_parquet_dir(ch_dir)
    if "model_version" not in ch:
        ch["model_version"] = RULE_VERSION   # 9/18 이전 embed_chunks 산출물(칼럼 없음) 호환
    ch["key"] = ch.gid + ":" + ch.seq.astype(str)
    ch = ch[~ch.key.isin(keys)].drop(columns="key")
    ch = pd.concat([ch, pd.DataFrame(rows, columns=PATCH_CHANGE_SCHEMA.names)], ignore_index=True)
    write_parquet_dir(ch.sort_values(["gid", "seq", "change_seq"]), ch_dir, schema=PATCH_CHANGE_SCHEMA)

    # patch_chunk 는 part 파일마다 두 칼럼만 바꾼다(embedding 을 pandas 로 올리지 않는다)
    ck_dir = out_dir("embeddings/patch_chunk", dt)
    ts, keyset, touched = now_ts(), pa.array(sorted(keys)), 0
    for f in sorted(ck_dir.glob("part-*.parquet")):
        t = pq.read_table(f)
        key = pc.binary_join_element_wise(t["gid"], pc.cast(t["seq"], pa.string()), ":")
        mask = pc.is_in(key, value_set=keyset)
        if not pc.any(mask).as_py():
            continue
        t = t.set_column(t.schema.get_field_index("model_version"), "model_version",
                         pc.if_else(mask, pa.scalar(MODEL_TAG), t["model_version"]))
        t = t.set_column(t.schema.get_field_index("processed_at"), "processed_at",
                         pc.if_else(mask, pa.scalar(ts, pa.int64()), t["processed_at"]))
        tmp = f.with_suffix(".parquet.tmp")
        pq.write_table(t, tmp, compression="snappy"); tmp.replace(f)
        touched += pc.sum(pc.cast(mask, pa.int64())).as_py()
    (ck_dir / SUCCESS).touch()

    STATE.parent.mkdir(parents=True, exist_ok=True)
    with STATE.open("a", encoding="utf-8") as f:
        for k in keys:
            f.write(json.dumps({"key": k, "dt": dt, "model": MODEL_TAG, "input_hash": new[k]["input_hash"], "ts": ts}) + "\n")
    print(f"반영: patch_chunk {touched:,}행 model_version 갱신 · patch_change {len(rows):,}행 교체")
    if rows:
        df = pd.DataFrame(rows)
        print("  target filled", f"{df.target.notna().mean():.0%}", "| attribute filled", f"{df.attribute.notna().mean():.0%}",
              "| needs_review", f"{(df.validation_status == 'needs_review').mean():.0%}")
    return len(new)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dt", required=True)
    ap.add_argument("--max-seconds", type=int, default=8 * 3600, help="시간 예산. 넘으면 중간 저장 후 종료")
    ap.add_argument("--limit", type=int, default=0, help="청크 수 제한(테스트)")
    ap.add_argument("--include-skipped", action="store_true", help="규칙이 변경점을 못 찾은 청크도 보냄")
    ap.add_argument("--top-apps", type=int, default=0, help="인기 상위 K 게임만 (state/app_rank.csv)")
    ap.add_argument("--since-days", type=int, default=0, help="최근 N 일 공지만")
    ap.add_argument("--make-shards", type=int, default=0, help="N 개 조각(JSONL)만 만들고 끝. 여러 노트북 분산용")
    ap.add_argument("--apply", nargs="+", metavar="DIR", help="워커 결과 폴더들을 반영하고 끝")
    a = ap.parse_args()

    if a.apply:
        apply(a.dt, a.apply)
        return
    todo = select_todo(a.dt, a.include_skipped, a.top_apps, a.since_days)
    if a.limit:
        todo = todo.head(a.limit)
    if todo.empty:
        print("할 것 없음"); return
    if a.make_shards:
        write_shards(todo, a.make_shards)
        return
    # 한 대로: 조각 하나 → 워커 → 반영
    write_shards(todo, 1, SHARDS / "local")
    raw_dir = out_dir("embeddings/qwen_raw", a.dt)
    n = qwen_worker.run(SHARDS / "local" / "shard-01.jsonl", raw_dir, max_seconds=a.max_seconds)
    applied = apply(a.dt, [raw_dir])
    if n == 0 and applied == 0:
        sys.exit(f"Qwen 결과 0건. Ollama 상태 확인: {qwen_worker.DEFAULT_URL}")


if __name__ == "__main__":
    main()
