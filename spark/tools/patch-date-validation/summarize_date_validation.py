"""Describe corpus coverage and export reproducible review samples; never invent gold labels."""
import argparse
import base64
import csv
import hashlib
import html
import json
import random
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

DATE = re.compile(r"\b20\d{2}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\.?\s+\d{1,2}|\b\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\b|\b(?:today|yesterday)\b", re.I)
ZONE = re.compile(r"\b(?:UTC|GMT|KST|CEST|CET|PDT|PST|EDT|EST|JST)\b|\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}|\[date\]|\[timestamp\]", re.I)
DEPLOYMENT = re.compile(r"\b(?:patch|update|hotfix|maintenance|deployed|released|release|went live|now live|rolled out)\b", re.I)


def plain_text(text):
    text = re.sub(r"\[img[^\]]*\].*?\[/img\]", " ", text, flags=re.I | re.S)
    text = re.sub(r"\[/?(?:h[1-6]|p|list|olist|\*|tr)[^\]]*\]|<\s*/?(?:p|br|li|h[1-6]|div)[^>]*>", "\n", text, flags=re.I)
    text = re.sub(r"\[/?(?:url|b|i|u|quote|table|th|td|strike|spoiler)[^\]]*\]|<[^>]*>", " ", text, flags=re.I)
    return re.sub(r"[ \t]+", " ", html.unescape(text)).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    folder = args.folder
    manifest = json.loads((folder / "manifest.json").read_text())
    types = {app["appid"]: app.get("app_type") for app in manifest["apps"]}
    corpus = {(str(item["appid"]), str(item["gid"])): item for item in
              map(json.loads, (folder / "corpus.jsonl").open(encoding="utf-8"))}
    with (folder / "output.tsv").open(encoding="utf-8") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    game_rows = [row for row in rows if types[int(row["appid"])] == "game"]
    assert len(rows) == len(corpus)
    assert len({(row["appid"], row["gid"]) for row in rows}) == len(rows)
    game_items = [corpus[(row["appid"], row["gid"])] for row in game_rows]
    summary = {"collected_app_notice_pairs": len(rows), "game_notice_pairs": len(game_rows),
               "games_with_notices": len({row["appid"] for row in game_rows}),
               "unique_gids": len({row["gid"] for row in game_rows}),
               "unique_bodies": len({hashlib.sha256(item.get("contents", "").encode()).hexdigest() for item in game_items}),
               "earliest_publication_utc": datetime.fromtimestamp(min(item["date"] for item in game_items), timezone.utc).isoformat(),
               "latest_publication_utc": datetime.fromtimestamp(max(item["date"] for item in game_items), timezone.utc).isoformat(),
               "classifier": dict(Counter(row["decision"] for row in game_rows)),
               "classifier_scope": dict(Counter(row["decision"] + "/" + row["scope"] for row in game_rows)),
               "date_status": dict(Counter(row["status"] for row in game_rows)),
               "date_reasons": dict(Counter(row["date_reason"] for row in game_rows)),
               "diagnostic_assumed_kst": dict(Counter(row["diagnostic_kst_status"] for row in game_rows)),
               "diagnostic_forced_patch_default": dict(Counter(row["diagnostic_forced_status"] for row in game_rows)),
               "diagnostic_forced_patch_default_and_kst": dict(Counter(row["diagnostic_forced_kst_status"] for row in game_rows)),
               "diagnostic_forced_kst_reasons": dict(Counter(row["diagnostic_forced_kst_reason"] for row in game_rows)),
               "feeds": dict(Counter(item.get("feedname", "") for item in game_items)),
               "empty_bodies": sum(not item.get("contents", "").strip() for item in game_items),
               "failed_apps": [app for app in manifest["apps"] if app.get("error")],
               "page_limited_apps": [app["appid"] for app in manifest["apps"] if app.get("stop") == "page_limit"]}
    by_game = defaultdict(list)
    for row in game_rows:
        by_game[row["appid"]].append(row)
    summary["per_game"] = [{"appid": appid, "game": corpus[(appid, records[0]["gid"])]["game"],
                            "count": len(records), "classifications": dict(Counter(r["decision"] for r in records)),
                            "date_status": dict(Counter(r["status"] for r in records))}
                           for appid, records in sorted(by_game.items(), key=lambda pair: int(pair[0]))]
    (folder / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    candidates = []
    for row in game_rows:
        item = corpus[(row["appid"], row["gid"])]
        text = plain_text(item.get("contents", ""))
        snippets = []
        for match in ZONE.finditer(text):
            snippet = text[max(0, match.start()-300):match.end()+450]
            if DATE.search(snippet) and DEPLOYMENT.search(snippet):
                snippets.append(snippet)
        if snippets or row["status"] == "RESOLVED" or row["diagnostic_kst_status"] == "RESOLVED":
            candidates.append({**row, "game": item["game"], "title": item["title"], "url": item.get("url"),
                               "published_at": datetime.fromtimestamp(item["date"], timezone.utc).isoformat(),
                               "snippets": list(dict.fromkeys(snippets))[:8]})
    (folder / "timezone-candidates.json").write_text(json.dumps(candidates, ensure_ascii=False, indent=2), encoding="utf-8")

    # These are review queues, not gold labels or automatic estimates of precision/recall.
    random_source = random.Random(20260915)
    eligible = [row for row in game_rows if row["decision"] == "PATCH" and row["scope"] == "DEFAULT"]
    excluded = [row for row in game_rows if not (row["decision"] == "PATCH" and row["scope"] == "DEFAULT")]
    sampled = random_source.sample(eligible, min(100, len(eligible))) + random_source.sample(excluded, min(100, len(excluded)))
    with (folder / "random-review.jsonl").open("w", encoding="utf-8") as output:
        for row in sampled:
            item = corpus[(row["appid"], row["gid"])]
            output.write(json.dumps({**row, "game": item["game"], "title": item["title"],
                                    "url": item.get("url"), "body": plain_text(item.get("contents", ""))}, ensure_ascii=False) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key not in {"per_game", "failed_apps"}}, ensure_ascii=False, indent=2))
    print(f"Timezone evidence candidates for source review: {len(candidates)}")


if __name__ == "__main__":
    main()
