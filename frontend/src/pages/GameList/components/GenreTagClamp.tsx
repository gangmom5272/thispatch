import { useEffect, useRef, useState } from "react"
import type { GameTag } from "../../../types"

/** 칩 한 줄 높이: body line + padding + descender 여유 */
const TAG_CHIP_HEIGHT_PX = 34
/** gap-sb-1 */
const TAG_GAP_PX = 4
/** 태그가 쌓일 수 있는 최대 줄 수(슬롯 높이) */
const TAG_SLOT_HEIGHT_PX = TAG_CHIP_HEIGHT_PX * 2 + TAG_GAP_PX

const chipClass =
  "flex h-[34px] shrink-0 items-center whitespace-nowrap rounded-sb-tag bg-sb-canvas-soft px-sb-2 leading-normal"

/**
 * 장르 태그를 최대 2줄로 두고, 넘치면 2줄째 끝에 인라인 … 칩을 넣는다.
 */
export default function GenreTagClamp({ tags }: { tags: GameTag[] }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const measureRef = useRef<HTMLUListElement>(null)
  // 화면에 그릴 태그 개수
  const [visibleCount, setVisibleCount] = useState(tags.length)
  // 2줄을 넘어 … 칩이 필요한지
  const [showEllipsis, setShowEllipsis] = useState(false)

  useEffect(() => {
    const container = containerRef.current
    const measure = measureRef.current
    if (!container || !measure) return

    const layout = () => {
      // 카드 태그 영역 너비(px)
      const maxWidth = container.clientWidth
      if (maxWidth <= 0 || tags.length === 0) {
        setVisibleCount(0)
        setShowEllipsis(false)
        return
      }

      const chipEls = [...measure.querySelectorAll<HTMLElement>("[data-measure-tag]")]
      const ellipsisWidth =
        measure.querySelector<HTMLElement>("[data-measure-ellipsis]")?.offsetWidth ?? 28

      /** 주어진 폭 예약으로 2줄에 몇 개까지 들어가는지 계산 */
      const fitCount = (reserveOnLastRow: number): number => {
        let row = 0
        let rowWidth = 0
        let count = 0

        for (const el of chipEls) {
          const w = el.offsetWidth
          const needed = rowWidth === 0 ? w : rowWidth + TAG_GAP_PX + w
          const limit = row === 1 ? maxWidth - reserveOnLastRow : maxWidth

          if (needed <= limit) {
            rowWidth = needed
            count++
            continue
          }

          if (row === 0) {
            // 다음 줄로
            row = 1
            const lastLimit = maxWidth - reserveOnLastRow
            if (w <= lastLimit) {
              rowWidth = w
              count++
              continue
            }
            break
          }

          break
        }

        return count
      }

      const withoutEllipsis = fitCount(0)
      if (withoutEllipsis >= tags.length) {
        setVisibleCount(tags.length)
        setShowEllipsis(false)
        return
      }

      // … 폭 + gap을 2줄째에 예약하고 다시 계산
      const reserve = ellipsisWidth + TAG_GAP_PX
      const withEllipsis = fitCount(reserve)
      setVisibleCount(Math.max(0, withEllipsis))
      setShowEllipsis(true)
    }

    layout()
    const observer = new ResizeObserver(layout)
    observer.observe(container)
    return () => observer.disconnect()
  }, [tags])

  return (
    <div
      ref={containerRef}
      className="relative shrink-0 overflow-hidden"
      style={{ height: TAG_SLOT_HEIGHT_PX }}
    >
      {/* 폭 측정용 — 화면에 보이지 않음 */}
      <ul
        ref={measureRef}
        aria-hidden="true"
        className="pointer-events-none invisible absolute top-0 left-0 flex flex-wrap gap-sb-1"
      >
        {tags.map((tag) => (
          <li key={tag.id} data-measure-tag className={chipClass}>
            {tag.name}
          </li>
        ))}
        <li data-measure-ellipsis className={chipClass}>
          …
        </li>
      </ul>

      <ul className="flex flex-wrap gap-sb-1" aria-label="장르">
        {tags.slice(0, visibleCount).map((tag) => (
          <li key={tag.id} className={chipClass}>
            {tag.name}
          </li>
        ))}
        {showEllipsis ? (
          <li aria-hidden="true" className={`${chipClass} text-sb-ink-mute`}>
            …
          </li>
        ) : null}
      </ul>
    </div>
  )
}
