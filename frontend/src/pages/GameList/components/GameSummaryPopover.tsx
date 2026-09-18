import { GameImage } from "@/components/GameImage"
import type { GameSummary, GameTag } from "../../../types"

/** 게임 카드 호버 시 Steam 요약 정보를 보여 준다. */
export default function GameSummaryPopover({
  summary,
  genreTags,
}: {
  summary: GameSummary
  /** 카드에서 잘린 장르를 포함해 전부 표시 */
  genreTags: GameTag[]
}) {
  return (
    <div className="w-96 overflow-hidden rounded-sb-card border border-sb-hairline-strong bg-sb-canvas shadow-sb-popover">
      <GameImage src={summary.headerImageUrl} className="h-32 w-full bg-sb-canvas-soft" />
      <div className="flex flex-col gap-sb-2 p-sb-4">
        <p className="text-sb-lead font-medium">{summary.title}</p>
        <p className="text-sb-ink-mute">
          {summary.releasedOn && (
            <>
              <time className="font-sb-mono tabular-nums">{summary.releasedOn} </time>출시 ·{" "}
            </>
          )}
          {summary.developer} · {summary.playModes.join(" · ")}
        </p>
        <hr className="border-sb-hairline" />
        <p className="leading-relaxed">{summary.description ?? "게임 설명이 없습니다."}</p>
        {genreTags.length > 0 ? (
          <>
            <hr className="border-sb-hairline" />
            <p className="text-sb-ink-mute">장르</p>
            <ul className="flex flex-wrap gap-sb-1" aria-label="장르 전체">
              {genreTags.map((tag) => (
                <li key={tag.id} className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1">
                  {tag.name}
                </li>
              ))}
            </ul>
          </>
        ) : null}
        <hr className="border-sb-hairline" />
        <p className="text-sb-ink-mute">사용자 태그</p>
        <ul className="flex flex-wrap gap-sb-1">
          {summary.userTags.map((tag) => (
            <li key={tag} className="rounded-sb-tag bg-sb-canvas-soft px-sb-2 py-sb-1">
              {tag}
            </li>
          ))}
        </ul>
        <hr className="border-sb-hairline" />
        <dl className="flex flex-wrap gap-sb-4">
          <div className="flex gap-sb-2">
            <dt className="text-sb-ink-mute">리뷰</dt>
            <dd className="font-sb-mono tabular-nums">
              {summary.reviewCount === null
                ? "집계 전"
                : `${summary.reviewCount.toLocaleString("ko-KR")}건`}
            </dd>
          </div>
          <div className="flex gap-sb-2">
            <dt className="text-sb-ink-mute">최근 패치</dt>
            <dd className="font-sb-mono">{summary.latestPatch}</dd>
          </div>
        </dl>
        <p className="text-sb-caption text-sb-ink-mute">출처 · Steam Store 설명과 사용자 태그</p>
      </div>
    </div>
  )
}
