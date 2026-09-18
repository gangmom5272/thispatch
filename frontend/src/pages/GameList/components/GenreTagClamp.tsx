import { useEffect, useRef, useState } from "react"
import type { GameTag } from "../../../types"

/** 칩 한 줄 높이: body line + py-sb-1 + descender 여유 */
const TAG_CHIP_HEIGHT_PX = 34
/** gap-sb-1 */
const TAG_GAP_PX = 4
/** 태그가 쌓일 수 있는 최대 줄 수(슬롯 높이) */
const TAG_SLOT_HEIGHT_PX = TAG_CHIP_HEIGHT_PX * 2 + TAG_GAP_PX

/**
 * 장르 태그 칩은 한 줄로 두고, 칩이 쌓이는 영역만 최대 2줄로 자른다.
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
      <ul ref={listRef} className="flex flex-wrap gap-sb-1" aria-label="장르">
        {tags.map((tag) => (
          <li
            key={tag.id}
            className="flex h-[34px] shrink-0 items-center whitespace-nowrap rounded-sb-tag bg-sb-canvas-soft px-sb-2 leading-normal"
          >
            {tag.name}
          </li>
        ))}
      </ul>
      {overflows ? (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute right-0 bottom-0 flex h-[34px] items-center rounded-sb-tag bg-sb-canvas-soft px-sb-2 leading-normal text-sb-ink-mute"
        >
          …
        </span>
      ) : null}
    </div>
  )
}
