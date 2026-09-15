# -*- coding: utf-8 -*-
"""개발용: PoC 리뷰 JSON(0904/poc/data/reviews_*.json) → review_raw/delta/dt=D 모양 Parquet.

컬럼은 common/ReviewSchema.REVIEW_RAW 중 분류에 필요한 것만. 값은 받은 그대로(unix초 long).
실행  python dev_make_review_input.py --src C:/.../0904/poc --dt 2026-09-11
"""
import argparse
import json
import sys
from pathlib import Path

import pandas as pd
import pyarrow as pa

sys.path.insert(0, str(Path(__file__).parent))
from common import WORK, write_parquet_dir  # noqa: E402

SCHEMA = pa.schema([
    ("recommendationid", pa.int64()), ("appid", pa.int64()), ("review_text", pa.string()),
    ("language_code", pa.string()), ("voted_up", pa.bool_()), ("created_ts", pa.int64()), ("updated_ts", pa.int64()),
    ("playtime_at_review", pa.int32()),
])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--dt", required=True)
    a = ap.parse_args()
    rows = []
    for f in sorted(Path(a.src).glob("data/reviews_*.json")):
        for r in json.load(open(f, encoding="utf-8")):
            rows.append({"recommendationid": int(r["recommendation_id"]), "appid": int(r["appid"]),
                         "review_text": r.get("review_text") or "", "language_code": r.get("language") or "",
                         "voted_up": str(r.get("voted_up")) == "True", "created_ts": int(r["timestamp_created"]),
                         "updated_ts": int(r["timestamp_updated"]),
                         "playtime_at_review": int(r["playtime_at_review"]) if r.get("playtime_at_review") not in (None, "", "None") else None})
    df = pd.DataFrame(rows).astype({"playtime_at_review": "Int32"})
    p = write_parquet_dir(df, WORK / "in" / "review_raw" / f"delta/dt={a.dt}", schema=SCHEMA)
    print(f"reviews={len(df)} games={df.appid.nunique()} langs={df.language_code.nunique()} -> {p}")


if __name__ == "__main__":
    main()
