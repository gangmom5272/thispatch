# -*- coding: utf-8 -*-
"""규칙 슬롯: 청크 문장 → (change_type, direction, target_type).

PoC 04·12·16(2026-09-07~10)과 노트북 08 B-2 의 규칙을 DB 코드값으로 매핑한 것.
3게임 검증(Valheim·TSW6·WARDOGS, AI 보조 채점): 방향 92~100%, 대상 50~62%.
대상 종류는 정확도가 낮으므로 검색 필터에는 쓰지 않고 표시·가점용으로만 둔다.

코드값은 backend V2__add_patch_analysis.sql 시드와 같다.
  change_type : add / remove / modify / fix / deprecate
  direction   : increase / decrease / none / not_applicable / unknown
  target_type : player / enemy / weapon / item / skill / map / system / other / unknown
"""
import re

RULE_VERSION = "rule-v2"   # v2(9/14): 5단어 미만·콜론으로 끝나는 소제목은 변경점 제외
MIN_WORDS = 5

# ---- 방향·변경 유형 단서 (문장 앞쪽 단서가 이긴다) ----
_DIR = [
    ("UP", r"\b(increased?|raised?|buff(ed)?|boost(ed)?|improved|higher|more|stronger)\b"),
    ("DOWN", r"\b(decreased?|reduced?|lowered?|nerf(ed)?|cut|less|fewer|weaker)\b"),
    ("ADD", r"\b(added|new|introduc(ed|ing)|now (has|have|available)|unlock(ed|s)?|implemented)\b"),
    ("REMOVE", r"\b(removed|deleted|disabled|no longer|deprecated)\b"),
    ("MODIFY", r"\b(fixed|fix|changed|adjusted|reworked|replaced|tweaked|resolved|corrected|updated|rebalanced|restored|reverted|renamed|addressed|polished|optimi[sz]ed)\b"),
]
_DIR_RE = [(d, re.compile(p, re.I)) for d, p in _DIR]
_FROMTO = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*%?\s*(?:->|→|to)\s*(\d[\d,]*(?:\.\d+)?)", re.I)
_FIX = re.compile(r"\b(fixed|fix|resolved|corrected|addressed)\b", re.I)
_DEPRECATE = re.compile(r"\bdeprecat(ed|ion)\b", re.I)

# ---- 대상 종류 단서 ----
_CUES = {
    "enemy": r"\b(enem(y|ies)|boss(es)?|elite|monster(s)?|hostile(s)?|foe(s)?|spawn(s|ed)?|creature(s)?|mob(s)?|raid(s|er|ers)?|zombie(s)?|infected)\b",
    "player": r"\b(player(s)?|character(s)?|hero|class|your|you (can|will|now)|playable|squad|teammate(s)?|ally|allies)\b",
    "weapon": r"\b(weapon(s)?|primary|secondary|sidearm|grenade(s)?|rifle(s)?|pistol(s)?|shotgun(s)?|smg|sniper|bow(s)?|sword(s)?|axe(s)?|spear(s)?|ammo|attachment(s)?|scope(s)?)\b",
    "item": r"\b(card(s)?|potion(s)?|item(s)?|armor|armour|perk(s)?|loadout|gear|equipment|shield(s)?|mead(s)?|food(s)?|arrow(s)?|locomotive(s)?|loco(s)?|train(s)?|unit(s)?|coach(es)?|wagon(s)?|rolling stock|route(s)?|scenario(s)?|timetable(s)?)\b",
    "skill": r"\b(skill(s)?|abilit(y|ies)|talent(s)?|spell(s)?|cooldown(s)?|passive(s)?|ultimate)\b",
    "map": r"\b(map(s)?|level(s)?|arena(s)?|zone(s)?|biome(s)?|dungeon(s)?|station(s)?|track(s)?|line|terrain|world gen)\b",
    "system": r"\b(menu(s)?|ui|hud|setting(s)?|option(s)?|server(s)?|matchmaking|crash(es|ed|ing)?|performance|fps|stutter(s|ing)?|audio|sound(s)?|graphic(s)?|texture(s)?|lighting|shadow(s)?|render(ing)?|difficulty|lobby|save(s)?|saving|controller|keybind(s)?|input|camera|localization|localisation|translation|achievement(s)?|steam|cloud|network|multiplayer|dedicated|host(ing)?|physics|animation(s)?|collision|editor|hdr|dlss|fsr|xbox|playstation|ps5|pc)\b",
    "other": r"\b(credit(s)?|gold|coin(s)?|currency|price(s)?|cost(s)?|reward(s)?|xp|experience|drop rate|loot|resource(s)?|ore|ingot(s)?)\b",
}
_CUE_RE = {k: re.compile(v, re.I) for k, v in _CUES.items()}


def _label(sentence):
    hits = [(m.start(), d) for d, rx in _DIR_RE for m in [rx.search(sentence)] if m]
    if hits:
        return min(hits)[1]
    m = _FROMTO.search(sentence)  # 동사 없는 'A to B' 숫자 변경
    if m:
        a, b = float(m.group(1).replace(",", "")), float(m.group(2).replace(",", ""))
        return "UP" if b > a else "DOWN" if b < a else "MODIFY"
    return None


def _cue(text):
    best = max(((k, len(rx.findall(text))) for k, rx in _CUE_RE.items()), key=lambda x: x[1])
    return best[0] if best[1] else None


def slots(text, context=""):
    """청크 문장 하나의 슬롯. 변경점이 아니면(단서 없음) None.

    반환: dict(change_type, direction, target_type)
    """
    # 소제목·한 단어 청크("Fixes", "Crashes", "Balance:")는 변경점이 아니다 (Top50 실측: 4단어 이하 16%가 fix 로 오탐)
    words = re.findall(r"[A-Za-z0-9][\w'%.-]*", text)
    if len(words) < MIN_WORDS or text.rstrip().endswith(":"):
        return None
    label = _label(text)
    if label is None:
        return None
    if label == "ADD":
        ct, d = "add", "none"
    elif label == "REMOVE":
        ct, d = ("deprecate" if _DEPRECATE.search(text) else "remove"), "none"
    elif label == "UP":
        ct, d = "modify", "increase"
    elif label == "DOWN":
        ct, d = "modify", "decrease"
    else:  # MODIFY
        if _FIX.search(text):
            ct, d = "fix", "not_applicable"
        else:
            ct, d = "modify", "unknown"
            m = _FROMTO.search(text)  # "Changed … from 79 to 70": 동사는 중립이라도 숫자가 방향을 말해 준다
            if m:
                a, b = float(m.group(1).replace(",", "")), float(m.group(2).replace(",", ""))
                d = "increase" if b > a else "decrease" if b < a else "none"
    # 대상: 문장 단서 → 없으면 소제목 단서 상속 → unknown
    tt = _cue(text) or (_cue(context) if context else None) or "unknown"
    return {"change_type": ct, "direction": d, "target_type": tt}
