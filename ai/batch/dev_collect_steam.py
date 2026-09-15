# -*- coding: utf-8 -*-
"""개발용 로컬 수집: Steam 공개 API → news_raw / review_raw Parquet (HDFS 없이 파이프라인 검증).

- 뉴스: ISteamNews/GetNewsForApp (키 불필요), 최근 24개월, 게임당 최대 500건
- 리뷰: store.steampowered.com/appreviews (키 불필요), filter=recent, 게임당 N페이지×100건
- 패치 판정: ai/docs/PatchJudge.java 와 같은 규칙(Python 판)
출력 컬럼은 CONTRACT.md 2절(news 테이블 / ReviewSchema)과 같다.

실행  python dev_collect_steam.py --apps apps.txt --dt 2026-09-14 --review-pages 3
      apps.txt: 한 줄에 appid[,이름]
"""
import argparse
import json
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pandas as pd
import pyarrow as pa

sys.path.insert(0, str(Path(__file__).parent))
from common import WORK, write_parquet_dir  # noqa: E402

NEWS_SCHEMA = pa.schema([("gid", pa.string()), ("appid", pa.int64()), ("title", pa.string()), ("contents", pa.string()),
                         ("url", pa.string()), ("published_ts", pa.int64()), ("feed_tags", pa.string()),
                         ("is_patch", pa.bool_()), ("patch_reason", pa.string())])
REVIEW_SCHEMA = pa.schema([("recommendationid", pa.int64()), ("appid", pa.int64()), ("steam_id", pa.string()),
                           ("review_text", pa.string()), ("language_code", pa.string()), ("created_ts", pa.int64()),
                           ("updated_ts", pa.int64()), ("voted_up", pa.bool_()), ("votes_up", pa.int32()),
                           ("playtime_at_review", pa.int32()), ("playtime_forever", pa.int32())])

# ---- 패치 판정 (PatchJudge.java 와 동일) ----
BB = re.compile(r"\[/?[a-zA-Z*][^\]]*\]")
VERB = re.compile(r"(increased|decreased|reduced|buffed|nerfed|fixed|adjusted|changed|added|removed|lowered|raised|improved|tweaked|rebalanced|reworked|replaced|resolved|corrected|no longer|can now|will now|now deals|now has|now costs|now takes|now grants|updated|scaled|capped|doubled|halved|disabled|enabled|renamed|restored|reverted)", re.I)
NEG = re.compile(r"(newsletter|\bsale\b|discount|%\s*off|\bevent\b|dev\s*diary|behind the scenes|deep dive|roadmap|survey|soundtrack|\bost\b|merch|stream|trailer|recap|wallpaper|contest|giveaway|free weekend|community spotlight|fan ?art|interview|anniversary|award|nomination|\bq&a\b|lore|comic|cosplay|kickstarter|state of the game|celebrating)", re.I)
PATCH_KW = re.compile(r"(patch|hotfix|hot-fix|update|fix(es|ed)?\b|patch notes|release notes|changelog|balance|version|\bv?\d+\.\d+(\.\d+)?\b)", re.I)
PREVIEW = re.compile(r"(preview|coming soon|upcoming|incoming|teaser|sneak peek|roadmap|what.s next|in development|announc(e|ing)|reveal|delay|postpone|arrives|will be|on \w+ \d+(st|nd|rd|th))", re.I)
RELEASE = re.compile(r"(out now|now available|now live|is live|has arrived|released|launch(es|ed)?\b|available now)", re.I)
VERSION = re.compile(r"\bv?\d+\.\d+(\.\d+)?\b")


def judge(title, contents, tags):
    t = title or ""
    verbs = len(VERB.findall(BB.sub(" ", contents or "")))
    ver = bool(VERSION.search(t))
    if "patchnotes" in (tags or "").lower():
        return True, "1:tag"
    if NEG.search(t) and not PATCH_KW.search(t) and verbs < 15:
        return False, "2:negative"
    if RELEASE.search(t) and (verbs >= 5 or ver):
        return True, "3:release"
    if PATCH_KW.search(t) and not PREVIEW.search(t) and (verbs >= 5 or ver):
        return True, "4:title_kw"
    if verbs >= 15:
        return True, "5:body_verbs"
    return False, "6:else"


def fetch_news(client, appid, since_ts, max_pages=6):
    """500건 상한(API)에 걸리면 enddate 를 가장 오래된 공지 직전으로 옮겨 24개월까지 이어 받는다(9/14: Top50 중 1게임이 11개월치만 받힘)."""
    rows, seen, enddate = [], set(), None
    for _ in range(max_pages):
        params = {"appid": appid, "count": 500, "maxlength": 0, "feeds": "steam_community_announcements"}
        if enddate:
            params["enddate"] = enddate
        r = client.get("https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/", params=params, timeout=60)
        items = r.json().get("appnews", {}).get("newsitems", [])
        new = 0
        for it in items:
            if it["gid"] in seen:
                continue
            seen.add(it["gid"]); new += 1
            if it["date"] < since_ts:
                continue
            tags = ",".join(it.get("tags") or [])
            ok, why = judge(it["title"], it.get("contents", ""), tags)
            rows.append({"gid": str(it["gid"]), "appid": appid, "title": it["title"], "contents": it.get("contents", ""),
                         "url": it.get("url", ""), "published_ts": int(it["date"]), "feed_tags": tags, "is_patch": ok, "patch_reason": why})
        if len(items) < 500 or new == 0 or min(it["date"] for it in items) < since_ts:
            break
        enddate = min(it["date"] for it in items) - 1
        time.sleep(0.3)
    return rows


def fetch_reviews(client, appid, pages):
    rows, cursor = [], "*"
    for _ in range(pages):
        r = client.get(f"https://store.steampowered.com/appreviews/{appid}",
                       params={"json": 1, "filter": "recent", "language": "all", "num_per_page": 100, "cursor": cursor,
                               "purchase_type": "all"}, timeout=60)
        d = r.json()
        for v in d.get("reviews", []):
            a = v.get("author", {})
            rows.append({"recommendationid": int(v["recommendationid"]), "appid": appid, "steam_id": str(a.get("steamid", "")),
                         "review_text": v.get("review", ""), "language_code": v.get("language", ""),
                         "created_ts": int(v["timestamp_created"]), "updated_ts": int(v.get("timestamp_updated", v["timestamp_created"])),
                         "voted_up": bool(v.get("voted_up")), "votes_up": int(v.get("votes_up", 0)),
                         "playtime_at_review": a.get("playtime_at_review"), "playtime_forever": a.get("playtime_forever")})
        cursor = d.get("cursor")
        if not cursor or not d.get("reviews"):
            break
        time.sleep(0.3)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apps", required=True)
    ap.add_argument("--dt", required=True)
    ap.add_argument("--months", type=int, default=24)
    ap.add_argument("--review-pages", type=int, default=3)
    a = ap.parse_args()
    apps = []
    for line in Path(a.apps).read_text(encoding="utf-8").splitlines():
        line = line.split("#")[0].strip()
        if line:
            apps.append(int(line.split(",")[0]))
    since = int((datetime.now(timezone.utc) - timedelta(days=30 * a.months)).timestamp())
    news, reviews = [], []
    with httpx.Client(headers={"User-Agent": "thispatch-dev/0.1"}) as client:
        for i, appid in enumerate(apps, 1):
            try:
                n = fetch_news(client, appid, since); news += n
                rv = fetch_reviews(client, appid, a.review_pages); reviews += rv
                print(f"[{i}/{len(apps)}] {appid}: news={len(n)} patch={sum(x['is_patch'] for x in n)} reviews={len(rv)}", flush=True)
            except Exception as e:  # noqa: BLE001
                print(f"[{i}/{len(apps)}] {appid}: FAIL {type(e).__name__} {str(e)[:80]}", flush=True)
            time.sleep(0.5)
    nd = pd.DataFrame(news).drop_duplicates("gid")
    rd = pd.DataFrame(reviews).drop_duplicates("recommendationid")
    for c in ("votes_up", "playtime_at_review", "playtime_forever"):
        rd[c] = pd.to_numeric(rd[c], errors="coerce").astype("Int32")
    p1 = write_parquet_dir(nd, WORK / "in" / "news_raw" / f"dt={a.dt}", schema=NEWS_SCHEMA)
    p2 = write_parquet_dir(rd, WORK / "in" / "review_raw" / f"delta/dt={a.dt}", schema=REVIEW_SCHEMA)
    print(f"news={len(nd)} (is_patch {int(nd.is_patch.sum())}) reviews={len(rd)} games={nd.appid.nunique()} -> {p1.parent}, {p2.parent}")
    json.dump({"apps": apps, "dt": a.dt, "since": since}, open(WORK / "in" / f"collect_{a.dt}.json", "w"), indent=1)


if __name__ == "__main__":
    main()
