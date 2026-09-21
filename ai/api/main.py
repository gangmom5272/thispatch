# -*- coding: utf-8 -*-
"""AI 요청 API (FastAPI). 백엔드(Spring)가 사용자 요청 시 호출한다. AI 서버는 DB 를 보지 않는다.

  GET  /health
  POST /plan/structure     화면 03  기획안 텍스트 → Qwen 1회 → 변경점 슬롯 + 재진술
  POST /plan/restate       화면 03b 수정한 슬롯 → 재진술 (문장 틀)
  POST /embed/query        화면 04  확인된 변경점 문장 → 질의 벡터 512 (pgvector 검색용)
  POST /cases/cards        화면 04  후보 카드 1줄 공통/차이 + 결과군 패턴 (문장 틀)
  POST /cases/compare      화면 05  공통점 3·차이점 3 (문장 틀 + Qwen 해석 옵션)
  POST /reviews/summarize  화면 02  구간·언어별 AI 대표 반응 요약 (Qwen 1회)
  POST /trends/summarize   화면 01  반응 추세 통계 요약 — 일별 집계·패치 시점 (문장 틀 + Qwen 옵션)

검색 자체(pgvector 상위 30 → 슬롯 필터)는 백엔드가 SQL 로 한다. 여기서는 호출하지 않는다.
질의 벡터는 배치 임베딩(embed_chunks.py)과 같은 모델·같은 실행기·같은 입력 형식으로 만든다
(HF bf16, truncate 512, 프롬프트 없음, "title: … | text: …" 틀). 다른 실행기(Ollama)와 섞지 않는다.

실행  uvicorn main:app --host 0.0.0.0 --port 8100   (ai/api 에서)
환경  OLLAMA_URL (기본 http://127.0.0.1:11434), EMBED_MODEL_ID
"""
import json
import sys
import time
from pathlib import Path

import httpx
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, model_validator

sys.path.insert(0, str(Path(__file__).parent.parent / "batch"))
from chunking import build_input_text, split_sentences  # noqa: E402
from common import EMBED_DIM, EMBED_MODEL_ID, EMBED_MODEL_TAG, OLLAMA_URL  # noqa: E402
from qwen_backfill import to_codes  # noqa: E402
from qwen_prompt import FORMAT, MESSAGES, MODEL, OPTIONS, PROMPT_VERSION  # noqa: E402
from rules import slots  # noqa: E402

app = FastAPI(title="This Patch AI", version="0.1")
_ready = {"embedder": False, "qwen": False, "error": None}   # 기동 워밍업 상태. /health 가 돌려준다


@app.on_event("startup")
def warmup():
    """모델을 미리 올린다. 첫 요청이 35초(임베딩 적재)·10초(Qwen 적재)를 기다리지 않게.
    실패해도 서버는 뜬다 - /health 의 ready 가 false 로 남고, 첫 요청 때 다시 시도한다."""
    import threading

    def run():
        try:
            embedder().encode(["warmup"], normalize_embeddings=True)
            _ready["embedder"] = True
            httpx.post(OLLAMA_URL + "/api/chat", json={"model": MODEL, "messages": [{"role": "user", "content": "ok"}],
                                                       "stream": False, "options": {"num_predict": 1}, "keep_alive": "2h"},
                       timeout=180).raise_for_status()
            _ready["qwen"] = True
        except Exception as e:  # noqa: BLE001
            _ready["error"] = f"{type(e).__name__}: {str(e)[:120]}"

    threading.Thread(target=run, daemon=True, name="warmup").start()
_embedder = None


# ---------- 오류 응답: 백엔드 공통 계약(backend/docs/api/conventions.md#error-response)과 같은 모양 ----------
# code, message, responsedAt(Asia/Seoul, yyyy-MM-dd HH:mm:ss), 필드 오류가 있을 때만 errors. data·success 없음.
# 백엔드가 우리 응답을 그대로 프론트에 넘길 수 있게 맞춘다. 예외 상세는 서버 로그에만 남긴다.
from datetime import datetime  # noqa: E402
from zoneinfo import ZoneInfo  # noqa: E402

from fastapi import Request  # noqa: E402
from fastapi.exceptions import RequestValidationError  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from starlette.exceptions import HTTPException as StarletteHTTPException  # noqa: E402

KST = ZoneInfo("Asia/Seoul")


def error_body(code, message, errors=None):
    body = {"code": code, "message": message, "responsedAt": datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S")}
    if errors:
        body["errors"] = errors
    return body


@app.exception_handler(RequestValidationError)
async def on_validation(_: Request, exc: RequestValidationError):
    # 잘못된 JSON·본문 누락은 INVALID_REQUEST, 필드 검증 실패는 VALIDATION_FAILED + errors (백엔드 공통 코드 표와 동일)
    # 어느 쪽이든 서버 로그에 이유와 본문 앞부분을 남긴다. 9/21 연동 중 400 만 보이고 이유를 몰라 헤맸다.
    import logging
    log = logging.getLogger("uvicorn.error")
    try:
        raw = (await _.body())[:300].decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        raw = "?"
    if any(e.get("type") == "json_invalid" or (e.get("type") == "missing" and tuple(e.get("loc", ())) == ("body",)) for e in exc.errors()):
        log.warning("400 INVALID_REQUEST %s content-type=%s body=%r", _.url.path, _.headers.get("content-type"), raw)
        return JSONResponse(status_code=400, content=error_body("INVALID_REQUEST", "올바르지 않은 요청입니다."))
    errors = [{"field": ".".join(str(p) for p in e["loc"] if p != "body"), "message": e["msg"]} for e in exc.errors()]
    log.warning("400 VALIDATION_FAILED %s %s | body=%r", _.url.path,
                "; ".join(f"{e['field']}: {e['message']}" for e in errors)[:400], raw[:200])
    return JSONResponse(status_code=400, content=error_body("VALIDATION_FAILED", "입력값을 확인해주세요.", errors))


@app.exception_handler(StarletteHTTPException)
async def on_http(_: Request, exc: StarletteHTTPException):
    code = {502: "AI_UPSTREAM_ERROR", 503: "AI_UNAVAILABLE", 404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED",
            415: "UNSUPPORTED_MEDIA_TYPE"}.get(exc.status_code, "HTTP_ERROR")
    return JSONResponse(status_code=exc.status_code, content=error_body(code, "요청을 처리할 수 없습니다." if exc.status_code in (404, 405, 415) else (str(exc.detail) or "요청을 처리할 수 없습니다.")))


@app.exception_handler(Exception)
async def on_error(_: Request, exc: Exception):
    import logging
    logging.getLogger("ai").exception("unexpected: %s", type(exc).__name__)
    return JSONResponse(status_code=500, content=error_body("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."))


def embedder():
    global _embedder
    if _embedder is None:
        import torch
        from sentence_transformers import SentenceTransformer
        device = "cuda" if torch.cuda.is_available() else "cpu"
        dtype = torch.bfloat16 if device == "cuda" else torch.float32
        _embedder = SentenceTransformer(EMBED_MODEL_ID, device=device, model_kwargs={"torch_dtype": dtype},
                                        truncate_dim=EMBED_DIM)
    return _embedder


# ---------- 스키마 ----------
class PlanIn(BaseModel):
    title: str = Field("", description="기획안 제목(선택)")
    text: str = Field(..., min_length=5, max_length=6000, description="기획안 자연어")


class Change(BaseModel):
    change_seq: int
    change_type: str
    direction: str
    target_type: str
    target: str | None
    attribute: str | None
    values: str | None
    conditions: list[str]
    restatement: str          # 화면 1 재진술: 사람이 읽고 고칠 문장
    source_sentence: str


class PlanOut(BaseModel):
    changes: list[Change]
    model: str
    prompt_version: str
    elapsed_ms: int


class QueryIn(BaseModel):
    title: str = ""
    sentences: list[str] = Field(..., min_length=1, max_length=20, description="사용자가 확인한 변경점 문장들")


class QueryOut(BaseModel):
    embeddings: list[list[float]]
    dim: int
    model: str


# ---------- 엔드포인트 ----------
@app.get("/health")
def health():
    try:
        ok = httpx.get(OLLAMA_URL + "/api/version", timeout=3).status_code == 200
    except Exception:  # noqa: BLE001
        ok = False
    ready = ok and _ready["embedder"] and _ready["qwen"]
    return {"status": "ready" if ready else "warming", "ready": ready, "ollama": ok, "embedder_loaded": _ready["embedder"],
            "qwen_loaded": _ready["qwen"], "warmup_error": _ready["error"], "embed_model": EMBED_MODEL_TAG, "llm_model": MODEL}


def restate(c, codes):
    """슬롯 → 한 줄 재진술. 문장 틀만 쓰고 LLM 을 다시 부르지 않는다."""
    ct, d = codes
    tgt = c.get("target") or "대상 미확인"
    attr = f"의 {c['attribute']}" if c.get("attribute") else ""
    if ct == "modify":
        verb = {"increase": "증가", "decrease": "감소"}.get(d, "변경")
    else:
        verb = {"add": "추가", "remove": "제거", "deprecate": "지원 중단", "fix": "수정"}[ct]
    val = f" ({c['values']})" if c.get("values") else ""
    cond = f" — 조건: {', '.join(c['conditions'])}" if c.get("conditions") else ""
    return f"{tgt}{attr} {verb}{val}{cond}"


@app.post("/plan/structure", response_model=PlanOut)
def plan_structure(p: PlanIn):
    t0 = time.time()
    sents = split_sentences(p.text)
    items = [{"id": f"p{i}", "context": p.title, "text": s} for i, s in enumerate(sents, 1)]
    body = {"model": MODEL, "messages": MESSAGES + [{"role": "user", "content": json.dumps(items, ensure_ascii=False)}],
            "format": FORMAT, "options": OPTIONS, "stream": False, "think": False, "keep_alive": "10m"}
    try:
        r = httpx.post(OLLAMA_URL + "/api/chat", json=body, timeout=180).json()
        got = {it["id"]: it.get("changes", []) for it in json.loads(r["message"]["content"]).get("items", [])}
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"Qwen 호출 실패: {type(e).__name__}") from e

    out, n = [], 0
    for i, s in enumerate(sents, 1):
        changes = got.get(f"p{i}", [])
        if not changes:  # Qwen 이 비우면 규칙으로 최소 슬롯
            rs = slots(s, p.title)
            if rs:
                changes = [{"action": {"add": "add", "remove": "remove", "fix": "fix", "deprecate": "deprecate",
                                       "modify": {"increase": "increase", "decrease": "decrease"}.get(rs["direction"], "change")}[rs["change_type"]],
                            "target": None, "target_type": rs["target_type"], "attribute": None, "values": None, "conditions": []}]
        for c in changes:
            codes = to_codes(c)
            if not codes:
                continue
            n += 1
            out.append(Change(change_seq=n, change_type=codes[0], direction=codes[1],
                              target_type=c.get("target_type") or "unknown", target=c.get("target"),
                              attribute=c.get("attribute"), values=c.get("values"), conditions=c.get("conditions") or [],
                              restatement=restate(c, codes), source_sentence=s))
    return PlanOut(changes=out, model=MODEL, prompt_version=PROMPT_VERSION, elapsed_ms=int((time.time() - t0) * 1000))


@app.post("/embed/query", response_model=QueryOut)
def embed_query(q: QueryIn):
    texts = [build_input_text(q.title, "", s) for s in q.sentences]
    vecs = embedder().encode(texts, batch_size=16, normalize_embeddings=True, show_progress_bar=False)
    vecs = np.asarray(vecs, dtype=np.float32)
    return QueryOut(embeddings=[v.tolist() for v in vecs], dim=int(vecs.shape[1]), model=EMBED_MODEL_TAG)


# ======================================================================
# 화면 03b · 04 · 05 — 문장 틀 + Qwen 해석(선택). ERD 에 없는 값은 evidence_quote 원문으로 대신한다.
# AI 서버는 DB 를 보지 않는다. 백엔드가 사례 데이터를 본문에 담아 보낸다(캐시도 백엔드).
# ======================================================================
from compare import card_lines, group_patterns, qwen_compare, template_compare  # noqa: E402


class PlanChange(BaseModel):
    change_type: str
    direction: str
    target_type: str = "unknown"
    target: str | None = None
    attribute: str | None = None
    values: str | None = None
    conditions: list[str] = []
    source_sentence: str = ""


class PlanSlots(BaseModel):
    changes: list[PlanChange] = Field(..., min_length=1, max_length=20)
    genres: list[str] = []


class CaseChange(BaseModel):
    change_type: str
    direction: str
    target_type: str = "unknown"
    evidence_quote: str = ""


class CaseStats(BaseModel):
    before_positive_pct: float | None = None
    after_positive_pct: float | None = None
    delta_pct: float | None = None
    review_count: int | None = None


class CaseCycle(BaseModel):
    usual_days: float | None = None
    next_patch_days: float | None = None
    ratio: float | None = None


class CaseIn(BaseModel):
    gid: str
    game: str = ""
    version: str = ""
    genres: list[str] = []
    changes: list[CaseChange] = Field(..., min_length=1, max_length=200)
    stats: CaseStats | None = None
    cycle: CaseCycle | None = None


class CompareIn(BaseModel):
    plan: PlanSlots
    case: CaseIn
    use_llm: bool = Field(True, description="false 면 문장 틀만(밀리초). true 면 Qwen 해석을 덧붙임(5~10초)")


class Point(BaseModel):
    title: str
    body: str
    source: str  # template | llm


class CompareOut(BaseModel):
    gid: str
    common: list[Point]
    differences: list[Point]
    llm_used: bool
    elapsed_ms: int


@app.post("/cases/compare", response_model=CompareOut)
def cases_compare(q: CompareIn):
    """화면 05 공통점·차이점. 문장 틀 결과를 먼저 만들고, use_llm 이면 Qwen 해석으로 교체(실패 시 문장 틀 유지)."""
    t0 = time.time()
    plan, case = q.plan.model_dump(), q.case.model_dump()
    tpl = template_compare(plan, case)
    common = [Point(**x, source="template") for x in tpl["common"]]
    diffs = [Point(**x, source="template") for x in tpl["differences"]]
    used = False
    if q.use_llm:
        try:
            llm = qwen_compare(plan, case)
            # Qwen 이 '…다릅니다' 를 공통점에 넣는 일이 있어 제목으로 한 번 재배치한다(9/14 실측)
            allp = llm["common"] + llm["differences"]
            llm = {"common": [x for x in allp if "다릅" not in x["title"]][:3],
                   "differences": [x for x in allp if "다릅" in x["title"]][:3]}
            if llm["common"] or llm["differences"]:
                common = [Point(**x, source="llm") for x in llm["common"]] or common
                diffs = [Point(**x, source="llm") for x in llm["differences"]] or diffs
                used = True
        except Exception as e:  # noqa: BLE001
            import logging
            logging.getLogger("ai").warning("qwen compare failed, template kept: %s", type(e).__name__)
    return CompareOut(gid=q.case.gid, common=common[:3], differences=diffs[:3], llm_used=used,
                      elapsed_ms=int((time.time() - t0) * 1000))


class CardsIn(BaseModel):
    plan: PlanSlots
    cases: list[CaseIn] = Field(..., min_length=1, max_length=60)
    min_group_n: int = Field(20, description="결과군 패턴 요약 최소 표본. 디자인 06 기준 20")


class CardLine(BaseModel):
    gid: str
    common: str
    difference: str


class CardsOut(BaseModel):
    cards: list[CardLine]
    patterns: list[str]
    note: str | None


@app.post("/cases/cards", response_model=CardsOut)
def cases_cards(q: CardsIn):
    """화면 04 카드 1줄 공통/차이 + 결과군 패턴 3줄. 전부 문장 틀(LLM 없음, 밀리초). 한 결과군씩 호출."""
    plan = q.plan.model_dump()
    cases = [c.model_dump() for c in q.cases]
    cards = [CardLine(gid=c["gid"], **card_lines(plan, c)) for c in cases]
    g = group_patterns(cases, q.min_group_n)
    return CardsOut(cards=cards, patterns=g["patterns"], note=g["note"])


class RestateIn(BaseModel):
    changes: list[PlanChange] = Field(..., min_length=1, max_length=20)


class RestateOut(BaseModel):
    restatements: list[str]
    summary: str


@app.post("/plan/restate", response_model=RestateOut)
def plan_restate(q: RestateIn):
    """화면 03b 슬롯 직접 수정 → 재진술 갱신. 문장 틀만(LLM 없음)."""
    from qwen_backfill import to_codes  # 이미 import 됨. 명시적으로
    lines = []
    for ch in q.changes:
        c = ch.model_dump()
        codes = (c["change_type"], c["direction"])
        lines.append(restate({"target": c["target"], "attribute": c["attribute"], "values": c["values"],
                              "conditions": c["conditions"]}, codes))
    tts = sorted({ko_tt for ko_tt in (c.target_type for c in q.changes) if ko_tt != "unknown"})
    conds = sorted({x for c in q.changes for x in c.conditions})
    summary = f"{', '.join(conds) + '에서 ' if conds else ''}{len(q.changes)}개 변경점" + \
              (f" ({', '.join(tts)} 대상)" if tts else "") + "으로 구성된 변경입니다. " + " / ".join(lines)
    return RestateOut(restatements=lines, summary=summary)


# ======================================================================
# 화면 02 · 02b · 02d — AI 대표 반응 요약 (요청 시 Qwen 1회, 저장 없음. 캐시 키 (appid, scope_type, scope_key) 는 백엔드)
# ======================================================================
from summarize import summarize  # noqa: E402


class ReviewIn(BaseModel):
    review_id: int
    review_text: str = Field(..., min_length=1, max_length=4000)
    voted_up: bool | None = None
    votes_up: int | None = None
    language_code: str | None = None
    playtime_at_review: int | None = Field(None, description="분")


class SummarizeIn(BaseModel):
    appid: int
    game: str = ""
    scope_type: str = Field(..., pattern="^(BAND|LANGUAGE|ALL)$")
    scope_key: str = Field("", description="BAND: 1~4, LANGUAGE: 언어 코드, ALL: 빈 문자열")
    reviews: list[ReviewIn] = Field(..., min_length=3, max_length=40, description="백엔드가 votes_up DESC 로 고른 상위 N건")


class SummarizeOut(BaseModel):
    appid: int
    scope_type: str
    scope_key: str
    title: str
    summary: str
    phrases: list[str]
    evidence_ids: list[int]
    review_count: int
    model: str
    attempts: int
    clean: bool
    elapsed_ms: int


@app.post("/reviews/summarize", response_model=SummarizeOut)
def reviews_summarize(q: SummarizeIn):
    t0 = time.time()
    label = {"BAND": f"플레이타임 {q.scope_key}구간", "LANGUAGE": f"언어 {q.scope_key}", "ALL": "전체"}[q.scope_type]
    try:
        out, attempts, clean, used_model = summarize(q.game or str(q.appid), label, [r.model_dump() for r in q.reviews])
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"Qwen 호출 실패: {type(e).__name__}") from e
    return SummarizeOut(appid=q.appid, scope_type=q.scope_type, scope_key=q.scope_key, title=out.get("title", ""),
                        summary=out.get("summary", ""), phrases=out.get("phrases", []), evidence_ids=out.get("evidence_ids", []),
                        review_count=len(q.reviews), model=used_model, attempts=attempts, clean=clean,
                        elapsed_ms=int((time.time() - t0) * 1000))


# ======================================================================
# 화면 01 — 반응 추세 통계 요약 (일별 집계·패치 시점. 문장 틀 + Qwen 옵션)
# ======================================================================
from trends import TREND_VERSION, caveats, compute_facts, summarize_trend, template_summary  # noqa: E402


class DayStat(BaseModel):
    date: str = Field(..., pattern=r"^\d{4}-\d{2}-\d{2}$", description="집계 기준일(KST, 리뷰 수정일)")
    reviews: int = Field(..., ge=0, description="그날 리뷰 수")
    positive: int = Field(..., ge=0, description="그중 긍정 수")
    first_reviews: int | None = Field(None, ge=0, description="첫 작성분(created == updated). 없으면 채널 문장 생략")
    first_positive: int | None = Field(None, ge=0)
    edited_reviews: int | None = Field(None, ge=0, description="수정분(created < updated)")
    edited_positive: int | None = Field(None, ge=0)

    @model_validator(mode="after")
    def _check(self):
        # 긍정이 전체보다 많으면 긍정률이 100% 를 넘는다. 조회 쿼리가 어긋난 것이므로 화면에 내보내지 않고 막는다.
        for whole, part, name in ((self.reviews, self.positive, "positive"),
                                  (self.first_reviews, self.first_positive, "first_positive"),
                                  (self.edited_reviews, self.edited_positive, "edited_positive")):
            if whole is not None and part is not None and part > whole:
                raise ValueError(f"{self.date}: {name}({part}) 가 전체({whole}) 보다 큽니다")
        return self


class PatchPoint(BaseModel):
    date: str = Field(..., pattern=r"^\d{4}-\d{2}-\d{2}$", description="패치 게시일(KST)")
    title: str = ""
    version: str | None = None
    gid: str | None = None


class TrendIn(BaseModel):
    appid: int
    game: str = ""
    daily: list[DayStat] = Field(..., max_length=400,
                                 description="선택 기간의 일별 집계. 날짜 순서는 상관없다. "
                                             "빈 배열이면 '리뷰 없음' 결과를 200 으로 돌려준다")
    patches: list[PatchPoint] = Field(default=[], max_length=50, description="기간 안 패치 시점")
    window_days: int = Field(7, ge=1, le=30, description="패치 전후 비교 창")
    use_llm: bool = Field(True, description="false 면 문장 틀로만(밀리초)")


class Fact(BaseModel):
    key: str
    label: str
    value: str
    detail: str | None = None
    delta_pct: float | None = None


class PatchEffect(BaseModel):
    title: str
    date: str
    version: str | None = None
    gid: str | None = None
    before: dict
    after: dict
    delta_pct: float | None = None
    window_days: int
    note: str | None = None


class TrendOut(BaseModel):
    appid: int
    title: str
    summary: str
    facts: list[Fact]
    patch_effects: list[PatchEffect]
    caveats: list[str]
    day_count: int
    used_llm: bool
    model: str | None = None
    rule_version: str = TREND_VERSION
    attempts: int = 0
    clean: bool = True
    elapsed_ms: int


@app.post("/trends/summarize", response_model=TrendOut)
def trends_summarize(q: TrendIn):
    """화면 01 반응 추세. 수치 계산은 여기서 하고 Qwen 은 문장만 쓴다.

    Qwen 이 규칙(인과·조언 금지)을 못 지키거나 죽어 있으면 문장 틀 결과로 내려간다 — 화면이 비지 않게.
    """
    t0 = time.time()
    daily = [d.model_dump() for d in q.daily]
    if not daily:
        # 기간에 리뷰가 없는 것은 오류가 아니다. 화면이 에러를 받지 않도록 200 으로 돌려준다.
        return TrendOut(appid=q.appid, title=f"{q.game or q.appid} 반응 추세",
                        summary="선택한 기간에는 리뷰가 없습니다.",
                        facts=[{"key": "total_reviews", "label": "기간 리뷰", "value": "0건"}],
                        patch_effects=[], caveats=["선택한 기간에 집계된 리뷰가 없습니다."],
                        day_count=0, used_llm=False, model=None, attempts=0, clean=True,
                        elapsed_ms=int((time.time() - t0) * 1000))
    facts, effects = compute_facts(daily, [p.model_dump() for p in q.patches], q.window_days)
    title, summary = template_summary(facts, effects)
    used_llm, attempts, clean, model = False, 0, True, None
    if q.use_llm:
        try:
            # 검사(인과·조언·추측·지어낸 숫자·반말체)를 통과한 문장만 쓴다. 못 지키면 s 가 비어 문장 틀이 남는다.
            # 로컬 Qwen 이 죽었거나 45초 안에 못 답하면 trends 가 GMS(gpt-4.1) 로 넘어간다. model 에 실제 답한 쪽이 온다.
            t, s, attempts, clean, used_model = summarize_trend(q.game or str(q.appid), facts, effects)
            if s:
                title, summary, used_llm, model = (t or title), s, True, used_model
        except Exception:  # noqa: BLE001
            used_llm, clean = False, False      # 로컬·GMS 둘 다 실패(또는 GMS 키 없음). 문장 틀로 응답하고 clean=false 로 알린다
    has_channel = any(f["key"] == "channel" for f in facts)
    return TrendOut(appid=q.appid, title=title, summary=summary, facts=facts, patch_effects=effects,
                    caveats=caveats(effects, has_channel), day_count=len(daily), used_llm=used_llm,
                    model=model, attempts=attempts, clean=clean, elapsed_ms=int((time.time() - t0) * 1000))
