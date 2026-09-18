import { Link } from "react-router"
import { GameImage } from "@/components/GameImage"
import type { Game, MyGame } from "@/types"
import { PositiveRate, StarIcon } from "./GameCardParts"

interface GameExpandedCardProps {
  game: Game | MyGame
  isMine: boolean
  detailPath: string
  isToggling: boolean
  onToggle: () => void
}

const tagClass = "rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1"

/**
 * 호버·포커스 시 목록 카드 위에 겹쳐 뜨는 펼쳐진 카드.
 * 컴팩트 카드와 같은 머리(이미지·제목·별)를 유지하고 그 아래로 Steam 요약을 이어 붙인다.
 * 보조 기술에는 컴팩트 카드만 노출하므로 이 안의 컨트롤은 포인터 전용이다.
 */
export default function GameExpandedCard({
  game,
  isMine,
  detailPath,
  isToggling,
  onToggle,
}: GameExpandedCardProps) {
  const summary = game.gameSummary
  const meta = [
    summary.releasedOn ? `${summary.releasedOn} 출시` : null,
    summary.developer,
    ...summary.playModes,
  ].filter(Boolean)

  return (
    <article className="flex flex-col overflow-hidden rounded-sb-card border border-sb-primary bg-sb-canvas shadow-sb-popover">
      <Link to={detailPath} tabIndex={-1} draggable={false} className="block">
        <GameImage
          src={summary.headerImageUrl ?? game.capsuleImageUrl}
          className="aspect-[460/215] w-full"
        />
      </Link>
      <div className="flex flex-col gap-sb-3 p-sb-4">
        <div className="flex items-start justify-between gap-sb-2">
          <p className="min-w-0 flex-1 text-sb-title font-medium">
            <Link
              to={detailPath}
              tabIndex={-1}
              draggable={false}
              className="block truncate leading-[1.35] hover:text-sb-primary-text"
            >
              {game.title}
            </Link>
          </p>
          <button
            type="button"
            tabIndex={-1}
            disabled={isToggling}
            onClick={onToggle}
            className={`shrink-0 cursor-pointer rounded-sb-tag disabled:cursor-not-allowed disabled:opacity-50 ${isMine ? "text-sb-amber-text hover:text-sb-ink-mute" : "text-sb-ink-mute hover:text-sb-ink"}`}
          >
            <StarIcon filled={isMine} />
          </button>
        </div>
        {meta.length > 0 && (
          <p className="font-sb-mono text-sb-caption text-sb-ink-mute tabular-nums">
            {meta.join(" · ")}
          </p>
        )}
        {game.tags.length > 0 && (
          <ul className="flex flex-wrap gap-sb-1">
            {game.tags.map((tag) => (
              <li key={tag.id} className={tagClass}>
                {tag.name}
              </li>
            ))}
          </ul>
        )}
        <PositiveRate rate={game.positiveRate} />

        <hr className="border-sb-hairline" />
        <p className="leading-relaxed">{summary.description ?? "게임 설명이 없습니다."}</p>
        {summary.userTags.length > 0 && (
          <>
            <p className="text-sb-caption text-sb-ink-mute">사용자 태그</p>
            <ul className="flex flex-wrap gap-sb-1">
              {summary.userTags.map((tag) => (
                <li key={tag} className={tagClass}>
                  {tag}
                </li>
              ))}
            </ul>
          </>
        )}

        <hr className="border-sb-hairline" />
        <dl className="flex flex-wrap gap-sb-4">
          <div className="flex gap-sb-2">
            <dt className="text-sb-ink-mute">리뷰</dt>
            <dd className="font-sb-mono tabular-nums">
              {summary.reviewCount === null
                ? "집계 전"
                : `${summary.reviewCount.toLocaleString("en-US")}건`}
            </dd>
          </div>
          <div className="flex gap-sb-2">
            <dt className="text-sb-ink-mute">최근 패치</dt>
            <dd className="font-sb-mono">{summary.latestPatch}</dd>
          </div>
        </dl>
        <p className="text-sb-caption text-sb-ink-mute-2">출처 · Steam Store 설명과 사용자 태그</p>
      </div>
    </article>
  )
}
