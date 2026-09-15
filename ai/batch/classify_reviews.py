# -*- coding: utf-8 -*-
"""리뷰 → EmbeddingGemma(Classification 프롬프트, 512) → 로지스틱 회귀 5개 → review_topic Parquet.

입력  {AI_WORK_DIR}/in/review_raw/base/*.parquet 또는 delta/dt=D/*.parquet  (ReviewSchema.REVIEW_RAW 컬럼)
출력  {AI_WORK_DIR}/out/review_topic/dt=D/   (recommendationid, appid, topic_id, score) — 리뷰 하나에 여러 행
실행  python classify_reviews.py --dt 2026-09-11 [--base] [--limit N]

분류기: 0905 노트북 08 에서 학습한 logreg-gemma512-v1.joblib (토픽별 {model, threshold}).
        환경 변수 CLASSIFIER_PATH, 기본 ai/models/logreg-gemma512-v1.joblib (git 밖).
토픽 ID: balance=1, bug=2, ui=3, ops=4, bm=5  (topic 테이블 시드와 맞춰야 함 — CONTRACT.md 4절)
벡터는 저장하지 않는다. 30바이트 이하 리뷰는 건너뛴다(노트북 08 규칙).
언어: 기본 english 만(--languages). 9/14 러·중 60건 사람 검수에서 토픽 붙은 행 정확도 러 2%·중 42% → 비영어는 토픽 없이 둔다.
"""
import argparse
import os
import sys
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from common import (EMBED_DIM, EMBED_MODEL_ID, REVIEW_TOPIC_SCHEMA, WORK, has_success,  # noqa: E402
                    out_dir, read_parquet_dir, write_parquet_dir)

TOPICS = ["balance", "bug", "ui", "ops", "bm"]
TOPIC_ID = {t: i + 1 for i, t in enumerate(TOPICS)}
CLF_PATH = Path(os.environ.get("CLASSIFIER_PATH", Path(__file__).parent.parent / "models" / "logreg-gemma512-v1.joblib"))
MAX_CHARS = 1500
MIN_BYTES = 30


def load_embedder():
    import torch
    from sentence_transformers import SentenceTransformer
    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.bfloat16 if device == "cuda" else torch.float32
    m = SentenceTransformer(EMBED_MODEL_ID, device=device, model_kwargs={"torch_dtype": dtype})
    m.max_seq_length = 256
    return m


def embed(model, texts, batch_size=64):
    """노트북 08 과 동일: 768 → 앞 512 절단 → 재정규화. prompt_name=Classification."""
    E = model.encode(list(texts), batch_size=batch_size, prompt_name="Classification",
                     normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)[:, :EMBED_DIM]
    return E / np.linalg.norm(E, axis=1, keepdims=True)


def classify(reviews, model, clf, batch_size=64):
    rows = []
    t0 = time.time()
    texts = reviews["review_text"].str[:MAX_CHARS].tolist()
    E = embed(model, texts, batch_size)
    for t in TOPICS:
        p = clf[t]["model"].predict_proba(E)[:, 1]
        hit = p >= clf[t]["threshold"]
        rows.append(pd.DataFrame({"recommendationid": reviews["recommendationid"].values[hit],
                                  "appid": reviews["appid"].values[hit],
                                  "topic_id": TOPIC_ID[t], "score": p[hit].astype(np.float32)}))
    out = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=REVIEW_TOPIC_SCHEMA.names)
    dt = time.time() - t0
    print(f"classified {len(reviews)} reviews in {dt:.1f}s ({len(reviews)/max(dt,1e-6):.0f}/s) → {len(out)} topic rows, "
          f"no-topic={len(reviews) - out.recommendationid.nunique()}")
    return out.sort_values(["appid", "recommendationid", "topic_id"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dt", required=True)
    ap.add_argument("--base", action="store_true", help="delta/dt=D 대신 base 전체(초기 1회)")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--languages", default=os.environ.get("TOPIC_LANGUAGES", "english"),
                    help="토픽을 붙일 리뷰 언어(쉼표). 9/14 사람 검수: 러시아어 정확도 2%·중국어 42% → 기본 english 만. 'all' 이면 전부")
    a = ap.parse_args()

    src = WORK / "in" / "review_raw" / ("base" if a.base else f"delta/dt={a.dt}")
    if not has_success(src):
        sys.exit(f"입력에 _SUCCESS 없음: {src}")
    rv = read_parquet_dir(src, columns=["recommendationid", "appid", "review_text", "language_code", "created_ts", "updated_ts"])
    # base+delta 에 같은 리뷰가 여러 판 있을 수 있다(수정본 13.7%, 재수집 완전 중복). 토픽은 최신 한 벌에만 붙인다 (common/ReviewLake.latest 와 같은 기준)
    before = len(rv)
    rv = (rv.sort_values(["recommendationid", "updated_ts"], ascending=[True, False])
            .drop_duplicates("recommendationid", keep="first"))
    if before != len(rv):
        print(f"dedupe by recommendationid (latest updated_ts): {before} -> {len(rv)}")
    rv = rv[rv["review_text"].fillna("").str.encode("utf-8").str.len() > MIN_BYTES]
    if a.languages != "all":
        langs = {x.strip() for x in a.languages.split(",")}
        before = len(rv); rv = rv[rv["language_code"].isin(langs)]
        print(f"language filter {sorted(langs)}: {before} -> {len(rv)}")
    if a.limit:
        rv = rv.head(a.limit)
    print(f"reviews={len(rv)} from {src}")
    if rv.empty:
        sys.exit("리뷰 0건")
    if not CLF_PATH.exists():
        sys.exit(f"분류기 없음: {CLF_PATH}")
    clf = joblib.load(CLF_PATH)
    out = classify(rv, load_embedder(), clf)
    p = write_parquet_dir(out, out_dir("review_topic", a.dt), schema=REVIEW_TOPIC_SCHEMA)
    print("topic dist", out.topic_id.map({v: k for k, v in TOPIC_ID.items()}).value_counts().to_dict())
    print("wrote", p, p.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
