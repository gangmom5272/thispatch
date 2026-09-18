import { useEffect, useRef, useState } from "react"
import type { GameTag } from "../../../types"

/** 칩 한 줄 높이: py-sb-1(8) + body line(24) */
const TAG_CHIP_HEIGHT_PX = 32
/** gap-sb-1 */
const TAG_GAP_PX = 4
/** 장르 태그 슬롯 고정 높이(2줄) */
const TAG_SLOT_HEIGHT_PX = TAG_CHIP_HEIGHT_PX * 2 + TAG_GAP_PX

/**
 * 장르 태그를 최대 2줄로 고정하고, 넘치면 우하단에 …을 표시한다.
 */
export default function GenreTagClamp({ tags }: { tags: GameTag[] }) {
  const listRef = useRef<HTMLUListElement>(null)
  // 태그 영역이 2줄을 넘는지 여부
  const [overflows, setOverflows] = useState(false)

  useEffect(() => {
    const list = listRef.current
    if (!list) return

    const measure = () => {
      // 1px 여유: 서브픽셀 반올림으로 오탐 방지
      setOverflows(list.scrollHeight > list.clientHeight + 1)
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(list)
    return () => observer.disconnect()
  }, [tags])

  return (
    <div className="relative shrink-0 overflow-hidden" style={{ height: TAG_SLOT_HEIGHT_PX }}>
      <ul
        ref={listRef}
        className="flex h-full flex-wrap gap-sb-1 overflow-hidden"
        aria-label="장르"
      >
        {tags.map((tag) => (
          <li key={tag.id} className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1">
            {tag.name}
          </li>
        ))}
      </ul>
      {overflows ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute right-0 bottom-0 rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1 text-sb-ink-mute"
        >
          …
        </span>
      ) : null}
    </div>
  )
}
