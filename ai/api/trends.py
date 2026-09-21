# -*- coding: utf-8 -*-
"""화면 01 '반응 추세' 통계 요약 — 일별 집계·패치 시점을 문장으로. Qwen 1회(선택), 저장 없음(백엔드 캐시).

/reviews/summarize 는 리뷰 원문을 받지만 이 화면은 원문이 아니라 집계를 요약한다. 그래서 별도다.

수치는 전부 여기서 계산한다(facts). Qwen 은 그 수치를 한국어 문장으로 옮기기만 하고 새 숫자를 만들지 않는다.
use_llm=false 면 문장 틀로만 만든다(밀리초).

금지 규칙 세 가지는 기획안 4.2·10장에서 온 것이다.
  - 패치가 긍정률 변화의 원인이라고 쓰지 않는다(같은 시기에 함께 관측됐다고만 쓴다).
  - 첫 작성·수정을 신규·기존 유저로 해석하지 않는다.
  - 수정 리뷰의 전환 방향(긍정→부정)을 단정하지 않는다. 이전 상태를 저장하지 않는다.
"""
import json
import os
import re
import urllib.request

import httpx

from common import OLLAMA_URL
from qwen_prompt import MODEL

# GMS 폴백 (9/21). 요약은 이 노트북의 Qwen 이 먼저 맡고, Qwen 이 죽었거나 LOCAL_TIMEOUT 안에 답하지 못하면
# SSAFY GMS(gpt-4.1) 로 넘어간다. 배치(백필)는 크레딧이 모자라 GMS 를 쓰지 않는다 — 요청 시 요약만.
# 키는 환경 변수로만 받는다(start.ps1 이 저장소 밖 api.txt 에서 읽어 넣는다). 없으면 폴백 없이 문장 틀로 내려간다.
GMS_URL = os.environ.get("GMS_URL", "https://gms.ssafy.io/gmsapi/api.openai.com/v1/responses")
GMS_KEY = os.environ.get("GMS_API_KEY", "")
GMS_MODEL = os.environ.get("GMS_MODEL", "gpt-4.1")
LOCAL_TIMEOUT = float(os.environ.get("TREND_LOCAL_TIMEOUT", "45"))   # Qwen 정상 30초 안팎(9/18 실측). 넘으면 GPU 가 바쁜 것

MIN_DAY_REVIEWS = 10   # 이 미만인 날은 '최저 긍정률일' 후보에서 뺀다(하루 2건으로 0% 가 나오는 것을 막는다)
TREND_VERSION = "trend-facts-1"

# 9/21: 이전 프롬프트는 "수치를 문장으로 옮기기만" 하게 했다. 그 결과 화면 카드에 이미 있는 총계를
# 되풀이하는 요약이 나왔다(팀 피드백 "수치해석에 치우쳐 해석이 없다"). 역할을 바꾼다 —
# 기획자가 묻는 것은 "비슷한 패치를 하면 반응이 어떻게 움직이나"이므로, 되돌림·지속·집중을 말하게 한다.
# 인과 단정 금지는 그대로 둔다(데이터가 인과를 증명하지 못한다). 관측 서술은 인과가 아니므로 오히려 권한다.
SYSTEM = """당신은 게임 기획자를 돕는 반응 추세 해설자입니다. 기획자는 '비슷한 패치를 하면 반응이 어떻게 움직이는가'를 알고 싶어 합니다. 그 판단에 쓰이는 말만 씁니다.
쓰는 법: 첫 문장에서 이 기간이 어떤 사례인지 규정합니다. 패치가 있으면 무엇을 바꾼 패치인지 제목에서 짧게 집고, 전후 차이와 되돌아왔는지·며칠 걸렸는지·반응이 얼마나 몰렸는지를 씁니다.
기간 리뷰 총계와 기간 긍정률은 화면에 표로 이미 나와 있으므로 문장에서 되풀이하지 않습니다.
권장 표현: '되돌아왔습니다', '이어졌습니다', '몰렸습니다', '함께 나타났습니다'. 관측된 움직임은 이렇게 적습니다.
금지: 인과 단정('때문에', '탓에', '영향으로', '원인'). 패치와 지표 변화는 '같은 시기에 함께 나타났다'로만 잇습니다. 예측('~할 것이다')과 조언('~해야 한다', '~하는 것이 좋다'). 주어진 수치 밖의 숫자와 계산. 출시 시점·게임 배경·개발사 의도·유저 심리 추측. 첫 작성·수정을 신규 유저·기존 팬으로 바꿔 부르는 것.
문체: 반드시 한국어, 중국어·일본어 문자 금지, 모든 문장을 '습니다' 또는 '입니다' 로 끝냅니다.
좋은 예: '마법 하향 패치 직후 강한 반발이 엿새 이어졌다가 핫픽스와 함께 대부분 돌아온 사례입니다. 패치 전후 7일 긍정률은 75.5%와 44.1%가 함께 관측됐고, 하루 리뷰는 평소의 4.6배까지 몰렸습니다. 핫픽스 6일 뒤 긍정률은 패치 전의 84% 수준으로 돌아왔습니다.'
나쁜 예(총계 되풀이·해석 없음): '전체 기간 16,453건의 리뷰가 작성되었으며 긍정률은 62.4%를 기록했습니다.'
JSON 으로만 답합니다: title(이 사례의 성격을 한 줄, 25자 내. 기간이나 게임 이름을 나열하지 않습니다), summary(이어지는 한국어 문장 2~3개, 번호·목록 없음)."""
FORMAT = {
    "type": "object", "additionalProperties": False, "required": ["title", "summary"],
    "properties": {"title": {"type": "string"}, "summary": {"type": "string"}},
}
BAD_CJK = re.compile(r"[一-鿿぀-ヿ]")
CAUSAL = re.compile(r"(때문|탓|영향|원인|덕분|덕에|야기|초래|로 인해|로 인한|효과로|이끌|견인|주도)")
# 데이터에 없는 배경을 지어내는 어휘. 출시 시점·유저 심리·개발사 의도는 입력에 없다.
SPECULATION = re.compile(r"(출시|런칭|정식 공개|유저들의 기대|실망|불만이 커|개발사의|의도적)")
# "핫픽스 적용으로 긍정률이 상승" 처럼 조사 '으로' 가 인과로 읽히는 경우.
# '전반적으로' 같은 부사는 앞 글자가 '적' 이므로 제외한다.
CAUSAL_BY = re.compile(r"(?<!적)으로\s+[^.]{0,20}?(상승|하락|증가|감소|올랐|떨어졌|개선|악화)")
ADVICE = re.compile(r"(해야 한다|하는 것이 좋|권장|필요하다|바람직|할 것이다|전망)")
# 화면에 여러 요약이 나란히 붙으므로 종결을 '습니다' 체로 고정한다. 반말체 종결을 잡는다.
PLAIN_END = re.compile(r"(?<!습니)(?<!입니)다\.(?=\s|$)")
NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")
MAX_TITLE = 40

# Qwen3.5 가 숫자와 단위·조사 사이에 공백을 넣는다("2,256 건", "61.3% 를"). 화면에 그대로 나가므로 붙인다.
SPACE_NUM = re.compile(r"(\d)\s+(건|일|월|년|개|명|시간|배|%p|%)")
SPACE_JOSA = re.compile(r"(%p|%|건|일|월|년|개|명)\s+(를|을|이|가|은|는|에서|까지|부터|으로|로|와|과|의|에|도|만)(?=[\s,.]|$)")


def tidy(text):
    """숫자·단위 사이 공백 정리. 두 번 도는 이유는 '9 월 7 일' 처럼 연달아 붙는 경우 때문이다.
    줄바꿈은 공백으로 바꾼다 — 화면은 한 문단으로 받는다."""
    text = re.sub(r"\s*\n\s*", " ", text.strip())
    for _ in range(2):
        text = SPACE_JOSA.sub(r"\1\2", SPACE_NUM.sub(r"\1\2", text))
    return re.sub(r" {2,}", " ", text)


def _canon(n):
    """'08' 과 '8', '73.0' 과 '73' 을 같게 본다. 날짜(2026-08-17)의 08 과 본문의 8월이 갈리지 않게."""
    try:
        f = float(n)
        return str(int(f)) if f == int(f) else str(f)
    except ValueError:
        return n


def unknown_numbers(text, source):
    """요약에 나온 숫자 중 입력 수치에 없는 것. 지어낸 값을 막는다."""
    have = {_canon(n) for n in NUM.findall(source.replace(",", ""))}
    return [n for n in NUM.findall(text.replace(",", "")) if _canon(n) not in have]


def _pct(part, whole):
    return round(100.0 * part / whole, 1) if whole else None


def _agg(rows):
    """행 묶음 → (리뷰 수, 긍정률)"""
    n = sum(d["reviews"] for d in rows)
    return n, _pct(sum(d["positive"] for d in rows), n)


def _span(days, start, end):
    """[start, end) 날짜 구간 합계 → (리뷰 수, 긍정률)"""
    return _agg([d for d in days if start <= d["date"] < end])


RECOVERY_RATIO = 0.9   # 패치 전 긍정률의 90% 이상으로 돌아오면 '되돌아왔다'로 본다
SURGE_RATIO = 1.5      # 평소 일평균의 1.5배 이상이면 반응이 몰린 날


def _ma(days, i, w=7):
    """i 번째 날부터 w 일 이동 구간의 (리뷰 수, 긍정률). 구간이 모자라면 있는 만큼만 본다."""
    seg = days[i:i + w]
    return _agg(seg) if seg else (0, None)


def _recovery(days, patch_date, before_p, later_patches):
    """패치 뒤 긍정률이 패치 전 수준으로 돌아오기까지 걸린 일수.

    왜 필요한가(9/21): 기획자가 묻는 것은 '떨어졌나'가 아니라 '되돌아왔나, 얼마나 걸렸나'다.
    하루치 등락에 흔들리지 않게 7일 이동 구간으로 본다.
    긍정률이 오른 패치(핫픽스 등)에는 계산하지 않는다 — 낮은 직전 값이 기준이 되어 '즉시 회복'이 되기 때문.
    다음 패치가 있어도 끊지 않고 기간 끝까지 추적하되, 그 사이에 있었던 패치를 via 로 함께 준다.
    """
    if not before_p:
        return None
    idx = [i for i, d in enumerate(days) if d["date"] >= patch_date]
    if not idx:
        return None
    target = before_p * RECOVERY_RATIO
    best = None
    for i in idx:
        n, p = _ma(days, i)
        if p is None or n < MIN_DAY_REVIEWS:
            continue
        pct = round(100.0 * p / before_p, 0)
        if best is None or pct > best:
            best = pct
        if p >= target:
            via = [d for d in later_patches if patch_date < d <= days[i]["date"]]
            return {"recovered": True, "days": _diff_days(patch_date, days[i]["date"]),
                    "pct_of_before": min(int(pct), 999), "level_pct": p, "via": via}
    return {"recovered": False, "days": None, "pct_of_before": int(best) if best is not None else None,
            "level_pct": None, "via": []}


def _surge(days, patch_date, before_n, window_days, until_date):
    """패치 뒤 리뷰가 평소의 몇 배로 늘었고 그 상태가 며칠 이어졌는지."""
    base = before_n / window_days if before_n else 0
    seg = [d for d in days if patch_date <= d["date"] < until_date]
    if not base or not seg:
        return None
    after_avg = sum(d["reviews"] for d in seg[:window_days]) / min(len(seg), window_days)
    run = 0
    for d in seg:
        if d["reviews"] >= base * SURGE_RATIO:
            run += 1
        else:
            break
    ratio = round(after_avg / base, 1)
    # 줄어든 경우까지 '몰렸다'로 말하지 않도록 20% 이상 는 경우만 내보낸다
    return {"ratio": ratio, "days": run, "base_per_day": round(base, 1)} if ratio >= 1.2 else None


def _diff_days(a, b):
    from datetime import date as D
    ya, ma, da = (int(x) for x in a.split("-")); yb, mb, db = (int(x) for x in b.split("-"))
    return (D(yb, mb, db) - D(ya, ma, da)).days


def compute_facts(daily, patches, window_days):
    """일별 집계 → 화면에 그대로 쓸 수 있는 수치. 계산은 전부 여기서 끝난다."""
    days = sorted(daily, key=lambda d: d["date"])
    if not days:
        # 호출 쪽(main)이 먼저 거르지만, 이 함수만 따로 쓰는 경우에도 죽지 않게 둔다
        return [{"key": "total_reviews", "label": "기간 리뷰", "value": "0건"}], []
    total = sum(d["reviews"] for d in days)
    pos = sum(d["positive"] for d in days)
    facts = [{"key": "period", "label": "기간", "value": f"{days[0]['date']} ~ {days[-1]['date']}"},
             {"key": "total_reviews", "label": "기간 리뷰", "value": f"{total:,}건"},
             {"key": "positive_pct", "label": "기간 긍정률", "value": f"{_pct(pos, total)}%" if total else "집계 없음"}]

    # 앞 1/3 과 뒤 1/3 의 긍정률 비교. 하루 단위 등락이 아니라 기간 안에서의 이동을 본다.
    # 하루짜리 요청에서는 비교할 구간이 없으므로 이 절을 넣지 않는다.
    if len(days) >= 2:
        third = max(1, len(days) // 3)
        _, early_p = _agg(days[:third])
        _, late_p = _agg(days[-third:])
        if early_p is not None and late_p is not None:
            facts.append({"key": "shift", "label": "기간 내 이동",
                          "value": f"앞 {third}일 {early_p}% → 뒤 {third}일 {late_p}%",
                          "delta_pct": round(late_p - early_p, 1)})

    if len(days) >= 2 and total:
        peak = max(days, key=lambda d: d["reviews"])
        facts.append({"key": "peak_day", "label": "리뷰가 가장 많은 날", "value": f"{peak['date']} {peak['reviews']:,}건",
                      "detail": f"긍정률 {_pct(peak['positive'], peak['reviews'])}%"})
    scored = [d for d in days if d["reviews"] >= MIN_DAY_REVIEWS]
    if len(days) >= 2 and scored:
        low = min(scored, key=lambda d: d["positive"] / d["reviews"])
        facts.append({"key": "low_day", "label": "긍정률이 가장 낮은 날",
                      "value": f"{low['date']} {_pct(low['positive'], low['reviews'])}%",
                      "detail": f"리뷰 {low['reviews']:,}건 · {MIN_DAY_REVIEWS}건 미만인 날 제외"})

    # 채널 분해. 백엔드가 first_/edited_ 를 안 보내면 이 절은 통째로 생략한다.
    fn = sum(d.get("first_reviews") or 0 for d in days)
    en = sum(d.get("edited_reviews") or 0 for d in days)
    if fn or en:
        fp = sum(d.get("first_positive") or 0 for d in days)
        ep = sum(d.get("edited_positive") or 0 for d in days)
        # 숫자 두 가지(긍정률·비중)가 나란히 오므로 무엇인지 문구에 박아 둔다.
        # 그냥 퍼센트만 적으면 Qwen 이 긍정률을 '비중' 이라고 바꿔 부른다(9/16 확인).
        facts.append({"key": "channel", "label": "작성 경로",
                      "value": f"첫 작성 {fn:,}건(긍정률 {_pct(fp, fn)}%) · 수정 {en:,}건(긍정률 {_pct(ep, en)}%)",
                      "detail": f"전체 중 수정이 차지하는 비중 {_pct(en, fn + en)}%"})

    effects = []
    dates = sorted(p["date"] for p in patches)
    for p in patches:
        before_n, before_p = _span(days, _shift(p["date"], -window_days), p["date"])
        after_n, after_p = _span(days, p["date"], _shift(p["date"], window_days))
        delta = round(after_p - before_p, 1) if (before_p is not None and after_p is not None) else None
        # 회복·급증은 '다음 패치 전까지'만 본다. 다음 패치가 섞이면 무엇이 되돌린 것인지 갈린다
        nxt = next((d for d in dates if d > p["date"]), "9999-12-31")
        effects.append({"title": p.get("title") or p.get("version") or "패치", "date": p["date"],
                        "version": p.get("version"), "gid": p.get("gid"),
                        "before": {"reviews": before_n, "positive_pct": before_p},
                        "after": {"reviews": after_n, "positive_pct": after_p},
                        "delta_pct": delta, "window_days": window_days,
                        # 긍정률이 떨어진 패치에서만 '되돌아왔는지'를 묻는다
                        "recovery": _recovery(days, p["date"], before_p, [d for d in dates if d > p["date"]])
                        if (delta is not None and delta < 0) else None,
                        "surge": _surge(days, p["date"], before_n, window_days, nxt),
                        "next_patch_date": None if nxt == "9999-12-31" else nxt,
                        "note": None if before_n and after_n else "비교 구간에 리뷰가 없어 값을 내지 않았습니다."})
    return facts, effects


def _shift(date, days):
    from datetime import date as D, timedelta
    y, m, d = (int(x) for x in date.split("-"))
    return (D(y, m, d) + timedelta(days=days)).isoformat()


def build_lines(game, facts, effects):
    lines = [f"게임: {game}"] + [f"- {f['label']}: {f['value']}" + (f" ({f['detail']})" if f.get("detail") else "")
                                 for f in facts]
    for e in effects:
        if e["delta_pct"] is None:
            lines.append(f"- 패치 {e['title']}({e['date']}): 비교 구간 리뷰 부족")
            continue
        lines.append(f"- 패치 {e['title']}({e['date']}) 전후 {e['window_days']}일: "
                     f"긍정률 {e['before']['positive_pct']}% → {e['after']['positive_pct']}% "
                     f"({e['delta_pct']:+}%p), 리뷰 {e['before']['reviews']:,}건 → {e['after']['reviews']:,}건")
        sg = e.get("surge")
        if sg and sg["ratio"]:
            lines.append(f"  · 반응 규모: 패치 뒤 하루 리뷰가 평소의 {sg['ratio']}배, 몰린 상태가 {sg['days']}일 이어짐")
        rc = e.get("recovery")
        if rc and rc["recovered"]:
            via = f", 그 사이 패치 {', '.join(rc['via'])} 있었음" if rc.get("via") else ""
            lines.append(f"  · 되돌림: 패치 {rc['days']}일 뒤 긍정률이 패치 전의 {rc['pct_of_before']}% 수준"
                         f"({rc['level_pct']}%)으로 돌아옴{via}")
        elif rc:
            back = f"최고 {rc['pct_of_before']}% 수준까지" if rc["pct_of_before"] is not None else "회복 지점 없음"
            lines.append(f"  · 되돌림: 기간 끝까지 패치 전 수준으로 돌아오지 못함({back})")
    return "\n".join(lines)


def template_summary(facts, effects):
    """LLM 없이 만드는 요약. use_llm=false 이거나 Qwen 이 죽었을 때 쓴다."""
    by = {f["key"]: f for f in facts}
    parts = [f"기간 리뷰는 {by['total_reviews']['value']}이고 긍정률은 {by['positive_pct']['value']}입니다."]
    if "shift" in by:
        d = by["shift"].get("delta_pct")
        move = "올랐습니다" if (d or 0) > 0 else "내렸습니다" if (d or 0) < 0 else "거의 같습니다"
        parts.append(f"기간 안에서 긍정률은 {by['shift']['value']}로 {move}.")
    if "channel" in by:
        parts.append(f"작성 경로별로는 {by['channel']['value']}이고 {by['channel']['detail']}입니다.")
    done = [e for e in effects if e["delta_pct"] is not None]
    if done:
        e = max(done, key=lambda x: abs(x["delta_pct"]))
        parts.append(f"패치 {e['title']}({e['date']}) 전후 {e['window_days']}일에는 긍정률 "
                     f"{e['before']['positive_pct']}%와 {e['after']['positive_pct']}%가 함께 관측됐습니다.")
        rc, sg = e.get("recovery"), e.get("surge")
        if sg and sg["ratio"]:
            parts.append(f"이 시기 하루 리뷰는 평소의 {sg['ratio']}배였고 {sg['days']}일 동안 이어졌습니다.")
        if rc and rc["recovered"]:
            via = f" 그 사이 패치 {', '.join(rc['via'])}가 있었습니다." if rc.get("via") else ""
            parts.append(f"{rc['days']}일 뒤 긍정률은 패치 전의 {rc['pct_of_before']}% 수준으로 돌아왔습니다.{via}")
        elif rc:
            parts.append("이후 구간에서 긍정률은 패치 전 수준으로 돌아오지 않았습니다.")
    title = by["period"]["value"] + " 반응 추세"
    return title, " ".join(parts)


def caveats(effects, has_channel):
    out = []
    if effects:
        out.append("패치 전후 수치는 같은 시기에 함께 관측된 값이며, 패치가 변화를 일으켰다는 뜻이 아닙니다.")
    if has_channel:
        out.append("첫 작성·수정은 리뷰가 올라온 경로이며 신규 유저·기존 유저 구분이 아닙니다.")
        out.append("이전 리뷰 상태를 저장하지 않아 수정이 긍정에서 부정으로 바뀐 것인지는 알 수 없습니다.")
    out.append(f"리뷰 {MIN_DAY_REVIEWS}건 미만인 날은 긍정률 비교에서 제외했습니다.")
    out.append("일별 집계는 리뷰 수정일 기준이라 과거 날짜의 값이 나중에 바뀔 수 있습니다.")
    return out


def _ask_local(msgs, timeout):
    r = httpx.post(OLLAMA_URL + "/api/chat",
                   json={"model": MODEL, "messages": msgs, "format": FORMAT, "stream": False, "think": False,
                         "options": {"temperature": 0.1, "num_predict": 400}, "keep_alive": "10m"},
                   timeout=timeout).json()
    return r["message"]["content"]


def _ask_gms(msgs, timeout=60):
    body = {"model": GMS_MODEL, "temperature": 0.1,
            "input": [{"role": m["role"], "content": m["content"]} for m in msgs],
            "text": {"format": {"type": "json_object"}}}
    req = urllib.request.Request(GMS_URL, data=json.dumps(body).encode("utf-8"), method="POST",
                                 headers={"Authorization": f"Bearer {GMS_KEY}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read())
    return "".join(c.get("text", "") for o in data.get("output", []) for c in o.get("content", [])
                   if c.get("type") == "output_text")


def _ask(msgs, use_gms):
    """(응답 문자열, 쓴 모델, gms 로 넘어갔는지). 로컬이 죽었거나 느리면 GMS 로 한 번 넘어가고 그 뒤로는 GMS 만 쓴다."""
    if not use_gms:
        try:
            return _ask_local(msgs, LOCAL_TIMEOUT), MODEL, False
        except (httpx.HTTPError, KeyError, ValueError, OSError):
            if not GMS_KEY:
                raise
    return _ask_gms(msgs), f"gms/{GMS_MODEL}", True


def _echoes_cards(summary, facts):
    """요약이 기간 총계·기간 긍정률을 그대로 옮겨 적었는지. 둘 다면 카드 낭독으로 본다.

    하나만 쓰는 것은 문맥상 필요할 수 있어 통과시킨다(예: 패치 구간 긍정률과 비교할 때).
    """
    by = {f["key"]: f["value"] for f in facts}
    hits = [label for key, label in (("total_reviews", "기간 리뷰 총계"), ("positive_pct", "기간 긍정률"))
            if key in by and by[key].rstrip("건%") and by[key].rstrip("건%") in summary]
    return " · ".join(hits) if len(hits) >= 2 else ""


def summarize_trend(game, facts, effects, retry=2, timeout=None):
    """반환: (title, summary, attempts, clean, model). title 이 빈 문자열이면 호출 쪽이 문장 틀 제목을 쓴다.
    model 은 실제로 답한 쪽(로컬 Qwen 또는 gms/gpt-4.1)."""
    lines = build_lines(game, facts, effects)
    msgs = [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": lines +
             "\n\n위 수치를 바탕으로 이 기간이 어떤 사례인지 요약하세요. 기간 리뷰 총계와 기간 긍정률은 화면에 이미 있으니 문장에 넣지 마세요."}]
    last = {"title": "", "summary": ""}
    use_gms, model = False, MODEL
    for attempt in range(1, retry + 2):
        content, model, use_gms = _ask(msgs, use_gms)
        try:
            last = json.loads(content)
        except Exception:  # noqa: BLE001
            last = {"title": "", "summary": content}
        s = tidy(last.get("summary", ""))
        title = tidy(last.get("title", ""))
        last["summary"], last["title"] = s, title
        both = s + title
        bad = len(BAD_CJK.findall(both))
        causal = CAUSAL.search(both) or CAUSAL_BY.search(both)
        advice = ADVICE.search(both)
        listy = bool(re.match(r"^\s*(\d+[.)]|[-•])", s))
        made_up = unknown_numbers(both, lines)
        plain = PLAIN_END.search(s)
        guess = SPECULATION.search(both)
        # 화면 카드에 이미 있는 총계를 그대로 옮겨 적으면 요약이 정보를 더하지 않는다(9/21)
        echo = _echoes_cards(s, facts)
        if (not bad and not causal and not advice and not listy and not made_up and not plain and not guess
                and not echo and len(s) >= 30):
            return (title if len(title) <= MAX_TITLE else ""), s, attempt, True, model
        why = []
        if bad:
            why.append(f"비한글 문자 {bad}개")
        if causal:
            why.append(f"인과 표현 '{causal.group(0)}'")
        if advice:
            why.append(f"조언·예측 표현 '{advice.group(0)}'")
        if listy:
            why.append("목록 형식")
        if made_up:
            why.append(f"입력에 없는 숫자 {', '.join(made_up[:3])}")
        if plain:
            why.append("반말체 종결")
        if guess:
            why.append(f"입력에 없는 배경 추측 '{guess.group(0)}'")
        if echo:
            why.append("화면 카드에 이미 있는 " + echo + " 되풀이")
        if len(s) < 30:
            why.append(f"길이 {len(s)}자")
        msgs += [{"role": "assistant", "content": json.dumps(last, ensure_ascii=False)},
                 {"role": "user", "content": "규칙 위반: " + ", ".join(why) +
                  ". 같은 내용을 한국어 문장 2~3개로 다시 쓰되 인과·조언 표현 없이, 주어진 수치만 그대로 쓰고 "
                  "모든 문장을 '습니다' 로 끝내서 같은 JSON 형식으로 답하라."}]
    # 끝까지 규칙을 못 지킨 문장은 쓰지 않는다. 호출 쪽이 문장 틀로 내려간다.
    return "", "", retry + 1, False, model
