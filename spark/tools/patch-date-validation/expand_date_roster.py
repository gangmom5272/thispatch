"""Select fresh apps from captured public Steam search listings, plus a previously capped history."""
import argparse
import html
import json
import re
import urllib.parse
from pathlib import Path

from collect_date_validation import fetch_json

parser = argparse.ArgumentParser()
parser.add_argument("prior", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
prior = args.prior
output = args.output
output.mkdir(exist_ok=False)
previous = json.loads((prior / "manifest.json").read_text(encoding="utf-8"))
excluded = {app["appid"] for app in previous["apps"] if app.get("unique_notices", 0)}
groups = []
for group, extra in [("high_review_scores", {"sort_by": "Reviews_DESC"}),
                     ("top_sellers", {"filter": "topsellers"}),
                     ("multiplayer", {"tags": "128", "sort_by": "Released_ASC"})]:
    records = []
    for page in range(5):
        params = {"start": page * 100, "count": 100, "category1": 998,
                  "infinite": 1, "l": "english", "json": 1, **extra}
        url = "https://store.steampowered.com/search/results/?" + urllib.parse.urlencode(params)
        response, digest = fetch_json(url)
        (output / f"{group}-{page}.json").write_text(json.dumps({"url": url, "sha256": digest,
            "response": response}, ensure_ascii=False), encoding="utf-8")
        anchors = re.findall(r'<a\b[^>]*data-ds-appid="(\d+)"[^>]*>(.*?)</a>', response["results_html"], re.S)
        for appid, body in anchors:
            name = re.search(r'<span class="title">(.*?)</span>', body, re.S)
            if name:
                records.append({"appid": int(appid), "name": html.unescape(re.sub(r"<[^>]*>", "", name.group(1))),
                                "identity_source": url, "selection_group": group})
        print(group, page, "records", len(anchors), flush=True)
    groups.append(records)

selected = []
seen = set(excluded)
# Interleave source lists so one selection route does not consume the entire sample budget.
for index in range(max(map(len, groups))):
    for group in groups:
        if index >= len(group):
            continue
        item = group[index]
        if item["appid"] not in seen:
            selected.append(item)
            seen.add(item["appid"])
    if len(selected) >= 300:
        selected = selected[:300]
        break

for app in previous["apps"]:
    if app.get("stop") != "page_limit":
        continue
    last_page = sorted((prior / "raw" / str(app["appid"])).glob("page-*.json"))[-1]
    response = json.loads(last_page.read_text(encoding="utf-8"))
    cursor = min(item["date"] for item in response["response"]["appnews"]["newsitems"])
    selected.append({"appid": app["appid"], "name": app["game"],
                     "identity_source": f"https://store.steampowered.com/app/{app['appid']}/",
                     "selection_group": "previously_page_limited_history", "initial_enddate": cursor})
(output / "roster.json").write_text(json.dumps(selected, ensure_ascii=False, indent=2), encoding="utf-8")
(output / "selection.json").write_text(json.dumps({"new_app_target": 300, "selected": len(selected),
    "prior_excluded_appids": sorted(excluded), "cutoff": previous["cutoff"],
    "note": "Public store-list sampling, not a probability sample; no selection by date-rule output"}, indent=2), encoding="utf-8")
print("READY", len(selected), "apps; cutoff", previous["cutoff"], flush=True)
