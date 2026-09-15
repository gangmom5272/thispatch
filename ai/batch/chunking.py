# -*- coding: utf-8 -*-
"""공지 원문(Steam BBCode) → 청크(항목) 분리.

동료 steam_pipeline(2026-09-09) `sections.py`의 split_sections 와 `compact_qwen.py`의
source_items 를 그대로 옮긴 것. 원문 구간을 잘라서만 쓰고 다시 쓰지 않는다(근거 인용 보존).

청크 1개 = {"context": 소제목 경로(줄바꿈 연결), "text": 원문 항목(BBCode 포함), "start", "end"}
임베딩 입력 문자열은 build_input_text 가 만든다: "title: … | text: Context: …\\nChange: …"
"""
import re
from dataclasses import dataclass

SECTION_VERSION = "explicit-headings-1"
CHUNK_VERSION = "source-items-1"
MAX_CHUNK_CHARS = 1500  # 비정형 공지 폴백 분할 한도

HEADING_PATTERN = re.compile(
    r"(?P<bb>\[h(?P<bb_level>[1-6])\][\s\S]*?\[/h(?P=bb_level)\])"
    r"|(?P<html><h(?P<html_level>[1-6])\b[^>]*>[\s\S]*?</h(?P=html_level)\s*>)"
    r"|(?P<valve>\[p\]\s*\\?\[\s*[A-Z][A-Z0-9 /&_-]*\s*\]\s*\[/p\])"
    r"|(?P<triangle>\[p\]\s*▼[^\[\r\n]{1,100}\[/p\])"
    r"|(?P<markdown>^#{1,6}[ \t]+[^\r\n]+)"
    r"|(?P<plain>^[A-Za-z][A-Za-z0-9 /&()'’-]{1,79}:[ \t]*$)",
    re.MULTILINE,
)
TAG_PATTERN = re.compile(r"\[/?(?:p|list|h[1-6])\]|\[/?\*\]", re.IGNORECASE)
BB_ANY = re.compile(r"\[/?[a-zA-Z*][^\]]*\]")
PARA_SPLIT = re.compile(r"\[/p\]|\n\s*\n|\[br\]")  # 폴백 분할 경계: 문단 닫힘·빈 줄·줄바꿈 태그
SENT_SPLIT = re.compile(r"(?<=[.!?])\s+|\n|(?=[・▼•●■◆])|(?<=[。！？])")  # 문장 경계 + 일본어·기호 불릿(Palworld 등)


@dataclass(frozen=True)
class SourceSection:
    section_id: str
    start: int
    end: int
    heading_path: tuple
    text: str


def split_sections(source):
    """명시적 소제목만 구역을 만든다. 임의의 고유명사는 구역이 아니다."""
    headings = list(HEADING_PATTERN.finditer(source))
    if not headings:
        return [SourceSection("section-1", 0, len(source), (), source)]
    sections = []
    if headings[0].start() > 0:
        end = headings[0].start()
        sections.append(SourceSection("section-1", 0, end, (), source[:end]))
    heading_stack = []
    for index, heading in enumerate(headings):
        if heading.group("bb"):
            level = int(heading.group("bb_level"))
        elif heading.group("html"):
            level = int(heading.group("html_level"))
        elif heading.group("markdown"):
            level = len(heading.group().split(" ", 1)[0].split("\t", 1)[0])
        else:
            level = 1
        while heading_stack and heading_stack[-1][0] >= level:
            heading_stack.pop()
        heading_stack.append((level, heading.group()))
        end = headings[index + 1].start() if index + 1 < len(headings) else len(source)
        sections.append(SourceSection(
            f"section-{len(sections) + 1}", heading.start(), end,
            tuple(text for _, text in heading_stack), source[heading.start():end],
        ))
    return sections


def has_content(text):
    return bool(TAG_PATTERN.sub("", text).strip())


def balanced_blocks(text, tag):
    """최상위 [tag]…[/tag] 구간. 중첩 리스트 때문에 non-greedy 정규식은 안 된다."""
    pattern = re.compile(r"\[(/?)" + re.escape(tag) + r"\]", re.IGNORECASE)
    depth = 0
    blocks = []
    start = content_start = 0
    for token in pattern.finditer(text):
        if not token.group(1):
            if depth == 0:
                start, content_start = token.start(), token.end()
            depth += 1
        else:
            depth -= 1
            if depth < 0:
                raise ValueError("Unbalanced source markup")
            if depth == 0:
                blocks.append((start, content_start, token.start(), token.end()))
    if depth:
        raise ValueError("Unclosed source markup")
    return blocks


def source_items(source):
    """원문 구간을 정확히 보존하고, 직전 문맥(소제목·리스트 머리)만 scope 로 붙인다."""
    items = []

    def add_region(start, end, scopes):
        text = source[start:end]
        content = text
        for scope in scopes:
            content = content.replace(scope, "", 1)
        if has_content(content):
            items.append({"text": text, "context": "\n".join(scopes), "start": start, "end": end})

    def visit(start, end, scopes):
        text = source[start:end]
        cursor = 0
        for list_start, content_start, content_end, list_end in balanced_blocks(text, "list"):
            prefix = text[cursor:list_start]
            add_region(start + cursor, start + list_start, scopes)
            child_scopes = (*scopes, prefix) if prefix.strip() else scopes
            list_text = text[content_start:content_end]
            entries = balanced_blocks(list_text, "*")
            if not entries:
                visit(start + content_start, start + content_end, child_scopes)
            list_cursor = 0
            for item_start, _, _, item_end in entries:
                gap = list_text[list_cursor:item_start]
                if has_content(gap):
                    raise ValueError("Unparsed text between list items; refuse silent omission")
                visit(start + content_start + item_start, start + content_start + item_end, child_scopes)
                list_cursor = item_end
            if entries and has_content(list_text[list_cursor:]):
                raise ValueError("Unparsed list tail; refuse silent omission")
            cursor = list_end
        add_region(start + cursor, end, scopes)

    for section in split_sections(source):
        visit(section.start, section.end, section.heading_path)
    return items


def strip_bb(text):
    """표시·규칙용 평문. 임베딩 입력에도 이것을 쓴다(태그는 의미가 없다)."""
    return re.sub(r"\s+", " ", BB_ANY.sub(" ", text or "")).strip()


def chunk_notice(contents):
    """공지 본문 → 청크 목록. 파싱 실패(비정형 마크업)면 본문 전체를 청크 1개로 돌려준다."""
    try:
        items = source_items(contents or "")
    except ValueError:
        items = [{"text": contents or "", "context": "", "start": 0, "end": len(contents or "")}]
    # 마크업이 없거나 깨진 공지는 항목 1개가 본문 전체가 된다. 임베딩 입력 한도(약 2,000자)를 넘지 않게
    # 문단([p]·빈 줄) 단위로 나눠 준다. 정상 파싱된 항목은 건드리지 않는다.
    split = []
    for it in items:
        if len(it["text"]) <= MAX_CHUNK_CHARS:
            split.append(it); continue
        buf = ""
        for para in PARA_SPLIT.split(it["text"]):
            # 문단조차 없는 통짜 텍스트는 문장 경계(". ")로 한 번 더 자른다
            pieces = SENT_SPLIT.split(para) if len(para) > MAX_CHUNK_CHARS else [para]
            for piece in pieces:
                if len(buf) + len(piece) > MAX_CHUNK_CHARS and buf:
                    split.append({**it, "text": buf}); buf = ""
                buf += piece
        if buf.strip():
            split.append({**it, "text": buf})
    items = split
    out = []
    for it in items:
        plain = strip_bb(it["text"])
        if not plain:
            continue
        out.append({"context": strip_bb(it["context"]), "text": plain, "raw": it["text"]})
    return out


def split_sentences(text, min_len=12):
    """변경점 판정용 문장 분리. 짧은 조각(번호·기호)은 앞 문장에 붙인다. 문장이 하나면 원문 그대로."""
    parts = [p.strip() for p in SENT_SPLIT.split(text) if p and p.strip()]
    out = []
    for p in parts:
        if out and len(p) < min_len:
            out[-1] = f"{out[-1]} {p}"
        else:
            out.append(p)
    return out or [text]


def build_input_text(title, context, text):
    """동료 embedding_preview.py 와 같은 형식. 벡터 재현을 위해 이 문자열을 patch_chunk.text 에 그대로 저장한다."""
    content = f"Context: {context}\nChange: {text}" if context else text
    return f"title: {title} | text: {content}"
