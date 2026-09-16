import { useLayoutEffect, useRef, useState } from "react"

interface ReviewBodyExpandableProps {
  /** 표시할 리뷰 본문(원문 또는 번역) */
  text: string
}

/**
 * 기본 2줄 클램프 + 더보기 행을 고정해 접힌 상태 박스 높이를 맞춘다.
 * 넘치면 「…더보기」로 펼치고, 펼친 뒤 「접기」로 되돌린다.
 * 본문 문자열이 바뀌면 부모가 key로 리마운트해 접힌 상태로 초기화한다.
 */
export function ReviewBodyExpandable({ text }: ReviewBodyExpandableProps) {
  // expanded: 해당 카드만 전체 본문 표시
  const [expanded, setExpanded] = useState(false)
  // needsClamp: 2줄을 넘는 긴 본문인지(접힌 상태에서 측정)
  const [needsClamp, setNeedsClamp] = useState(false)
  const bodyRef = useRef<HTMLParagraphElement>(null)

  useLayoutEffect(() => {
    const el = bodyRef.current
    if (!el || expanded) return
    // scrollHeight가 clientHeight보다 크면 2줄을 넘는 것
    setNeedsClamp(el.scrollHeight > el.clientHeight + 1)
  }, [text, expanded])

  return (
    <div className="mt-sb-3 flex flex-col gap-sb-2">
      <p
        ref={bodyRef}
        className={
          expanded
            ? "text-sb-body leading-relaxed text-sb-ink"
            : // min-h: leading-relaxed 2줄 높이 고정 → 짧은 본문도 박스 크기 동일
              "line-clamp-2 min-h-[calc(1.625em*2)] text-sb-body leading-relaxed text-sb-ink"
        }
      >
        {text}
      </p>
      {/* h-[1.5em]: caption 한 줄 + 더보기/접기 자리 예약(없으면 빈 칸) */}
      <div className="flex h-[1.5em] items-center">
        {!expanded && needsClamp ? (
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className="cursor-pointer text-sb-caption text-sb-primary hover:text-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            …더보기
          </button>
        ) : null}
        {expanded && needsClamp ? (
          <button
            type="button"
            onClick={() => setExpanded(false)}
            className="cursor-pointer text-sb-caption text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            접기
          </button>
        ) : null}
      </div>
    </div>
  )
}
