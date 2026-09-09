#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
스팀 API 수집 속도 측정 — 서버 vs 노트북 비교용.

최초 전량 수집 18시간이 어디서 도는 게 빠른지 정하려면
「초당 몇 콜을 넣을 수 있나」를 같은 코드로 양쪽에서 재야 한다.

의존성 없음 (표준 라이브러리만). Ubuntu 24.04 python3 에서 그대로 돌아간다.

    python3 netbench.py                 # 기본 (약 4분)
    python3 netbench.py --quick         # 짧게 (약 90초)
    python3 netbench.py --label 서버2    # 결과에 이름 붙이기
"""
import argparse, gzip, io, json, os, platform, random, socket, statistics as st
import sys, threading, time, urllib.parse, urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
POOL_LOCK = threading.Lock()


# ─────────────────────────────────────────────────────────────
def fetch(url, timeout=30):
    """(소요초, 바이트수, 상태) — 예외는 상태로 돌려준다."""
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            enc = r.headers.get("Content-Encoding")
        n = len(raw)
        if enc == "gzip":
            raw = gzip.decompress(raw)
        return time.perf_counter() - t0, n, 200
    except urllib.error.HTTPError as e:
        return time.perf_counter() - t0, 0, e.code
    except Exception:
        return time.perf_counter() - t0, 0, "ERR"


def catalog_pool(target=240, min_calls=1):
    """카탈로그에서 appid 를 모은다. 같은 게임을 반복 조회하면
    스팀 캐시에 걸려 실제보다 빠르게 나오므로 풀을 넓게 잡는다."""
    pool, pages = [], list(range(185))
    random.shuffle(pages)
    lat = []
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
        el, _, code = fetch(u, timeout=60)
        lat.append((el, code))
        if code != 200:
            continue
        req = urllib.request.Request(u, headers={"User-Agent": UA,
                                                 "Accept-Encoding": "gzip"})
        with urllib.request.urlopen(req, timeout=60) as r:
            b = r.read()
            if r.headers.get("Content-Encoding") == "gzip":
                b = gzip.decompress(b)
        d = json.loads(b.decode("utf-8", "replace"))
        for it in ((d or {}).get("response") or {}).get("store_items") or []:
            rv = it.get("reviews") or {}
            s = rv.get("summary_unfiltered") or rv.get("summary_filtered") or {}
            if (s.get("review_count") or 0) >= 50:
                pool.append(it.get("appid"))
        if len(pool) >= target and len(lat) >= min_calls:
            break
    random.shuffle(pool)
    return pool[:target], lat


def review_url(appid):
    return ("https://store.steampowered.com/appreviews/%d?json=1&filter=recent"
            "&language=all&purchase_type=all&review_type=all"
            "&filter_offtopic_activity=0&num_per_page=100&cursor=*" % appid)


# ─────────────────────────────────────────────────────────────
def ramp(pool, workers, seconds, out):
    """workers 개 스레드로 seconds 초간 계속 요청하고 결과를 out 에 쌓는다."""
    stop = time.time() + seconds
    idx = [0]

    def next_appid():
        with POOL_LOCK:
            a = pool[idx[0] % len(pool)]
            idx[0] += 1
            return a

    def run():
        while time.time() < stop:
            el, nbytes, code = fetch(review_url(next_appid()))
            out.append((el, nbytes, code))

    ths = [threading.Thread(target=run, daemon=True) for _ in range(workers)]
    t0 = time.perf_counter()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    return time.perf_counter() - t0


def pct(v, q):
    if not v:
        return 0.0
    v = sorted(v)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


# ─────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default=None, help="결과에 붙일 이름")
    ap.add_argument("--quick", action="store_true", help="짧게 (약 90초)")
    args = ap.parse_args()

    label = args.label or socket.gethostname()
    levels = [(4, 15), (8, 20), (16, 20), (32, 25)] if args.quick else \
             [(4, 25), (8, 30), (16, 35), (32, 40), (48, 40)]

    print("=" * 68)
    print(" 스팀 API 수집 속도 측정   [%s]" % label)
    print("=" * 68)
    print(" 호스트   %s" % socket.gethostname())
    print(" OS      %s %s" % (platform.system(), platform.release()))
    print(" python  %s" % platform.python_version())
    try:
        print(" CPU     논리 %d개" % os.cpu_count())
    except Exception:
        pass
    print(" 시각     %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    print()

    print("① 카탈로그 API (매일 184콜)")
    pool, cat = catalog_pool(min_calls=3)
    ok = [e for e, c in cat if c == 200]
    if ok:
        print("   %d콜 · 중앙 %.0fms · 최대 %.0fms" %
              (len(ok), 1000 * pct(ok, .5), 1000 * max(ok)))
    print("   appid 풀 %d개 확보" % len(pool))
    if len(pool) < 20:
        print("   ⚠ 풀이 너무 작습니다. 네트워크를 확인하세요.")
        return
    print()

    print("② 리뷰 API — 동시 요청 수를 올리며 포화점 찾기")
    print("   %-6s %8s %9s %9s %9s %8s %7s" %
          ("동시", "콜/초", "중앙 ms", "p90 ms", "p99 ms", "Mbps", "차단"))
    print("   " + "-" * 62)

    rows = []
    for w, secs in levels:
        out = []
        el = ramp(pool, w, secs, out)
        good = [x[0] for x in out if x[2] == 200]
        byt = sum(x[1] for x in out if x[2] == 200)
        blocked = sum(1 for x in out if x[2] == 429)
        errs = sum(1 for x in out if x[2] not in (200, 429))
        if not good:
            print("   %-6d %8s  전부 실패 (오류 %d)" % (w, "-", errs))
            continue
        cps = len(good) / el
        mbps = byt * 8 / el / 1e6
        rows.append((w, cps, pct(good, .5), pct(good, .9), pct(good, .99),
                     mbps, blocked, errs))
        print("   %-6d %8.1f %9.0f %9.0f %9.0f %8.1f %7d%s" %
              (w, cps, 1000 * pct(good, .5), 1000 * pct(good, .9),
               1000 * pct(good, .99), mbps, blocked,
               ("  오류%d" % errs) if errs else ""))
        time.sleep(2)

    if not rows:
        print("\n   측정 실패.")
        return

    print()
    # 포화점 = 오류율 2% 미만인 것 중 콜/초가 가장 큰 것.
    # 동시 수를 무작정 올리면 콜/초는 조금 늘고 오류가 폭증하므로
    # 오류를 무시하고 고르면 실제로 못 쓰는 설정을 고르게 된다.
    def usable(r):
        tot = r[1] * 1.0
        return r[7] <= max(3, 0.02 * (tot * 30))
    cands = [r for r in rows if usable(r)] or rows
    best = max(cands, key=lambda r: r[1])
    if best is not max(rows, key=lambda r: r[1]):
        top = max(rows, key=lambda r: r[1])
        print("   (동시 %d 이 %.1f 콜/초로 더 높지만 오류 %d건이라 제외)"
              % (top[0], top[1], top[7]))
    CALLS_FULL = 1_760_000     # 1.76억 건 / 100건per page
    CALLS_DAILY = 117_000      # 문서 실측
    print("③ 환산")
    print("   포화점              동시 %d · %.1f 콜/초" % (best[0], best[1]))
    print("   최초 전량 176만콜    %.1f시간   (1대 기준)" %
          (CALLS_FULL / best[1] / 3600))
    print("   매일 증분 11.7만콜   %.0f분     (1대 기준)" %
          (CALLS_DAILY / best[1] / 60))
    print()
    print("   ※ 지연이 대부분 스팀 응답 생성 시간이라 CPU 성능과 무관합니다.")
    print("     이 숫자가 큰 쪽에서 최초 수집을 돌리는 게 맞습니다.")
    if best[6]:
        print("   ⚠ 429(차단)가 %d건 나왔습니다. 동시 수를 낮춰야 합니다." % best[6])

    json.dump({"label": label, "host": socket.gethostname(),
               "when": time.strftime("%Y-%m-%d %H:%M:%S"),
               "rows": [{"workers": r[0], "calls_per_sec": round(r[1], 2),
                         "p50_ms": round(1000 * r[2]), "p90_ms": round(1000 * r[3]),
                         "p99_ms": round(1000 * r[4]), "mbps": round(r[5], 2),
                         "blocked": r[6], "errors": r[7]} for r in rows]},
              open("netbench_%s.json" % label.replace(" ", "_"), "w"),
              ensure_ascii=False, indent=1)
    print()
    print("   결과 저장: netbench_%s.json" % label.replace(" ", "_"))


if __name__ == "__main__":
    main()
