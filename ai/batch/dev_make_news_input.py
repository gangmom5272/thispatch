# -*- coding: utf-8 -*-
"""개발용: PoC 공지 JSON(0904/poc/data/news_*.json) → news_raw 모양 Parquet.

HDFS 의 /news_raw/dt=D 가 준비되기 전, 같은 컬럼으로 로컬 입력을 만든다.
is_patch 는 PoC 판정 결과(results/patch_judgment_v2_{appid}.csv)가 있으면 그것을, 없으면 제목·본문 규칙으로.
실행  python dev_make_news_input.py --src C:/.../0904/poc --dt 2026-09-11
"""
import argparse
import csv
import json
import re
import sys
from pathlib import Path

import pandas as pd
import pyarrow as pa

sys.path.insert(0, str(Path(__file__).parent))
from common import in_dir, write_parquet_dir  # noqa: E402

NEWS_SCHEMA = pa.schema([
    ("gid", pa.string()), ("appid", pa.int64()), ("title", pa.string()), ("contents", pa.string()),
    ("published_at", pa.int64()), ("is_patch", pa.bool_()),
])
VERB = re.compile(r"(increased|decreased|reduced|buffed|nerfed|fixed|adjusted|changed|added|removed|lowered|raised|improved|tweaked|rebalanced|reworked|replaced|resolved|corrected|no longer|can now|will now|updated)", re.I)
KW = re.compile(r"(patch|hotfix|update|balance|changelog|notes|fix)", re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="0904/poc 폴더")
    ap.add_argument("--dt", required=True)
    a = ap.parse_args()
    src = Path(a.src)
    rows = []
    for f in sorted(src.glob("data/news_*.json")):
        appid = int(f.stem.split("_")[1])
        jud = {}
        jf = src / f"results/patch_judgment_v2_{appid}.csv"
        if jf.exists():
            jud = {r["gid"]: r["is_patch"] == "True" for r in csv.DictReader(open(jf, encoding="utf-8-sig"))}
        for n in json.load(open(f, encoding="utf-8")):
            body = n.get("contents") or ""
            is_patch = jud.get(n["gid"]) if n["gid"] in jud else bool(KW.search(n["title"]) and len(VERB.findall(body)) >= 5)
            rows.append({"gid": str(n["gid"]), "appid": appid, "title": n["title"], "contents": body,
                         "published_at": int(n["date"]), "is_patch": bool(is_patch)})
    df = pd.DataFrame(rows)
    p = write_parquet_dir(df, in_dir("news_raw", a.dt), schema=NEWS_SCHEMA)
    print(f"news={len(df)} is_patch={int(df.is_patch.sum())} games={df.appid.nunique()} -> {p}")


if __name__ == "__main__":
    main()
