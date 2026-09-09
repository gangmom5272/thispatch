#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
연결 재사용(HTTP keep-alive)이 수집 속도를 얼마나 바꾸는가.

netbench.py 는 요청마다 TCP 연결을 새로 엽니다 (urllib 기본 동작).
동시 32에서 오류가 폭발한 게 스팀이 막은 것(429=0)이 아니라
연결을 계속 새로 여는 쪽이 한계에 걸린 것으로 보인다.

한 스레드가 연결 하나를 유지하며 계속 재사용하면 어떻게 되는지 잰다.
표준 라이브러리 http.client 만 쓴다.
"""
import argparse, gzip, io, json, os, platform, random, socket, sys
import threading, time, urllib.parse, urllib.request
import http.client

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
HOST = "store.steampowered.com"
LOCK = threading.Lock()


def catalog_pool(target=240):
    pool, pages = [], list(range(185))
    random.shuffle(pages)
    for page in pages[:4]:
        payload = {
            "query": {"start": page * 1000, "count": 1000, "sort": 2,
                      "filters": {"type_filters": {"include_games": True}}},
            "context": {"language": "english", "country_code": "US",
                        "steam_realm": 1},
            "data_request": {"include_basic_info": True, "include_reviews": True},
        }
        u = ("https://api.steampowered.com/IStoreQueryService/Query/v1/"
             "?input_json=" + urllib.parse.quote(json.dumps(payload)))
        try:
            req = urllib.request.Request(u, headers={"User-Agent": UA,
                                                     "Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=60) as r:
                b = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    b = gzip.decompress(b)
        except Exception:
            continue
        d = json.loads(b.decode("utf-8", "replace"))
        for it in ((d or {}).get("response") or {}).get("store_items") or []:
            rv = it.get("reviews") or {}
            s = rv.get("summary_unfiltered") or rv.get("summary_filtered") or {}
            if (s.get("review_count") or 0) >= 50:
                pool.append(it.get("appid"))
        if len(pool) >= target:
            break
    random.shuffle(pool)
    return pool[:target]


def path_for(appid):
    return ("/appreviews/%d?json=1&filter=recent&language=all"
            "&purchase_type=all&review_type=all&filter_offtopic_activity=0"
            "&num_per_page=100&cursor=*" % appid)


def worker_fresh(pool, idx, stop, out):
    """요청마다 새 연결 — netbench.py 와 같은 방식."""
    while time.time() < stop:
        with LOCK:
            a = pool[idx[0] % len(pool)]; idx[0] += 1
        t0 = time.perf_counter()
        try:
            req = urllib.request.Request(
                "https://" + HOST + path_for(a),
                headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
            with urllib.request.urlopen(req, timeout=30) as r:
                n = len(r.read())
            out.append((time.perf_counter() - t0, n, 200))
        except Exception as e:
            out.append((time.perf_counter() - t0, 0,
                        getattr(e, "code", "ERR")))


def worker_keepalive(pool, idx, stop, out):
    """연결 하나를 유지하며 재사용. 끊기면 다시 연다."""
    conn = None
    while time.time() < stop:
        with LOCK:
            a = pool[idx[0] % len(pool)]; idx[0] += 1
        t0 = time.perf_counter()
        try:
            if conn is None:
                conn = http.client.HTTPSConnection(HOST, timeout=30)
            conn.request("GET", path_for(a), headers={
                "User-Agent": UA, "Accept-Encoding": "gzip",
                "Connection": "keep-alive", "Host": HOST})
            r = conn.getresponse()
            n = len(r.read())
            code = r.status
            if r.will_close:
                conn.close(); conn = None
            out.append((time.perf_counter() - t0, n, code))
        except Exception as e:
            try:
                if conn: conn.close()
            except Exception:
                pass
            conn = None
            out.append((time.perf_counter() - t0, 0, getattr(e, "code", "ERR")))
    try:
        if conn: conn.close()
    except Exception:
        pass


def run(pool, fn, workers, seconds):
    out, idx, stop = [], [0], time.time() + seconds
    ths = [threading.Thread(target=fn, args=(pool, idx, stop, out), daemon=True)
           for _ in range(workers)]
    t0 = time.perf_counter()
    for t in ths: t.start()
    for t in ths: t.join()
    el = time.perf_counter() - t0
    good = [x[0] for x in out if x[2] == 200]
    byt = sum(x[1] for x in out if x[2] == 200)
    blocked = sum(1 for x in out if x[2] == 429)
    errs = sum(1 for x in out if x[2] not in (200, 429))
    return {"el": el, "n": len(good), "cps": len(good) / el if el else 0,
            "mbps": byt * 8 / el / 1e6 if el else 0,
            "p50": pct(good, .5), "p90": pct(good, .9),
            "blocked": blocked, "errs": errs}


def pct(v, q):
    if not v: return 0.0
    v = sorted(v)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default=socket.gethostname())
    ap.add_argument("--secs", type=int, default=25)
    args = ap.parse_args()

    print("=" * 72)
    print(" 연결 재사용 효과 측정   [%s]   %s · 논리 %d코어"
          % (args.label, platform.system(), os.cpu_count() or 0))
    print("=" * 72)
    pool = catalog_pool()
    print(" appid 풀 %d개 · 각 조건 %d초" % (len(pool), args.secs))
    if len(pool) < 20:
        print(" ⚠ 풀 확보 실패"); return
    print()
    print(" %-10s %-7s %8s %9s %9s %8s %8s" %
          ("방식", "동시", "콜/초", "중앙 ms", "p90 ms", "Mbps", "오류"))
    print(" " + "-" * 66)

    rows = []
    for name, fn in (("새 연결", worker_fresh), ("재사용", worker_keepalive)):
        for w in (16, 32, 64):
            r = run(pool, fn, w, args.secs)
            rows.append((name, w, r))
            print(" %-10s %-7d %8.1f %9.0f %9.0f %8.1f %8d%s" %
                  (name, w, r["cps"], 1000 * r["p50"], 1000 * r["p90"],
                   r["mbps"], r["errs"],
                   ("  차단%d" % r["blocked"]) if r["blocked"] else ""))
            time.sleep(3)
        print()

    print(" ── 환산 (176만콜 = 리뷰 1.76억 건) ──")
    ok = [(nm, w, r) for nm, w, r in rows
          if r["cps"] > 0 and r["errs"] <= max(5, 0.02 * r["n"])]
    if not ok:
        print("  오류율 2% 미만 조건이 없습니다."); return
    for nm, w, r in sorted(ok, key=lambda x: -x[2]["cps"])[:4]:
        print("  %-10s 동시 %-3d  %5.1f 콜/초  →  %5.1f시간   (오류 %d)"
              % (nm, w, r["cps"], 1760000 / r["cps"] / 3600, r["errs"]))
    best = max(ok, key=lambda x: x[2]["cps"])
    print()
    print("  최적: %s · 동시 %d · %.1f 콜/초 · 최초 %.1f시간"
          % (best[0], best[1], best[2]["cps"], 1760000 / best[2]["cps"] / 3600))

    json.dump({"label": args.label,
               "rows": [{"mode": nm, "workers": w,
                         "cps": round(r["cps"], 2), "p50_ms": round(1000*r["p50"]),
                         "mbps": round(r["mbps"], 2), "errs": r["errs"],
                         "blocked": r["blocked"]} for nm, w, r in rows]},
              open("keepalive_%s.json" % args.label.replace(" ", "_"), "w"),
              ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
