"""Find review candidates with full-year dates and completion wording, not automatic truth labels."""
import json
import re
import sys
from pathlib import Path
from summarize_date_validation import plain_text

folder = Path(sys.argv[1])
complete = re.compile(r"\b(?:has been fixed|have been fixed|was applied|were applied|was updated|has been deployed|have been deployed|maintenance (?:has been|is) completed|maintenance has ended|was released|have applied|applied at|fix applied)\b", re.I)
zone = re.compile(r"\b(?:UTC|GMT|KST)\b", re.I)
year = re.compile(r"\b20(?:1\d|2\d)\b")
rows = []
seen = set()
for line in (folder / "corpus.jsonl").open(encoding="utf-8"):
    item = json.loads(line)
    body = plain_text(item.get("contents", ""))
    if body in seen:
        continue
    seen.add(body)
    for match in complete.finditer(body):
        context = body[max(0, match.start()-350):match.end()+500]
        if zone.search(context) and year.search(context):
            rows.append({"appid": item["appid"], "gid": item["gid"], "game": item["game"],
                         "title": item["title"], "date": item["date"], "excerpt": context})
            break
(folder / "completed-date-candidates.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
print("FULL-YEAR COMPLETED-DATE CANDIDATES:", len(rows))
for row in rows:
    print(json.dumps(row, ensure_ascii=False))
