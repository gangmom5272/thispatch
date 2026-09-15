"""Collect a bounded, reproducible corpus of Steam first-party announcements."""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# App identities are verified with appdetails before being passed to the Java classifier.
APP_IDS = [730, 570, 440, 4000, 550, 620, 105600, 413150, 294100, 427520,
           108600, 275850, 548430, 646570, 588650, 1145350, 1145360, 526870, 1604030, 1091500,
           292030, 553850, 892970, 1245620, 1086940, 1623730, 2000950, 1172470, 578080, 252490,
           322330, 251570, 346110, 2399830, 381210, 236390, 230410, 238960, 2694490, 218620,
           548570, 359550, 1203220, 570940, 377160, 489830, 1888930, 990080, 2050650, 1085660,
           242760, 1326470, 892970, 1973530, 1966720, 1794680, 1817070, 782330, 1286680, 1151640,
           582010, 1446780, 2246340, 1364780, 1778820, 386180, 393380, 394360, 236850, 281990,
           1158310, 529340, 289070, 1142710, 594570, 431960, 311210, 960090, 813780, 1466860,
           367520, 1030300, 632360, 678960, 1113560, 1240440, 1938090, 1174180, 1172620, 1325200,
           438100, 438640, 444090, 1097150, 504230, 242050, 304930, 306130, 813820, 252950,
           774171]
API = "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"
FEEDS = "steam_community_announcements,steam_updates"
REQUEST_LOCK = threading.Lock()
LAST_REQUEST = 0.0


def fetch_json(url):
    global LAST_REQUEST
    for attempt in range(3):
        with REQUEST_LOCK:
            time.sleep(max(0, 0.55 - (time.monotonic() - LAST_REQUEST)))
            LAST_REQUEST = time.monotonic()
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "SteamPatchDateValidation/1.0"})
            with urllib.request.urlopen(request, timeout=45) as response:
                raw = response.read(64 * 1024 * 1024 + 1)
            if len(raw) > 64 * 1024 * 1024:
                raise ValueError("Response exceeds 64 MiB safety bound")
            return json.loads(raw), hashlib.sha256(raw).hexdigest()
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            if attempt == 2:
                raise
            delay = 4 * (attempt + 1)
            if isinstance(error, urllib.error.HTTPError) and error.code == 429:
                delay = max(delay, min(60, int(error.headers.get("Retry-After", "30"))))
            time.sleep(delay)


def collect_app(appid, output, cutoff, max_pages):
    folder = output / "raw" / str(appid)
    folder.mkdir(parents=True, exist_ok=True)
    status = {"appid": appid, "game": None, "pages": [], "error": None}
    try:
        metadata_url = f"https://store.steampowered.com/api/appdetails?appids={appid}&l=english&filters=basic"
        metadata, digest = fetch_json(metadata_url)
        info = metadata.get(str(appid), {})
        if not info.get("success"):
            raise ValueError("Cannot verify app identity")
        status["game"] = info["data"]["name"]
        status["app_type"] = info["data"]["type"]
        (folder / "metadata.json").write_text(json.dumps({"url": metadata_url, "sha256": digest,
            "observed_at": datetime.now(timezone.utc).isoformat(), "response": metadata}, ensure_ascii=False), encoding="utf-8")
        enddate = cutoff
        seen = set()
        for page in range(1, max_pages + 1):
            params = {"appid": appid, "count": 1000, "maxlength": 0, "feeds": FEEDS, "enddate": enddate}
            url = API + "?" + urllib.parse.urlencode(params)
            response, digest = fetch_json(url)
            items = response["appnews"].get("newsitems", [])
            envelope = {"appid": appid, "game": status["game"], "requested_url": url,
                        "observed_at": datetime.now(timezone.utc).isoformat(), "sha256": digest, "response": response}
            (folder / f"page-{page:02}.json").write_text(json.dumps(envelope, ensure_ascii=False), encoding="utf-8")
            new_items = [item for item in items if str(item["gid"]) not in seen]
            seen.update(str(item["gid"]) for item in items)
            status["pages"].append({"page": page, "received": len(items), "new": len(new_items), "enddate": enddate})
            if not items or not new_items:
                status["stop"] = "empty_or_no_new_items"
                break
            oldest = min(int(item["date"]) for item in items)
            if oldest >= enddate:
                status["stop"] = "cursor_not_advancing"
                break
            enddate = oldest
        else:
            status["stop"] = "page_limit"
        status["unique_notices"] = len(seen)
    except Exception as error:
        status["error"] = f"{type(error).__name__}: {error}"
    (folder / "status.json").write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"appid": appid, "game": status["game"], "count": status.get("unique_notices"),
                      "stop": status.get("stop"), "error": status["error"]}, ensure_ascii=False), flush=True)
    return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--max-pages", type=int, default=5)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    cutoff = int(time.time())
    appids = list(dict.fromkeys(APP_IDS))
    manifest = {"started_at": datetime.now(timezone.utc).isoformat(), "cutoff": cutoff,
                "appids": appids, "count_per_request": 1000, "max_pages": args.max_pages,
                "maxlength": 0, "feeds": FEEDS, "workers": 2, "min_request_spacing_seconds": 0.55,
                "sampling": "Purposive cross-game history sample; not a probability sample of all Steam",
                "announcement_zone": None, "timezone_policy": "Do not infer publisher timezone"}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        statuses = list(executor.map(lambda appid: collect_app(appid, args.output, cutoff, args.max_pages), appids))

    by_key = {}
    duplicates = 0
    conflicts = []
    for path in sorted((args.output / "raw").glob("*/page-*.json")):
        envelope = json.loads(path.read_text(encoding="utf-8"))
        for item in envelope["response"]["appnews"].get("newsitems", []):
            key = (int(envelope["appid"]), str(item["gid"]))
            record = {**item, "appid": key[0], "game": envelope["game"], "raw_file": str(path.relative_to(args.output))}
            if key in by_key:
                duplicates += 1
                if item.get("contents") != by_key[key].get("contents"):
                    conflicts.append(key)
                continue
            by_key[key] = record
    with (args.output / "corpus.jsonl").open("w", encoding="utf-8") as corpus, \
            (args.output / "input.tsv").open("w", encoding="utf-8") as prepared:
        for key, item in sorted(by_key.items()):
            corpus.write(json.dumps(item, ensure_ascii=False) + "\n")
            texts = [item.get("title", ""), item.get("contents", ""), ",".join(item.get("tags") or []), item["game"]]
            encoded = [base64.b64encode(text.encode()).decode() for text in texts]
            prepared.write("\t".join([str(key[0]), key[1], str(item["date"]), *encoded]) + "\n")
    manifest.update({"finished_at": datetime.now(timezone.utc).isoformat(), "unique_notices": len(by_key),
                     "duplicate_records": duplicates, "conflicting_versions": conflicts, "apps": statuses})
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"COMPLETE: {len(by_key)} unique notices across {len(statuses)} attempted apps", flush=True)


if __name__ == "__main__":
    main()
