"""Combine frozen audit runs by app/news identity and prepare source-only review queues."""
import argparse
import csv
import hashlib
import json
import random
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from summarize_date_validation import plain_text


def read_run(folder):
    with (folder / "output.tsv").open(encoding="utf-8") as source:
        results = {(int(row["appid"]), row["gid"]): row for row in csv.DictReader(source, delimiter="\t")}
    seen = set()
    with (folder / "corpus.jsonl").open(encoding="utf-8") as source:
        for line in source:
            item = json.loads(line)
            key = (item["appid"], str(item["gid"]))
            assert key not in seen
            seen.add(key)
            yield key, item, results[key]
    assert seen == results.keys()


def summarize(records):
    items = [record[0] for record in records.values()]
    rows = [record[1] for record in records.values()]
    columns = ["decision", "status", "date_reason", "diagnostic_kst_status",
               "diagnostic_forced_status", "diagnostic_forced_kst_status", "diagnostic_forced_kst_reason"]
    return {
        "notice_pairs": len(items), "apps_with_notices": len({item["appid"] for item in items}),
        "unique_gids": len({str(item["gid"]) for item in items}),
        "unique_bodies": len({hashlib.sha256(item.get("contents", "").encode()).hexdigest() for item in items}),
        "empty_bodies": sum(not item.get("contents", "").strip() for item in items),
        "publication_range_utc": [datetime.fromtimestamp(fn(item["date"] for item in items), timezone.utc).isoformat()
                                  for fn in (min, max)],
        "feeds": dict(Counter(item.get("feedname", "") for item in items)),
        "classifier_scope": dict(Counter(row["decision"] + "/" + row["scope"] for row in rows)),
        **{column: dict(Counter(row[column] for row in rows)) for column in columns},
    }


def review_record(key, item):
    return {"appid": key[0], "gid": key[1], "game": item["game"], "title": item["title"],
            "url": item.get("url"), "raw_file": item["raw_file"],
            "published_at": datetime.fromtimestamp(item["date"], timezone.utc).isoformat(),
            "body_sha256": hashlib.sha256(item.get("contents", "").encode()).hexdigest(),
            "body": plain_text(item.get("contents", ""))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("prior", type=Path)
    parser.add_argument("expanded", type=Path)
    args = parser.parse_args()
    prior = {key: (item, row) for key, item, row in read_run(args.prior)}
    expanded = {key: (item, row) for key, item, row in read_run(args.expanded)}
    overlap = prior.keys() & expanded.keys()
    conflicts = [key for key in overlap if prior[key][0].get("contents") != expanded[key][0].get("contents")]
    fresh = {key: record for key, record in expanded.items() if key not in prior}
    combined = {**prior, **fresh}  # Preserve earlier observations for overlapping identities.
    manifests = [json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
                 for folder in (args.prior, args.expanded)]
    assert manifests[0]["cutoff"] == manifests[1]["cutoff"]
    summary = {"prior": summarize(prior), "expanded_run": summarize(expanded),
               "new_unique": summarize(fresh), "combined": summarize(combined),
               "overlap_pairs": len(overlap), "overlap_body_conflicts": conflicts,
               "cutoff": manifests[0]["cutoff"],
               "expanded_failed_apps": [app for app in manifests[1]["apps"] if app.get("error")],
               "expanded_page_limited_apps": [app["appid"] for app in manifests[1]["apps"] if app.get("stop") == "page_limit"],
               "expanded_identity_types": dict(Counter(app.get("app_type") for app in manifests[1]["apps"]))}
    (args.expanded / "combined-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    # Candidate selection deliberately does not consult model predictions. These are queues, not gold labels.
    zone = re.compile(r"\b(?:UTC|GMT|KST|JST|PDT|PST|CET|CEST)\b|한국\s*시간", re.I)
    complete = re.compile(r"released|deployed|went live|has been updated|completed|finished|적용|완료|수정되|업데이트되", re.I)
    candidates = []
    for key, (item, _) in sorted(fresh.items()):
        record = review_record(key, item)
        if zone.search(record["body"]) and complete.search(record["body"]):
            candidates.append(record)
    (args.expanded / "source-candidates.json").write_text(json.dumps(candidates, ensure_ascii=False, indent=2), encoding="utf-8")
    eligible = [review_record(key, item) for key, (item, _) in sorted(fresh.items())
                if 100 <= len(plain_text(item.get("contents", ""))) <= 1800]
    queue = random.Random(20260916).sample(eligible, min(20, len(eligible)))
    (args.expanded / "source-random-20.json").write_text(json.dumps(queue, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Source-only candidates: {len(candidates)}; short-body random queue: {len(queue)} / {len(eligible)}")


if __name__ == "__main__":
    main()
