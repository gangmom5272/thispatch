#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Steam appreviews 지속 수집 검증 (soak test)

이 스크립트가 답하려는 것 4가지
  1. 몇 시간 연속 호출하면 차단되는가
  2. 지속 실효 처리율은 몇 리뷰/초인가        <- 가장 중요
  3. 안전한 동시성은 몇인가
  4. 차단이 어떤 형태로 오는가 (429 / 빈 응답 / 커넥션 끊김 / 복구 시간)

합격 기준
  지속 처리율 x 24시간 > 1.5억  ->  하루 1회 전량 재수집 성립
  그보다 낮으면                 ->  증분 + 되돌아보기 창으로 후퇴하거나 대상 축소

핵심 제약
  appreviews 는 cursor 페이지네이션이라 (appid, language) 파티션 하나 안에서는
  순차 호출밖에 안 됩니다. 동시성은 서로 다른 파티션을 병렬로 돌려서 만듭니다.
  그래서 워커 수 = 동시에 진행하는 파티션 수입니다.

사용법
  python collect_soak.py                                  # 기본: 8시간, 동시성 12
  python collect_soak.py --hours 8 --concurrency 12
  python collect_soak.py --hours 6 --ramp 8:60,16:60,32:60,48:0
  python collect_soak.py --resume                          # 중단 지점부터 이어서
  python collect_soak.py --hours 0.1 --concurrency 4       # 6분 연습 실행

산출물 (out/ 아래)
  metrics.csv     1분 단위 처리율 / 오류 / 지연
  events.csv      429 · 빈 응답 · 커넥션 오류 등 이상 징후 시각
  summary.txt     종료 시 요약 (노션에 그대로 붙일 수 있음)
  reviews/*.jsonl.gz   수집한 리뷰 원본
  checkpoint.json      파티션별 커서 (재시작용)

의존성 없음. 표준 라이브러리만 씁니다.
Ctrl+C 로 언제든 중단해도 summary 와 checkpoint 가 남습니다.
"""

import argparse
import gzip
import io
import json
import os
import random
import signal
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from datetime import datetime, timezone

# Windows 콘솔 인코딩
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
REVIEW_DIR = os.path.join(OUT_DIR, "reviews")
CKPT_PATH = os.path.join(OUT_DIR, "checkpoint.json")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SoakTest/1.0"

# ---------------------------------------------------------------------------
# 대상 파티션
#   리뷰가 많은 (appid, language) 조합입니다. 앞쪽이 큰 파티션이라
#   워커가 오래 붙어 있습니다. 동시성보다 넉넉하게 넣어 뒀습니다.
# ---------------------------------------------------------------------------
TARGETS = [
    (730,     "russian",   "CS2"),                 # 임계경로. 실측 27,032 페이지
    (730,     "english",   "CS2"),
    (730,     "schinese",  "CS2"),
    (578080,  "schinese",  "PUBG"),
    (578080,  "english",   "PUBG"),
    (1172470, "english",   "Apex Legends"),
    (271590,  "english",   "GTA V"),
    (413150,  "english",   "Stardew Valley"),
    (413150,  "schinese",  "Stardew Valley"),
    (553850,  "english",   "HELLDIVERS 2"),
    (553850,  "russian",   "HELLDIVERS 2"),
    (1085660, "english",   "Destiny 2"),
    (1245620, "english",   "ELDEN RING"),
    (105600,  "english",   "Terraria"),
    (892970,  "english",   "Valheim"),
    (322330,  "english",   "Don't Starve Together"),
    (252490,  "russian",   "Rust"),
    (252490,  "english",   "Rust"),
    (230410,  "english",   "Warframe"),
    (1091500, "english",   "Cyberpunk 2077"),
    (1091500, "schinese",  "Cyberpunk 2077"),
    (238960,  "english",   "Path of Exile"),
    (275850,  "english",   "No Man's Sky"),
    (646570,  "english",   "Slay the Spire"),
    (588650,  "english",   "Dead Cells"),
    (1145360, "english",   "Hades"),
    (1966720, "english",   "Lethal Company"),
    (1794680, "english",   "Vampire Survivors"),
    (526870,  "english",   "Satisfactory"),
    (960090,  "english",   "Bloons TD 6"),
    (2868840, "english",   "Slay the Spire 2"),
    (427520,  "english",   "Factorio"),
    (294100,  "english",   "RimWorld"),
    (739630,  "english",   "Phasmophobia"),
    (1938090, "english",   "Call of Duty"),
    (1174180, "english",   "Red Dead Redemption 2"),
]

# 파티션을 동적으로 늘릴 때 쓰는 언어 목록 (리뷰 수 많은 순)
LANGS = ["english", "schinese", "russian", "brazilian", "koreana", "spanish",
         "german", "turkish", "japanese", "french", "polish", "tchinese",
         "thai", "latam", "italian", "czech", "ukrainian", "hungarian"]


def build_targets(n_games, timeout=40):
    """
    Query API 로 리뷰 많은 게임을 받아 (appid, language) 파티션을 생성합니다.

    하드코딩 목록만 쓰면 8시간 실행 후반에 큐가 말라서 동시성이 저절로
    무너집니다. 게임 x 언어로 펼치면 파티션이 수천 개가 되어 그럴 일이 없습니다.
    """
    games, seen_app = [], set()
    for page in range(12):                      # 최대 12,000개 순회
        payload = {
            "query": {"start": page * 1000, "count": 1000, "sort": 11,
                      "filters": {"type_filters": {"include_games": True}}},
            "context": {"language": "english", "country_code": "US", "steam_realm": 1},
            "data_request": {"include_basic_info": True, "include_reviews": True},
        }
        url = ("https://api.steampowered.com/IStoreQueryService/Query/v1/?input_json="
               + urllib.parse.quote(json.dumps(payload)))
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode("utf-8", "replace"))
        items = d.get("response", {}).get("store_items", []) or []
        if not items:
            break
        for it in items:
            appid = it.get("appid")
            if not appid or appid in seen_app:
                continue
            rv = (it.get("reviews") or {}).get("summary_unfiltered") or {}
            cnt = rv.get("review_count") or 0
            if cnt < 1000:
                continue
            seen_app.add(appid)
            games.append((cnt, appid, it.get("name") or str(appid)))
        if len(games) >= n_games:
            break
    games.sort(reverse=True)
    games = games[:n_games]

    # 큰 게임의 주요 언어가 앞에 오도록: 게임 리뷰수 내림차순 x 언어 우선순위
    parts, seen = [], set()
    for appid, language, name in TARGETS:      # 하드코딩 목록을 앞에
        seen.add((appid, language))
        parts.append((appid, language, name))
    for cnt, appid, name in games:
        for language in LANGS:
            if (appid, language) in seen:
                continue
            seen.add((appid, language))
            parts.append((appid, language, name))
    return parts, len(games)


# ---------------------------------------------------------------------------
# 동시성 게이트 (실행 중에 한도를 바꿀 수 있음 = ramp 지원)
# ---------------------------------------------------------------------------
class Gate:
    def __init__(self, limit):
        self._cv = threading.Condition()
        self._limit = limit
        self._inflight = 0

    def set_limit(self, limit):
        with self._cv:
            self._limit = limit
            self._cv.notify_all()

    @property
    def limit(self):
        return self._limit

    def acquire(self, stop_evt):
        with self._cv:
            while self._inflight >= self._limit:
                if stop_evt.is_set():
                    return False
                self._cv.wait(0.5)
            self._inflight += 1
            return True

    def release(self):
        with self._cv:
            self._inflight -= 1
            self._cv.notify()


# ---------------------------------------------------------------------------
# 집계용 카운터
# ---------------------------------------------------------------------------
class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.reset_window()
        self.cum_reviews = 0
        self.cum_requests = 0
        self.cum_429 = 0
        self.cum_err = 0
        self.first_429_at = None
        self.pages_done = 0
        self.partitions_done = 0
        self.short_partitions = 0
        self.cum_lat_ms = 0.0     # 임계경로 계산용 (동시성과 무관)
        self.cum_lat_n = 0

    def reset_window(self):
        self.w_requests = 0
        self.w_ok = 0
        self.w_429 = 0
        self.w_5xx = 0
        self.w_4xx = 0
        self.w_conn = 0
        self.w_empty = 0
        self.w_reviews = 0
        self.w_lat = []

    def record(self, kind, latency_ms=None, n_reviews=0):
        with self.lock:
            self.w_requests += 1
            self.cum_requests += 1
            if latency_ms is not None:
                self.w_lat.append(latency_ms)
                self.cum_lat_ms += latency_ms
                self.cum_lat_n += 1
            if kind == "ok":
                self.w_ok += 1
                self.w_reviews += n_reviews
                self.cum_reviews += n_reviews
                self.pages_done += 1
                if n_reviews == 0:
                    self.w_empty += 1
            elif kind == "429":
                self.w_429 += 1
                self.cum_429 += 1
                self.cum_err += 1
                if self.first_429_at is None:
                    self.first_429_at = time.time()
            elif kind == "5xx":
                self.w_5xx += 1
                self.cum_err += 1
            elif kind == "4xx":
                self.w_4xx += 1
                self.cum_err += 1
            else:
                self.w_conn += 1
                self.cum_err += 1

    def snapshot_and_reset(self):
        with self.lock:
            lat = sorted(self.w_lat)
            snap = dict(
                requests=self.w_requests, ok=self.w_ok, http_429=self.w_429,
                http_5xx=self.w_5xx, http_4xx=self.w_4xx, conn_err=self.w_conn,
                empty=self.w_empty, reviews=self.w_reviews,
                p50=lat[len(lat) // 2] if lat else 0,
                p95=lat[int(len(lat) * 0.95)] if lat else 0,
                cum_reviews=self.cum_reviews, cum_requests=self.cum_requests,
                pages_done=self.pages_done, partitions_done=self.partitions_done,
            )
            self.reset_window()
            return snap


# ---------------------------------------------------------------------------
# 이벤트 로그
# ---------------------------------------------------------------------------
class EventLog:
    def __init__(self, path):
        self.lock = threading.Lock()
        self.f = open(path, "a", encoding="utf-8", newline="")
        if self.f.tell() == 0:
            self.f.write("ts_utc,elapsed_s,kind,partition,detail\n")
        self.recent = deque(maxlen=40)

    def add(self, t0, kind, partition, detail):
        line = "%s,%.1f,%s,%s,%s\n" % (
            datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
            time.time() - t0, kind, partition,
            str(detail).replace(",", ";").replace("\n", " ")[:200],
        )
        with self.lock:
            self.f.write(line)
            self.f.flush()
            self.recent.append((kind, partition, detail))

    def close(self):
        try:
            self.f.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# HTTP 한 번
# ---------------------------------------------------------------------------
def fetch_page(appid, language, cursor, timeout=30):
    """반환: (kind, reviews, next_cursor, latency_ms, detail)"""
    qs = urllib.parse.urlencode({
        "json": 1,
        "language": language,
        "purchase_type": "all",
        "review_type": "all",
        "filter": "recent",
        "num_per_page": 100,
        "cursor": cursor,
    })
    url = "https://store.steampowered.com/appreviews/%d?%s" % (appid, qs)
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
    })
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
        ms = (time.time() - t) * 1000.0
        try:
            d = json.loads(body.decode("utf-8", "replace"))
        except Exception as e:
            return ("bad_json", [], None, ms, "%s len=%d" % (e, len(body)))
        if d.get("success") != 1:
            return ("api_fail", [], None, ms, "success=%s" % d.get("success"))
        total = (d.get("query_summary") or {}).get("total_reviews")
        return ("ok", d.get("reviews", []) or [], d.get("cursor"), ms,
                "" if total is None else "total=%d" % total)
    except urllib.error.HTTPError as e:
        ms = (time.time() - t) * 1000.0
        code = e.code
        kind = "429" if code == 429 else ("5xx" if 500 <= code < 600 else "4xx")
        retry_after = e.headers.get("Retry-After") if e.headers else None
        return (kind, [], None, ms, "HTTP %d retry_after=%s" % (code, retry_after))
    except Exception as e:
        ms = (time.time() - t) * 1000.0
        return ("conn", [], None, ms, "%s: %s" % (type(e).__name__, e))


# ---------------------------------------------------------------------------
# 워커
# ---------------------------------------------------------------------------
def worker(idx, queue, qlock, gate, stats, events, ckpt, ckpt_lock,
           stop_evt, t0, args):
    while not stop_evt.is_set():
        with qlock:
            if not queue:
                return
            appid, language, name = queue.pop(0)
        part = "%d:%s" % (appid, language)
        cursor = ckpt.get(part, {}).get("cursor", "*")
        saved = ckpt.get(part, {}).get("reviews", 0)
        if ckpt.get(part, {}).get("done"):
            continue

        out_path = os.path.join(REVIEW_DIR, "%d_%s.jsonl.gz" % (appid, language))
        fh = gzip.open(out_path, "at", encoding="utf-8")
        pages = 0
        seen_cursor = set()
        backoff = 0.0
        strikes = 0          # 빈 페이지 / 커서 반복 연속 횟수
        MAX_STRIKES = 4      # 이만큼 연속돼야 진짜 끝으로 인정
        total_expected = ckpt.get(part, {}).get("total")

        try:
            while not stop_evt.is_set():
                if backoff > 0:
                    slept = 0.0
                    while slept < backoff and not stop_evt.is_set():
                        time.sleep(min(0.5, backoff - slept))
                        slept += 0.5
                    backoff = 0.0

                if not gate.acquire(stop_evt):
                    break
                try:
                    kind, reviews, nxt, ms, detail = fetch_page(appid, language, cursor)
                finally:
                    gate.release()

                stats.record(kind, ms, len(reviews))

                if kind != "ok":
                    events.add(t0, kind, part, detail)
                    if kind == "429":
                        backoff = min(60.0, 5.0 + random.random() * 5.0)
                    elif kind in ("5xx", "conn", "bad_json"):
                        backoff = min(30.0, 2.0 + random.random() * 3.0)
                    else:  # 4xx / api_fail 은 재시도해도 안 됨
                        events.add(t0, "partition_abort", part, detail)
                        break
                    continue

                if total_expected is None and detail.startswith("total="):
                    total_expected = int(detail.split("=", 1)[1])

                if reviews:
                    for rv in reviews:
                        rv["_appid"] = appid
                        rv["_language"] = language
                        fh.write(json.dumps(rv, ensure_ascii=False) + "\n")
                    saved += len(reviews)
                    pages += 1

                # 끝 판정 -- 한 번의 빈 페이지로 끝내면 안 됩니다.
                # 실측: 순회 도중 빈 응답이 분당 2~8건씩 섞여 나오고, 이걸 끝으로
                # 처리하면 큰 파티션에서 리뷰의 98% 를 놓칩니다.
                is_end_signal = (not reviews) or (nxt is None) or (nxt in seen_cursor)
                if is_end_signal:
                    strikes += 1
                    if strikes < MAX_STRIKES:
                        events.add(t0, "empty_retry", part,
                                   "strike %d/%d saved=%d" % (strikes, MAX_STRIKES, saved))
                        backoff = 1.5 * strikes
                        if nxt and nxt not in seen_cursor:
                            seen_cursor.add(nxt)
                            cursor = nxt          # 커서가 살아 있으면 진행
                        continue                  # 아니면 같은 커서로 재시도
                    cov = ("%.1f%%" % (saved / total_expected * 100.0)) if total_expected else "?"
                    with ckpt_lock:
                        ckpt[part] = {"cursor": cursor, "reviews": saved, "done": True,
                                      "total": total_expected, "coverage": cov}
                    with stats.lock:
                        stats.partitions_done += 1
                        if total_expected and saved < total_expected * 0.9:
                            stats.short_partitions += 1
                    events.add(t0, "partition_done", part,
                               "%s reviews=%d/%s (%s) pages=%d" % (
                                   name, saved, total_expected, cov, pages))
                    break

                strikes = 0
                seen_cursor.add(nxt)
                cursor = nxt

                if pages % 25 == 0:
                    with ckpt_lock:
                        ckpt[part] = {"cursor": cursor, "reviews": saved, "done": False,
                                      "total": total_expected}

                if args.delay > 0:
                    time.sleep(args.delay)
        finally:
            try:
                fh.close()
            except Exception:
                pass
            with ckpt_lock:
                cur = ckpt.get(part, {})
                if not cur.get("done"):
                    ckpt[part] = {"cursor": cursor, "reviews": saved, "done": False,
                                  "total": total_expected}


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
def parse_ramp(s):
    """'8:60,16:60,32:0' -> [(8,60),(16,60),(32,0)]  0분 = 끝까지"""
    out = []
    for chunk in s.split(","):
        c, m = chunk.split(":")
        out.append((int(c), float(m)))
    return out


def human(n):
    return "{:,}".format(int(n))


def main():
    ap = argparse.ArgumentParser(description="Steam appreviews 지속 수집 검증")
    ap.add_argument("--hours", type=float, default=8.0, help="실행 시간 (기본 8)")
    ap.add_argument("--concurrency", type=int, default=12, help="동시 진행 파티션 수 (기본 12)")
    ap.add_argument("--ramp", type=str, default="",
                    help="동시성 단계 변경. 예: 8:60,16:60,32:60,48:0 (한도:분)")
    ap.add_argument("--delay", type=float, default=0.0, help="워커별 요청 간 추가 대기(초)")
    ap.add_argument("--resume", action="store_true", help="checkpoint 부터 이어서")
    ap.add_argument("--games", type=int, default=400,
                    help="Query API 로 받아올 게임 수. 0 이면 하드코딩 목록만 (기본 400)")
    args = ap.parse_args()

    # 새로 시작하는데 out/ 에 이전 실행 결과가 있으면 옆으로 치웁니다.
    # (jsonl.gz 가 append 모드라 섞이면 리뷰가 중복됩니다)
    if not args.resume and os.path.isdir(OUT_DIR) and os.listdir(OUT_DIR):
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        bak = OUT_DIR + "_" + stamp
        os.rename(OUT_DIR, bak)
        print("이전 실행 결과를 %s 로 옮겼습니다." % os.path.basename(bak))

    os.makedirs(REVIEW_DIR, exist_ok=True)

    ramp = parse_ramp(args.ramp) if args.ramp else [(args.concurrency, 0.0)]
    max_conc = max(c for c, _ in ramp)

    ckpt = {}
    if args.resume and os.path.exists(CKPT_PATH):
        with open(CKPT_PATH, encoding="utf-8") as f:
            ckpt = json.load(f)
        print("checkpoint 로드: 파티션 %d개" % len(ckpt))
    elif os.path.exists(CKPT_PATH) and not args.resume:
        print("주의: checkpoint 가 있습니다. 이어서 하려면 --resume 을 주세요. 지금은 새로 시작합니다.")

    all_targets = TARGETS
    if args.games > 0:
        try:
            all_targets, n_games = build_targets(args.games)
            print("파티션 생성: 게임 %d개 x 언어 -> %d개 파티션" % (n_games, len(all_targets)))
        except Exception as e:
            print("파티션 생성 실패(%s: %s). 하드코딩 목록 %d개로 진행합니다."
                  % (type(e).__name__, e, len(TARGETS)))
            all_targets = TARGETS

    queue = [t for t in all_targets
             if not ckpt.get("%d:%s" % (t[0], t[1]), {}).get("done")]
    if not queue:
        print("모든 파티션이 done 입니다. checkpoint.json 을 지우고 다시 실행하세요.")
        return
    if len(queue) < max_conc:
        print("주의: 파티션 %d개 < 최대 동시성 %d. 후반에 동시성이 떨어집니다."
              % (len(queue), max_conc))

    stats = Stats()
    events = EventLog(os.path.join(OUT_DIR, "events.csv"))
    gate = Gate(ramp[0][0])
    stop_evt = threading.Event()
    qlock = threading.Lock()
    ckpt_lock = threading.Lock()
    t0 = time.time()

    def on_sigint(sig, frm):
        print("\n중단 요청. 정리 중입니다...")
        stop_evt.set()
    signal.signal(signal.SIGINT, on_sigint)

    mpath = os.path.join(OUT_DIR, "metrics.csv")
    new_file = not os.path.exists(mpath) or os.path.getsize(mpath) == 0
    mf = open(mpath, "a", encoding="utf-8", newline="")
    if new_file:
        mf.write("ts_utc,elapsed_min,concurrency,requests,ok,http_429,http_5xx,"
                 "http_4xx,conn_err,empty,reviews,reviews_per_sec,req_per_sec,"
                 "p50_ms,p95_ms,cum_reviews,pages_done,partitions_done\n")
        mf.flush()

    workers = []
    for i in range(max_conc):
        th = threading.Thread(target=worker, name="w%d" % i, daemon=True,
                              args=(i, queue, qlock, gate, stats, events, ckpt,
                                    ckpt_lock, stop_evt, t0, args))
        th.start()
        workers.append(th)

    print("=" * 78)
    print("지속 수집 검증 시작")
    print("  실행 시간   %.1f시간" % args.hours)
    print("  동시성      %s" % (" -> ".join("%d(%.0f분)" % (c, m) if m else "%d(끝까지)" % c
                                            for c, m in ramp)))
    print("  파티션      %d개 대기" % len(queue))
    print("  산출물      %s" % OUT_DIR)
    print("  중단        Ctrl+C (checkpoint 와 summary 는 남습니다)")
    print("=" * 78)
    print()
    print("  경과   동시성   리뷰/초   요청/초   429   오류   p95(ms)   누적 리뷰")
    print("  " + "-" * 70)

    deadline = t0 + args.hours * 3600.0
    ramp_i, ramp_started = 0, t0
    minute = 0
    try:
        while not stop_evt.is_set() and time.time() < deadline:
            # 1분 대기 (0.5초 단위로 중단 확인)
            target = t0 + (minute + 1) * 60.0
            while time.time() < target and not stop_evt.is_set() and time.time() < deadline:
                time.sleep(0.5)
                if all(not th.is_alive() for th in workers):
                    break
            minute += 1

            snap = stats.snapshot_and_reset()
            elapsed_min = (time.time() - t0) / 60.0
            rps_rev = snap["reviews"] / 60.0
            rps_req = snap["requests"] / 60.0
            mf.write("%s,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.2f,%.0f,%.0f,%d,%d,%d\n" % (
                datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"), elapsed_min,
                gate.limit, snap["requests"], snap["ok"], snap["http_429"],
                snap["http_5xx"], snap["http_4xx"], snap["conn_err"], snap["empty"],
                snap["reviews"], rps_rev, rps_req, snap["p50"], snap["p95"],
                snap["cum_reviews"], snap["pages_done"], snap["partitions_done"]))
            mf.flush()

            err = snap["http_5xx"] + snap["http_4xx"] + snap["conn_err"]
            print("  %5.0f분   %5d   %7.1f   %7.2f   %3d   %4d   %7.0f   %s" % (
                elapsed_min, gate.limit, rps_rev, rps_req, snap["http_429"], err,
                snap["p95"], human(snap["cum_reviews"])))

            # ramp 단계 전환
            if ramp_i < len(ramp) - 1:
                _, mins = ramp[ramp_i]
                if mins and (time.time() - ramp_started) >= mins * 60.0:
                    ramp_i += 1
                    ramp_started = time.time()
                    gate.set_limit(ramp[ramp_i][0])
                    events.add(t0, "ramp", "-", "concurrency -> %d" % gate.limit)
                    print("  >>> 동시성 %d 로 변경" % gate.limit)

            with ckpt_lock:
                with open(CKPT_PATH, "w", encoding="utf-8") as f:
                    json.dump(ckpt, f, ensure_ascii=False, indent=1)

            if all(not th.is_alive() for th in workers):
                print("\n모든 파티션 완료.")
                break
    finally:
        stop_evt.set()
        for th in workers:
            th.join(timeout=20)
        with ckpt_lock:
            with open(CKPT_PATH, "w", encoding="utf-8") as f:
                json.dump(ckpt, f, ensure_ascii=False, indent=1)
        mf.close()

        wall = max(1.0, time.time() - t0)
        elapsed_h = wall / 3600.0
        cum = stats.cum_reviews
        rate = cum / wall
        proj_24h = rate * 86400.0
        need_h = 150_000_000 / max(1e-9, rate) / 3600.0

        # 제약 1: 총 처리량
        ok_throughput = proj_24h > 150_000_000
        # 제약 2: 임계경로 = 파티션 하나를 순차로 다 도는 시간
        #   요청 평균 지연으로 계산합니다. 동시성과 무관한 값입니다.
        #   CS2 russian 27,032 페이지가 실측 최대 파티션입니다.
        sec_per_page = (stats.cum_lat_ms / stats.cum_lat_n / 1000.0) if stats.cum_lat_n else 0.0
        critpath_h = 27032 * sec_per_page / 3600.0
        ok_critpath = critpath_h < 24.0

        lines = []
        lines.append("=" * 70)
        lines.append("지속 수집 검증 결과")
        lines.append("=" * 70)
        lines.append("측정일        %s" % datetime.now().strftime("%Y-%m-%d %H:%M"))
        lines.append("실행 시간     %.2f시간" % elapsed_h)
        lines.append("동시성        %s" % (" -> ".join("%d" % c for c, _ in ramp)))
        lines.append("")
        lines.append("수집 리뷰     %s건" % human(cum))
        lines.append("요청 수       %s건" % human(stats.cum_requests))
        lines.append("완료 파티션   %d개  (그중 90%% 미만 수집 %d개)"
                     % (stats.partitions_done, stats.short_partitions))
        lines.append("")
        lines.append("-- 제약 1 · 총 처리량 " + "-" * 45)
        lines.append("지속 처리율   %.1f 리뷰/초" % rate)
        lines.append("24시간 환산   %s건   (필요 1.5억)" % human(proj_24h))
        lines.append("1.5억 소요    %.1f시간" % need_h)
        lines.append("판정          %s" % ("통과" if ok_throughput else "미달"))
        lines.append("")
        lines.append("-- 제약 2 · 임계경로 " + "-" * 46)
        lines.append("페이지당      %.2f초 (워커 1개 기준)" % sec_per_page)
        lines.append("최대 파티션   CS2 russian 27,032 페이지 -> %.1f시간" % critpath_h)
        lines.append("판정          %s" % ("통과" if ok_critpath else "미달"))
        lines.append("              이 시간은 동시성을 올려도 줄어들지 않습니다.")
        lines.append("              파티션 하나 안에서는 커서를 순차로 따라가야 합니다.")
        lines.append("")
        lines.append("-- 종합 " + "-" * 59)
        if ok_throughput and ok_critpath:
            lines.append("하루 1회 전량 재수집 성립. 소요 %.1f시간" %
                         max(need_h, critpath_h))
        else:
            lines.append("하루 1회 전량 재수집 성립하지 않음")
            if not ok_throughput:
                lines.append("  · 처리량 부족: 동시성을 올려야 합니다 (차단 여부를 함께 봐야 함)")
            if not ok_critpath:
                lines.append("  · 임계경로 초과: 큰 파티션을 언어보다 더 잘게 쪼개야 합니다")
            lines.append("  · 대안: 증분 + 되돌아보기 창(30일=91.9%) 또는 수집 대상 축소")
        lines.append("")
        lines.append("429 발생      %d회" % stats.cum_429)
        if stats.first_429_at:
            lines.append("첫 429        시작 후 %.1f분" % ((stats.first_429_at - t0) / 60.0))
        else:
            lines.append("첫 429        없음 (%.2f시간 동안 차단 없음)" % elapsed_h)
        lines.append("기타 오류     %d회" % (stats.cum_err - stats.cum_429))
        lines.append("")
        lines.append("상세          metrics.csv (1분 단위) · events.csv (이상 징후)")
        lines.append("=" * 70)
        txt = "\n".join(lines)

        with open(os.path.join(OUT_DIR, "summary.txt"), "w", encoding="utf-8") as f:
            f.write(txt + "\n")
        print()
        print(txt)
        events.close()


if __name__ == "__main__":
    main()
