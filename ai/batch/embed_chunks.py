# -*- coding: utf-8 -*-
"""공지 → 청크 → 규칙 슬롯 → 임베딩(512) → patch_chunk / patch_change Parquet.

입력  {AI_WORK_DIR}/in/news_raw/dt=D/*.parquet   (news 테이블 컬럼, is_patch=true 만 처리)
출력  {AI_WORK_DIR}/out/embeddings/patch_chunk/dt=D/
      {AI_WORK_DIR}/out/embeddings/patch_change/dt=D/
실행  python embed_chunks.py --dt 2026-09-11 [--limit N] [--no-embed] [--all-dt]

실측(2026-09-11, RTX 4070 8GB, HF bf16, batch 64): 127청크/초.
"""
import argparse
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from chunking import CHUNK_VERSION, build_input_text, chunk_notice, split_sentences  # noqa: E402
from common import (EMBED_DIM, EMBED_MODEL_ID, EMBED_MODEL_TAG, PATCH_CHANGE_SCHEMA,  # noqa: E402
                    PATCH_CHUNK_SCHEMA, has_success, in_dir, now_ts, out_dir, read_news, write_parquet_dir)
from rules import RULE_VERSION, slots  # noqa: E402


def load_embedder():
    import torch
    from sentence_transformers import SentenceTransformer
    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.bfloat16 if device == "cuda" else torch.float32
    return SentenceTransformer(EMBED_MODEL_ID, device=device, model_kwargs={"torch_dtype": dtype},
                               truncate_dim=EMBED_DIM)


def build_rows(news):
    """공지 DataFrame → (patch_chunk 행, patch_change 행). 임베딩 컬럼은 비워 둔다."""
    chunks, changes = [], []
    seen = set()   # (appid, 본문 정규화) — 같은 게임이 공지마다 반복하는 문장(면책 문구·소제목)은 첫 1개만 임베딩 (Top50 실측 28%)
    for n in news.itertuples(index=False):
        for seq, c in enumerate(chunk_notice(n.contents), start=1):
            text = build_input_text(n.title, c["context"], c["text"])
            dup_key = (int(n.appid), " ".join(c["text"].lower().split()))
            if dup_key in seen:
                chunks.append({"gid": n.gid, "seq": seq, "text": text, "extraction_status": "succeeded",
                               "embedding_status": "duplicate", "embedding": None,
                               "embedding_model": None, "model_version": RULE_VERSION, "processed_at": now_ts()})
                continue
            seen.add(dup_key)
            # 변경점은 문장 단위. 한 불릿에 변경 문장이 여럿이면 change_seq 1,2,3… (실측 12%가 해당)
            change_seq = 0
            for sent in split_sentences(c["text"]):
                s = slots(sent, c["context"])
                if not s:
                    continue
                change_seq += 1
                changes.append({"gid": n.gid, "seq": seq, "change_seq": change_seq, **s,
                                "target": None, "attribute": None,   # 규칙은 이름·속성을 못 뽑는다. Qwen 백필이 채움
                                "evidence_quote": sent, "validation_status": "valid", "model_version": RULE_VERSION})
            # 변경점 없는 청크(소제목·인사말·설명)는 저장만 하고 임베딩하지 않는다 → 검색 후보에서 제외 (실측 32%)
            chunks.append({"gid": n.gid, "seq": seq, "text": text, "extraction_status": "succeeded",
                           "embedding_status": "pending" if change_seq else "skipped", "embedding": None,
                           "embedding_model": None, "model_version": RULE_VERSION, "processed_at": now_ts()})
    return pd.DataFrame(chunks), pd.DataFrame(changes)


def embed_rows(chunks, batch_size=64):
    model = load_embedder()
    idx = chunks.index[chunks["embedding_status"] == "pending"]
    t0 = time.time()
    vecs = model.encode(chunks.loc[idx, "text"].tolist(), batch_size=batch_size, normalize_embeddings=True,
                        show_progress_bar=False)
    dt = time.time() - t0
    vecs = np.asarray(vecs, dtype=np.float32)
    ok = np.isfinite(vecs).all(axis=1)
    chunks.loc[idx, "embedding"] = pd.Series([v.tolist() if o else None for v, o in zip(vecs, ok)], index=idx)
    chunks.loc[idx, "embedding_status"] = np.where(ok, "succeeded", "failed")
    chunks.loc[idx, "embedding_model"] = EMBED_MODEL_TAG
    chunks["processed_at"] = now_ts()
    print(f"embedded {len(idx)} of {len(chunks)} chunks in {dt:.1f}s ({len(idx)/max(dt,1e-6):.0f}/s), "
          f"failed={int((~ok).sum())}, skipped={int((chunks['embedding_status'] == 'skipped').sum())}")
    return chunks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dt", required=True)
    ap.add_argument("--limit", type=int, default=0, help="공지 수 제한(테스트)")
    ap.add_argument("--no-embed", action="store_true", help="청크·규칙만, 임베딩 생략")
    ap.add_argument("--all-dt", action="store_true", help="초기 전량: news_raw/dt=* 전부 읽고 gid 최신 한 벌만 (출력은 --dt 폴더)")
    a = ap.parse_args()

    src = in_dir("news_raw") if a.all_dt else in_dir("news_raw", a.dt)
    if not a.all_dt and not has_success(src):
        sys.exit(f"입력에 _SUCCESS 없음: {src}")
    news = read_news(None if a.all_dt else a.dt)
    if a.limit:
        news = news.head(a.limit)
    print(f"news(is_patch)={len(news)} from {src}{' (all dt)' if a.all_dt else ''}")

    chunks, changes = build_rows(news)
    print(f"chunks={len(chunks)} changes={len(changes)} (chunk={CHUNK_VERSION}, rule={RULE_VERSION})")
    if chunks.empty:
        sys.exit("청크 0개")
    if not a.no_embed:
        chunks = embed_rows(chunks)

    p1 = write_parquet_dir(chunks, out_dir("embeddings/patch_chunk", a.dt), schema=PATCH_CHUNK_SCHEMA)
    p2 = write_parquet_dir(changes if not changes.empty else pd.DataFrame(columns=PATCH_CHANGE_SCHEMA.names),
                           out_dir("embeddings/patch_change", a.dt), schema=PATCH_CHANGE_SCHEMA)
    print("wrote", p1, p1.stat().st_size // 1024, "KB;", p2, p2.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
