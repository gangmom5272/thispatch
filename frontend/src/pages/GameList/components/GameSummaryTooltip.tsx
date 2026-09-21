import type { Game, MyGame } from "@/types"

interface GameSummaryTooltipProps {
  id: string
  game: Game | MyGame
}

/**
 * 게임 카드 옆에 뜨는 Steam 스토어식 요약 툴팁.
 * 카드에 이미 있는 이미지·장르·긍정률·최근 패치는 빼고, 카드에 없는 정보만 담는다.
 * 포인터를 받지 않으므로 툴팁 위로 마우스가 지나가면 카드를 벗어난 것으로 처리된다.
 */
export default function GameSummaryTooltip({ id, game }: GameSummaryTooltipProps) {
  const summary = game.gameSummary
  const release = [
    summary.releasedOn ? `출시 ${summary.releasedOn}` : null,
    summary.developer,
  ].filter(Boolean)

  return (
    <div
      id={id}
      role="tooltip"
      className="flex flex-col gap-sb-3 rounded-sb-card border border-sb-hairline-strong bg-sb-canvas p-sb-4 shadow-sb-popover"
    >
      <div className="flex flex-col gap-sb-1 font-sb-mono text-sb-caption text-sb-ink-mute tabular-nums">
        {release.length > 0 && <p>{release.join(" · ")}</p>}
        {summary.playModes.length > 0 && <p>{summary.playModes.join(" · ")}</p>}
      </div>

      <p className="leading-relaxed">{summary.description ?? "게임 설명이 없습니다."}</p>

      <div className="flex flex-wrap items-baseline gap-sb-2 rounded-sb-tag bg-sb-canvas-soft px-sb-3 py-sb-2">
        <span className="text-sb-ink-mute">리뷰</span>
        <span className="font-sb-mono tabular-nums">
          {summary.reviewCount === null
            ? "집계 전"
            : `${summary.reviewCount.toLocaleString("en-US")}건`}
        </span>
      </div>

      {summary.userTags.length > 0 && (
        <div className="flex flex-col gap-sb-1">
          <p className="text-sb-caption text-sb-ink-mute">사용자 태그</p>
          <ul className="flex flex-wrap gap-sb-1">
            {summary.userTags.map((tag) => (
              <li
                key={tag}
                className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1 text-sb-caption"
              >
                {tag}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
