import type { GameSummary } from "../../../types"

export default function GameSummaryPopover({ summary }: { summary: GameSummary }) {
  return (
    <div className="w-96 overflow-hidden rounded-sb-card border border-sb-hairline-strong bg-sb-canvas shadow-sb-popover">
      <img
        src={summary.headerImageUrl}
        alt=""
        className="h-32 w-full bg-sb-canvas-soft object-cover"
      />
      <div className="flex flex-col gap-sb-2 p-sb-4">
        <p className="text-sb-lead font-medium">{summary.title}</p>
        <p className="text-sb-ink-mute">
          <time className="font-sb-mono tabular-nums">{summary.releasedAt.slice(0, 10)}</time> 출시
          · {summary.developer} · {summary.playModes.join(" · ")}
        </p>
        <hr className="border-sb-hairline" />
        <p className="leading-relaxed">{summary.description}</p>
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
              {summary.reviewCount.toLocaleString("ko-KR")}건
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
