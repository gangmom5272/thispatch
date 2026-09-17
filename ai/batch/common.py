# -*- coding: utf-8 -*-
"""AI 노드 배치 공통: 경로·Parquet 입출력·_SUCCESS 마커.

경로는 전부 환경 변수. 코드에 절대 경로를 두지 않는다(Windows 개발 → WSL 운영 이동 대비).
  AI_WORK_DIR   로컬 작업 루트. 아래에 HDFS 와 같은 구조를 둔다.
                 {AI_WORK_DIR}/in/news_raw/dt=D        (hdfs dfs -get 결과)
                 {AI_WORK_DIR}/out/embeddings/patch_chunk/dt=D   (hdfs dfs -put 대상)
  OLLAMA_URL    기본 http://127.0.0.1:11434
컬럼·경로 규약은 ai/CONTRACT.md 와 common/HdfsPaths.java 를 따른다.
"""
import os
import time
from pathlib import Path

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq

WORK = Path(os.environ.get("AI_WORK_DIR", Path.home() / "ai_work"))
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")
EMBED_MODEL_ID = os.environ.get("EMBED_MODEL_ID", "google/embeddinggemma-300m")
EMBED_MODEL_TAG = "embeddinggemma-300m-bf16-512"  # patch_chunk.embedding_model 에 기록
EMBED_DIM = 512
SUCCESS = "_SUCCESS"


def in_dir(name, dt=None):
    p = WORK / "in" / name
    return p / f"dt={dt}" if dt else p


def out_dir(name, dt):
    return WORK / "out" / name / f"dt={dt}"


def now_ts():
    return int(time.time())


def has_success(d):
    return (Path(d) / SUCCESS).exists()


NEWS_COLS = ["gid", "appid", "title", "contents", "published_ts", "is_patch"]


def read_news(dt=None, columns=None, patch_only=True):
    """news_raw 읽기. dt 를 주면 그 날 파티션(_SUCCESS 필요), None 이면 dt=* 전체.
    같은 공지가 여러 날 들어올 수 있어(수집 담당 NewsLake.latest 와 같은 기준) gid 당 collected_ts 최신 한 벌만 남긴다."""
    cols = list(dict.fromkeys((columns or NEWS_COLS) + ["collected_ts"]))
    root = in_dir("news_raw")
    parts = [root / f"dt={dt}"] if dt else sorted(root.glob("dt=*"))
    parts = [p for p in parts if has_success(p)]
    if not parts:
        return pd.DataFrame(columns=cols)
    frames = []
    for p in parts:
        have = set(pq.read_schema(next(p.glob("*.parquet"))).names) if list(p.glob("*.parquet")) else set()
        want = [c for c in cols if c in have] + (["published_at"] if "published_ts" in cols and "published_ts" not in have and "published_at" in have else [])
        frames.append(read_parquet_dir(p, columns=want or None))
    df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame(columns=cols)
    if "published_ts" not in df and "published_at" in df:   # 9/15 이전 개발용 입력(dev_make_news_input 구판) 호환
        df = df.rename(columns={"published_at": "published_ts"})
    if "collected_ts" in df:
        df = df.sort_values(["gid", "collected_ts"], ascending=[True, False])
    df = df.drop_duplicates("gid", keep="first")
    if patch_only:
        if "is_patch" not in df:
            # 칼럼이 없으면 전량이 통과해 비패치 공지까지 임베딩·Qwen 대상이 된다(9/16 김경민 지적). 조용히 돌지 않고 멈춘다.
            raise SystemExit("news_raw 에 is_patch 칼럼이 없습니다. NewsToParquet(PatchClassifier) 산출물인지 확인하세요: " + str(root))
        df = df[df["is_patch"] == True]  # noqa: E712
    return df.reset_index(drop=True)


def read_parquet_dir(d, columns=None):
    """폴더 안 *.parquet 전부를 하나의 DataFrame 으로. 없으면 빈 프레임."""
    files = sorted(Path(d).glob("*.parquet"))
    if not files:
        return pd.DataFrame(columns=columns or [])
    return pd.concat([pq.read_table(f, columns=columns).to_pandas() for f in files], ignore_index=True)


def write_parquet_dir(df, d, schema=None, part="part-00000.parquet", mark=True):
    """폴더를 비우고 다시 쓴다(같은 dt 재실행 = 통째로 덮어쓰기). 끝나면 _SUCCESS."""
    d = Path(d)
    d.mkdir(parents=True, exist_ok=True)
    for f in d.iterdir():
        if f.is_file():
            f.unlink()
    table = pa.Table.from_pandas(df, schema=schema, preserve_index=False)
    pq.write_table(table, d / part, compression="snappy")
    if mark:
        (d / SUCCESS).touch()
    return d / part


# ---- 계약 스키마 (CONTRACT.md 3절) ----
PATCH_CHUNK_SCHEMA = pa.schema([
    ("gid", pa.string()),
    ("seq", pa.int16()),
    ("text", pa.string()),
    ("extraction_status", pa.string()),
    ("embedding_status", pa.string()),
    ("embedding", pa.list_(pa.float32())),
    ("embedding_model", pa.string()),
    ("model_version", pa.string()),
    ("processed_at", pa.int64()),
])

PATCH_CHANGE_SCHEMA = pa.schema([
    ("gid", pa.string()),
    ("seq", pa.int16()),
    ("change_seq", pa.int16()),
    ("change_type", pa.string()),
    ("direction", pa.string()),
    ("target_type", pa.string()),
    ("target", pa.string()),
    ("attribute", pa.string()),
    ("evidence_quote", pa.string()),
    ("validation_status", pa.string()),
])

REVIEW_TOPIC_SCHEMA = pa.schema([
    ("recommendationid", pa.int64()),
    ("appid", pa.int64()),
    ("topic_id", pa.int16()),
    ("score", pa.float32()),
])
