import { Link } from "react-router"
import { isApiError } from "@/api/error"
import { useGameDetail } from "@/hooks/queries/gameQueries"
import { gameDetailPath } from "@/router/paths"
import type { GameDetail } from "@/types"

const secondaryButtonClass =
  "h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
const thumbnailClass =
  "aspect-[460/215] w-46 shrink-0 rounded-sb-control border border-sb-hairline-cool bg-sb-canvas object-cover"

function formatCollectedAt(iso: string): string {
  const date = new Date(iso)
  const pad = (value: number) => String(value).padStart(2, "0")
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function Fact({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex gap-sb-1">
      <dt className="text-sb-ink-mute">{label}</dt>
      <dd className={value ? "font-sb-mono font-medium tabular-nums" : "text-sb-ink-mute"}>
        {value ?? "—"}
      </dd>
    </div>
  )
}

function GameInfo({ game }: { game: GameDetail }) {
  return (
    <div className="flex flex-col gap-sb-4 sm:flex-row sm:items-center">
      {game.capsuleImageUrl ? (
        <img src={game.capsuleImageUrl} alt="" className={thumbnailClass} />
      ) : (
        <div aria-hidden="true" className={thumbnailClass} />
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-sb-2">
        <div className="flex flex-wrap items-baseline gap-x-sb-3 gap-y-sb-1">
          <h1 className="text-sb-heading font-medium">{game.title}</h1>
          {game.lastCollectedAt && (
            <p className="font-sb-mono text-sb-ink-mute tabular-nums sm:ml-auto">
              수집 {formatCollectedAt(game.lastCollectedAt)} · 수정일 기준 집계
            </p>
          )}
        </div>
        {game.description && <p>{game.description}</p>}
        <div className="flex flex-wrap items-center gap-sb-2">
          {game.tags.length > 0 && (
            <>
              <ul aria-label="장르" className="flex flex-wrap gap-sb-2">
                {game.tags.map((tag) => (
                  <li
                    key={tag.id}
                    className="rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 py-sb-1 text-sb-ink-mute"
                  >
                    {tag.name}
                  </li>
                ))}
              </ul>
              <span aria-hidden="true" className="h-4 w-px bg-sb-hairline-strong" />
            </>
          )}
          <dl className="flex flex-wrap gap-sb-3">
            <Fact label="출시" value={game.releasedOn} />
            <Fact label="리뷰" value={game.reviewCount?.toLocaleString("ko-KR") ?? null} />
            <Fact
              label="전체 긍정률"
              value={game.positiveRate === null ? null : `${game.positiveRate}%`}
            />
          </dl>
        </div>
      </div>
    </div>
  )
}

export default function GameHeader({ gameId }: { gameId: number }) {
  const query = useGameDetail(gameId)

  return (
    <div className="border-b border-sb-hairline-cool bg-sb-canvas-surface">
      <div className="border-b border-sb-hairline">
        <div className="mx-auto max-w-sb-page px-sb-4 py-sb-2 md:px-sb-12">
          <Link
            to={gameDetailPath(gameId)}
            className="inline-flex items-center gap-sb-1 rounded-sb-tag text-sb-primary hover:text-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
          >
            <span aria-hidden="true">←</span>
            게임 분석
          </Link>
        </div>
      </div>
      <div className="mx-auto max-w-sb-page px-sb-4 py-sb-4 md:px-sb-12">
        {query.isPending && (
          <div aria-label="게임 정보 불러오는 중" aria-busy="true" className="flex gap-sb-4">
            <div className={`${thumbnailClass} animate-pulse motion-reduce:animate-none`} />
            <div className="flex flex-1 flex-col gap-sb-2">
              <div className="h-8 w-64 animate-pulse rounded-sb-tag bg-sb-canvas-soft motion-reduce:animate-none" />
              <div className="h-6 w-full max-w-2xl animate-pulse rounded-sb-tag bg-sb-canvas-soft motion-reduce:animate-none" />
            </div>
          </div>
        )}
        {query.isError && (
          <div role="alert" className="flex flex-wrap items-center gap-sb-3">
            <p>
              {isApiError(query.error)
                ? query.error.message
                : "게임 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
            </p>
            <button
              type="button"
              onClick={() => query.refetch()}
              disabled={query.isFetching}
              className={secondaryButtonClass}
            >
              다시 시도
            </button>
          </div>
        )}
        {query.isSuccess && <GameInfo game={query.data} />}
      </div>
    </div>
  )
}
