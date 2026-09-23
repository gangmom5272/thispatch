# -*- coding: utf-8 -*-
"""Qwen 호출 워커 — 표준 라이브러리만 쓴다. 팀원 노트북에 이 파일과 qwen_prompt.py 만 복사해 돌릴 수 있다.

왜 따로 뗐나(9/18)
  청크가 176만 개라 한 대로는 730시간이다. 우선순위(인기 게임·최근 공지) 7.8만 개만 채워도 32시간이라
  같은 사양 노트북 여러 대로 나눈다. 그쪽에는 pandas·pyarrow·CUDA 파이썬 환경이 없어도 되게
  입력·출력을 JSONL 로 하고 의존을 Ollama + 파이썬 3.10 이상으로 줄였다.

입력  shard JSONL: 한 줄에 청크 하나 {"key": "gid:seq", "gid", "seq", "text"}. 우선순위 순서로 정렬돼 있다.
출력  {out}/facts-<shard이름>.jsonl: gid·seq·model·title·context·text·input_hash·changes 를 요청마다 이어 쓴다.
      중간에 죽어도 그때까지는 남고, 다시 실행하면 이미 쓴 청크는 건너뛴다.
      이 파일을 qwen_backfill.py --apply 에 넘기면 patch_change 를 덮어쓴다.

실행  python qwen_worker.py --shard shard-01.jsonl --out results [--max-seconds 21600]

실측(9/11, 4070 8GB, Q4_K_M): 1.2~1.7초/청크. 동시 요청은 이득 없음(GPU 한 장이 이미 포화).
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from qwen_prompt import FORMAT, MESSAGES, MODEL, MODEL_TAG, OPTIONS  # noqa: E402

CHAR_BUDGET = 2500   # 제목·본문·문맥 합으로 묶음을 나눈다. 긴 항목 하나는 분할하지 않는다.
MAX_ITEMS = 6        # 스키마 items maxItems=6 (qwen_prompt.FORMAT)
DEFAULT_URL = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")
VALUE_TRANSITION = re.compile(r"(?<![\w.,+−-])(\d[\d,]*(?:\.\d+)?)\s*%?\s*(?:->|→|\bto\b)\s*(\d[\d,]*(?:\.\d+)?)(?![\w.]|,\d)", re.I)


def split_text(text):
    """patch_chunk.text('title: T | text: Context: C\\nChange: X') → (title, context, change)"""
    title, separator, rest = text.partition(" | text: ")
    if not separator:
        raise ValueError("missing patch_chunk text separator")
    title = title.removeprefix("title: ")
    if rest.startswith("Context: "):
        ctx, separator, chg = rest[len("Context: "):].partition("\nChange: ")
        if not separator:
            raise ValueError("missing patch_chunk context separator")
        return title, ctx, chg
    return title, "", rest


def source_excerpt(value, source):
    """공백·같은 종류의 따옴표 차이만 허용하고 실제 원문 구간을 반환한다."""
    if not isinstance(value, str) or not value.strip():
        return None
    value = value.strip()
    parts = []
    for part in value.split():
        characters = []
        for character in part:
            if character in "'‘’":
                characters.append("['‘’]")
            elif character in '\"“”':
                characters.append('["“”]')
            else:
                characters.append(re.escape(character))
        parts.append("".join(characters))
    # 문자열 자체를 정규화하지 않아 인용문의 글자·위치를 그대로 보존한다.
    # 대시·마이너스·범위 기호는 수치 의미가 달라질 수 있어 통합하지 않는다.
    pattern = r"\s+".join(parts)
    # '5'가 '15'·'-5'의 일부, 'bow'가 'crossbow'의 일부인 경우는 근거가 아니다.
    if value[0] in "+-−0123456789":
        pattern = r"(?<![\w.,+−-])" + pattern
    elif value[0].isascii() and value[0].isalnum():
        pattern = r"(?<!\w)" + pattern
    # 한국어 조사가 붙은 '피해량을'에서도 '피해량'은 원문 구절이다.
    # 영문·숫자 경계를 한글에 적용하지 않으며, 형태소 판별까지 했다는 의미는 아니다.
    if value[-1].isascii() and value[-1].isalnum():
        pattern += r"(?!\w)"
    if value[-1].isdigit():
        pattern += r"(?![.,][0-9]|\s*%)"
    match = re.search(pattern, source)
    return match.group() if match else None


def validate_changes(changes, title, context, text, *, discard_ungrounded_attribute=False):
    """원문 연결을 검사한다. 재시도에서는 속성 이름만 비울 수 있으며 다른 검사는 유지한다."""
    if not isinstance(changes, list) or len(changes) > 8:
        raise ValueError("invalid changes list")
    schema = FORMAT["$defs"]["Fact"]
    validated = []
    for change in changes:
        if not isinstance(change, dict) or set(change) != set(schema["required"]):
            raise ValueError("missing or unexpected fact fields")
        for name in ("action", "target_type"):
            if change[name] not in schema["properties"][name]["enum"]:
                raise ValueError("invalid fact code")
        sentence = source_excerpt(change["source_sentence"], text)
        if sentence is None:
            raise ValueError("source_sentence must occur in this item's text")
        result = {**change, "source_sentence": sentence}
        target = change["target"]
        if not isinstance(target, str):
            raise ValueError("target must be a string")
        if not target.strip():
            if change["target_type"] != "unknown":
                raise ValueError("unstated target must have unknown type")
            result["target"] = ""
        else:
            grounded = source_excerpt(target, sentence) or source_excerpt(target, context) or source_excerpt(target, title)
            if grounded is None:
                raise ValueError("target must occur in this fact or its context")
            result["target"] = grounded
        for name in ("attribute", "values"):
            value = change[name]
            if value is not None:
                if not isinstance(value, str):
                    raise ValueError(name + " must be a string or null")
                grounded = source_excerpt(value, sentence)
                if grounded is None and not (name == "attribute" and discard_ungrounded_attribute):
                    raise ValueError(name + " must occur in the same source_sentence")
                result[name] = grounded
        if change["action"] in ("increase", "decrease"):
            for before, after in VALUE_TRANSITION.findall(result["values"] or ""):
                before_value, after_value = float(before.replace(",", "")), float(after.replace(",", ""))
                if ((change["action"] == "increase" and after_value <= before_value)
                        or (change["action"] == "decrease" and after_value >= before_value)):
                    raise ValueError("action contradicts the explicit numeric transition")
        conditions = change["conditions"]
        if not isinstance(conditions, list) or len(conditions) > 6:
            raise ValueError("invalid conditions")
        result["conditions"] = []
        for condition in conditions:
            grounded = source_excerpt(condition, sentence) or source_excerpt(condition, context)
            if grounded is None:
                raise ValueError("condition must occur in this fact or its context")
            if grounded not in result["conditions"]:
                result["conditions"].append(grounded)
        validated.append(result)
    return validated


def input_hash(title, context, text):
    # 같은 본문이라도 소제목·제목이 다르면 생략된 대상과 조건이 달라질 수 있다.
    source = json.dumps([MODEL_TAG, title, context, text], ensure_ascii=False)
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def call_qwen(url, items, timeout=300, *, discard_ungrounded_attribute=False):
    body = {"model": MODEL, "messages": MESSAGES + [{"role": "user", "content": json.dumps(items, ensure_ascii=False)}],
            "format": FORMAT, "options": OPTIONS, "stream": False, "think": False, "keep_alive": "10m"}
    req = urllib.request.Request(url + "/api/chat", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode("utf-8"))
    out = json.loads(resp["message"]["content"])
    expected = {item["id"]: item for item in items}
    returned = out.get("items")
    if not isinstance(returned, list) or len(returned) != len(expected):
        raise ValueError("Qwen must return every requested item exactly once")
    result = {}
    for item in returned:
        item_id = item.get("id")
        if item_id not in expected or item_id in result:
            raise ValueError("unexpected or duplicate item id")
        source = expected[item_id]
        result[item_id] = validate_changes(item.get("changes"), source.get("title", ""), source["context"], source["text"],
                                          discard_ungrounded_attribute=discard_ungrounded_attribute)
    return result


def _done_keys(path):
    keys = {}
    if path.exists():
        with path.open(encoding="utf-8") as source:
            for line in source:
                if line.strip():
                    d = json.loads(line)
                    if d.get("model") == MODEL_TAG and d.get("input_hash"):
                        keys[f"{d['gid']}:{d['seq']}"] = d["input_hash"]
    return keys


def run(shard, out_dir, url=DEFAULT_URL, max_seconds=6 * 3600, limit=0, log=print):
    """shard 의 청크를 Qwen 에 보내고 결과를 out_dir/facts-<shard>.jsonl 에 이어 쓴다. 처리 건수를 돌려준다."""
    shard, out_dir = Path(shard), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"facts-{shard.stem}.jsonl"
    done = _done_keys(out)

    todo = []
    with shard.open(encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            d = json.loads(line)
            title, ctx, body = split_text(d["text"])
            d["title"], d["context"], d["body"] = title, ctx, body
            d["h"] = input_hash(title, ctx, body)
            if done.get(d["key"]) == d["h"]:
                continue
            todo.append(d)
    if limit:
        todo = todo[:limit]
    log(f"shard={shard.name} chunks={len(todo)} (이미 끝난 {len(done)}) → {out}")
    if not todo:
        return 0

    # 모델에 전달하는 제목·문맥·본문이 모두 같을 때만 결과를 재사용한다.
    by_h = {}
    for d in todo:
        by_h.setdefault(d["h"], []).append(d)
    uniq = [v[0] for v in by_h.values()]
    batches, cur, cur_len = [], [], 0
    for r in uniq:
        n = len(r["title"]) + len(r["body"]) + len(r["context"])
        if cur and (cur_len + n > CHAR_BUDGET or len(cur) >= MAX_ITEMS):
            batches.append(cur); cur, cur_len = [], 0
        cur.append(r); cur_len += n
    if cur:
        batches.append(cur)
    log(f"unique={len(uniq)} requests={len(batches)} (avg {len(uniq)/max(len(batches),1):.1f} items)")

    t0, calls, fails, written, seen = time.time(), 0, 0, 0, 0
    with out.open("a", encoding="utf-8") as f:
        for batch in batches:
            if time.time() - t0 > max_seconds:
                log("시간 예산 끝. 여기까지 저장됨 — 다시 실행하면 이어서 한다"); break
            items = [{"id": r["h"], "title": r["title"], "context": r["context"], "text": r["body"]} for r in batch]
            try:
                got = call_qwen(url, items); calls += 1
            except Exception as e:  # noqa: BLE001
                # 묶음 응답이 잘리거나 깨지면 한 개씩 다시 보낸다. 그래도 실패하면 그 청크만 건너뜀
                got = {}
                for it in items:
                    try:
                        # 별도 요청으로 게임 간 문맥을 분리한다. 속성 이름의 의역만 비우며,
                        # 다른 게임의 문장이나 잘못된 수치가 있으면 이 요청도 실패한다.
                        got.update(call_qwen(url, [it], discard_ungrounded_attribute=True)); calls += 1
                    except Exception as e1:  # noqa: BLE001
                        fails += 1; log(f"qwen fail(single) {type(e1).__name__} {str(e1)[:80]} | {it['text'][:60]}")
            for r in batch:
                seen += 1
                if r["h"] not in got:
                    continue
                for d in by_h[r["h"]]:          # 같은 문장의 청크 전부에 결과 복사
                    f.write(json.dumps({"gid": d["gid"], "seq": int(d["seq"]), "model": MODEL_TAG,
                                        "title": d["title"], "context": d["context"], "input_hash": d["h"],
                                        "text": d["body"], "changes": got[r["h"]]}, ensure_ascii=False) + "\n")
                    written += 1
            f.flush()
            if calls % 50 == 0:
                el = time.time() - t0
                log(f"  {seen}/{len(uniq)} unique · {el/60:.0f}분 · {el/max(seen,1):.2f}초/unique · 청크 {written}")
    el = time.time() - t0
    log(f"끝: 청크 {written} · 요청 {calls} · 실패 {fails} · {el/60:.0f}분 ({el/max(written,1):.2f}초/청크)")
    return written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shard", required=True, help="shard-0N.jsonl")
    ap.add_argument("--out", default="results", help="결과 폴더")
    ap.add_argument("--ollama", default=DEFAULT_URL)
    ap.add_argument("--max-seconds", type=int, default=6 * 3600, help="시간 예산. 넘으면 저장 후 종료")
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    run(a.shard, a.out, a.ollama, a.max_seconds, a.limit)


if __name__ == "__main__":
    main()
