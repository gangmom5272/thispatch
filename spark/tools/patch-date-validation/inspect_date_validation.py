"""Print bounded source review packets without calling another model or changing predictions."""
import argparse
import base64
import json
import re
from collections import Counter
from pathlib import Path
from summarize_date_validation import plain_text

parser = argparse.ArgumentParser()
parser.add_argument("folder", type=Path)
parser.add_argument("mode", choices=["candidates", "body", "diagnostics", "random"])
parser.add_argument("--start", type=int, default=0)
parser.add_argument("--limit", type=int, default=20)
parser.add_argument("--gid", default="")
parser.add_argument("--appid", default="")
parser.add_argument("--max-body", type=int, default=0)
parser.add_argument("--min-index", type=int, default=0)
args = parser.parse_args()
if args.mode == "candidates":
    rows = json.loads((args.folder / "timezone-candidates.json").read_text(encoding="utf-8"))
    if args.appid:
        rows = [row for row in rows if row["appid"] in args.appid.split(",")]
    # Prefer completed language; this ranking is for targeted inspection, not a gold label.
    completion = re.compile(r"\b(?:completed|has been released|was released|went live|now live|deployed|is now available|maintenance is over)\b", re.I)
    rows.sort(key=lambda row: (-sum(bool(completion.search(s)) for s in row["snippets"]), int(row["appid"]), row["gid"]))
    for index, row in enumerate(rows[args.start:args.start+args.limit], args.start):
        print(json.dumps({"index": index, "appid": row["appid"], "gid": row["gid"], "game": row["game"], "title": row["title"],
                          "classification": row["decision"] + "/" + row["scope"], "result": row["date_reason"],
                          "published_at": row["published_at"], "snippets": row["snippets"][:1]}, ensure_ascii=False))
elif args.mode == "body":
    wanted = set(args.gid.split(","))
    for line in (args.folder / "corpus.jsonl").open(encoding="utf-8"):
        item = json.loads(line)
        if str(item["gid"]) in wanted:
            print(json.dumps({"appid": item["appid"], "gid": item["gid"], "game": item["game"], "title": item["title"],
                              "date": item["date"], "url": item.get("url"), "body": plain_text(item.get("contents", ""))}, ensure_ascii=False))
elif args.mode == "diagnostics":
    import csv
    with (args.folder / "output.tsv").open(encoding="utf-8") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            if row["status"] == "RESOLVED" or row["diagnostic_forced_kst_reason"] not in {
                    "NO_EXPLICIT_DEPLOYMENT_DATE", "DEPLOYMENT_PENDING_OR_CONFLICTING", "MISSING_TITLE_OR_BODY"}:
                row["diagnostic_evidence"] = base64.b64decode(row.pop("diagnostic_evidence_b64")).decode()
                row["evidence"] = base64.b64decode(row.pop("evidence_b64")).decode()
                print(json.dumps(row, ensure_ascii=False))
else:
    rows = [json.loads(line) for line in (args.folder / "random-review.jsonl").open(encoding="utf-8")]
    selected = [(index, row) for index, row in enumerate(rows)
                if index >= args.min_index and (not args.max_body or len(row["body"]) <= args.max_body)]
    for index, row in selected[args.start:args.start+args.limit]:
        print(json.dumps({"index": index, "appid": row["appid"], "gid": row["gid"], "game": row["game"], "title": row["title"],
                          "body_characters": len(row["body"]), "body": row["body"]}, ensure_ascii=False))
