# -*- coding: utf-8 -*-
"""개발용 검색 검증: pgvector 없이 Parquet + numpy 로 '질의 임베딩 → 코사인 상위 30 → 슬롯 필터' 를 재현한다.

운영에서는 백엔드 SQL 이 하는 일과 같은 계산이다:
  ORDER BY embedding <=> :q LIMIT 30  →  direction·change_type 필수 일치, target_type 가점
실행  python dev_search_check.py --dt 2026-09-14 --plan "Reduce enemy health by 20% in hard mode" [--direction decrease --change-type modify --target-type enemy]
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from chunking import build_input_text  # noqa: E402
from common import in_dir, out_dir, read_parquet_dir  # noqa: E402
from embed_chunks import load_embedder  # noqa: E402
from rules import slots  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dt", required=True)
    ap.add_argument("--plan", required=True, help="기획안 변경 문장 1개")
    ap.add_argument("--title", default="")
    ap.add_argument("--direction"); ap.add_argument("--change-type"); ap.add_argument("--target-type")
    ap.add_argument("--k", type=int, default=30)
    a = ap.parse_args()

    ck = read_parquet_dir(out_dir("embeddings/patch_chunk", a.dt))
    ch = read_parquet_dir(out_dir("embeddings/patch_change", a.dt))
    news = read_parquet_dir(in_dir("news_raw", a.dt), columns=["gid", "appid", "title", "published_at"])
    ck = ck[ck.embedding_status == "succeeded"].reset_index(drop=True)
    E = np.vstack(ck.embedding.to_numpy()).astype(np.float32)
    print(f"index: {len(ck)} chunks × {E.shape[1]} dim, games={news.appid.nunique()}")

    # 질의 슬롯: 인자로 주지 않으면 규칙으로
    s = slots(a.plan, a.title) or {}
    q_dir = a.direction or s.get("direction"); q_ct = a.change_type or s.get("change_type"); q_tt = a.target_type or s.get("target_type")
    print(f"query slots: change_type={q_ct} direction={q_dir} target_type={q_tt}")

    model = load_embedder()
    q = model.encode([build_input_text(a.title, "", a.plan)], normalize_embeddings=True)[0].astype(np.float32)
    sim = E @ q                                   # 정규화 벡터라 내적 = 코사인
    top = np.argsort(-sim)[:a.k]
    cand = ck.iloc[top].assign(sim=sim[top])
    # 후보 30 안에서 0~100 정규화 (화면 표기용)
    lo, hi = cand.sim.min(), cand.sim.max()
    cand = cand.assign(score=((cand.sim - lo) / max(hi - lo, 1e-6) * 100).round(1))

    # 슬롯 필터: direction·change_type 필수, target_type 가점
    key = ch.assign(key=ch.gid + ":" + ch.seq.astype(str)).groupby("key").agg(
        directions=("direction", lambda x: set(x)), types=("change_type", lambda x: set(x)), targets=("target_type", lambda x: set(x)),
        quote=("evidence_quote", "first"))
    cand = cand.assign(key=cand.gid + ":" + cand.seq.astype(str)).join(key, on="key")
    cand["ok"] = cand.apply(lambda r: (not q_dir or q_dir in (r.directions or set())) and (not q_ct or q_ct in (r.types or set())), axis=1)
    cand["tt_bonus"] = cand.apply(lambda r: bool(q_tt and q_tt != "unknown" and q_tt in (r.targets or set())), axis=1)
    cand = cand.merge(news, on="gid", how="left").sort_values(["ok", "tt_bonus", "sim"], ascending=False)

    print(f"\ncandidates={len(cand)} pass_filter={int(cand["ok"].sum())} target_bonus={int(cand.tt_bonus.sum())}\n")
    for r in cand.head(12).itertuples():
        mark = "✓" if r.ok else " "
        body = r.text.split("Change: ")[-1][:90].replace("\n", " ")
        print(f"{mark}{'+' if r.tt_bonus else ' '} {r.score:5.1f}  {str(r.appid):>8} {str(r.title)[:28]:28} | {body}")
    return cand


if __name__ == "__main__":
    main()
