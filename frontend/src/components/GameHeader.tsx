import { Link } from "react-router"
import { isApiError } from "@/api/error"
import Button from "@/components/Button"
import { GameHeaderSkeleton } from "@/components/GameHeaderSkeleton"
import { GameImage } from "@/components/GameImage"
import { useGameDetail } from "@/hooks/queries/gameQueries"
import { gameDetailPath, lastGamesPath, paths } from "@/router/paths"
import type { GameDetail } from "@/types"

function formatReviewCount(count: number | null): string {
  if (count === null) return "—"
  return count.toLocaleString("en-US")
}

function formatPositiveRate(rate: number | null): string {
  if (rate === null) return "—"
  const rounded = Number.isInteger(rate) ? String(rate) : rate.toFixed(1)
  return `${rounded}%`
}

function BackArrowIcon() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className="size-[17px] shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M19 12H5" />
      <path d="m12 19-7-7 7-7" />
    </svg>
  )
}

function GameInfo({ game }: { game: GameDetail }) {
  return (
    <div className="flex flex-col gap-sb-4 border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-4 md:flex-row md:items-center md:gap-[18px] md:px-sb-12 md:pb-3.5 md:pt-sb-4">
      <div className="h-[86px] w-[184px] shrink-0 overflow-hidden rounded-sb-control border border-sb-hairline-cool bg-sb-canvas">
        <GameImage src={game.capsuleImageUrl} className="size-full" loading="eager" />
      </div>

      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex flex-col gap-sb-2 sm:flex-row sm:items-center sm:gap-sb-2">
          <h1 className="text-sb-heading font-medium tracking-[-0.42px] text-sb-ink">
            {game.title}
          </h1>
        </div>

        {game.description ? (
          <p className="text-sb-body leading-normal text-sb-ink">{game.description}</p>
        ) : null}

        <div className="flex flex-wrap items-center gap-sb-2">
          {game.tags.map((tag) => (
            <span
              key={tag.id}
              className="rounded-sb-tag border border-sb-hairline bg-sb-canvas-soft px-sb-2 py-[3px] text-sb-body text-sb-ink-mute"
            >
              {tag.name}
            </span>
          ))}

          {game.tags.length > 0 ? (
            <span aria-hidden="true" className="hidden h-4 w-px bg-sb-hairline-strong sm:block" />
          ) : null}

          <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
            <span>출시</span>
            <span className="font-sb-mono tabular-nums text-sb-ink">{game.releasedOn ?? "—"}</span>
          </span>
          <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
            <span>리뷰</span>
            <span className="font-sb-mono tabular-nums text-sb-ink">
              {formatReviewCount(game.reviewCount)}
            </span>
          </span>
          <span className="inline-flex items-center gap-1.5 text-sb-body text-sb-ink-mute">
            <span>전체 긍정률</span>
            <span className="font-sb-mono tabular-nums text-sb-ink">
              {formatPositiveRate(game.positiveRate)}
            </span>
          </span>
        </div>
      </div>
    </div>
  )
}

type GameHeaderBack = "games" | "gameDetail"

interface GameHeaderProps {
  gameId: number
  /** 진단 탭 화면은 게임 목록으로, 다음 패치 화면은 게임 분석(상세)으로 돌아간다. */
  back?: GameHeaderBack
}

export function GameHeader({ gameId, back = "games" }: GameHeaderProps) {
  const query = useGameDetail(gameId)

  if (query.isPending) {
    return <GameHeaderSkeleton />
  }

  const backLink =
    back === "games"
      ? { to: lastGamesPath(), label: "게임 목록" }
      : { to: gameDetailPath(gameId), label: "게임 분석" }

  return (
    <header className="border-b border-sb-hairline-cool bg-sb-canvas-base">
      <div className="border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-3 md:px-sb-12">
        <Link
          to={backLink.to}
          className="inline-flex items-center gap-1.5 text-sb-body text-sb-primary hover:text-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          <BackArrowIcon />
          {backLink.label}
        </Link>
      </div>

      {query.isError ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-sb-3 border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-4 md:px-sb-12"
        >
          <p className="text-sb-body text-sb-ink">
            {isApiError(query.error)
              ? query.error.message
              : "게임 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
          </p>
          {isApiError(query.error) && query.error.status === 401 ? (
            <Link
              to={paths.login}
              className="rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
            >
              로그인하기
            </Link>
          ) : (
            <Button variant="secondary" onClick={() => query.refetch()} disabled={query.isFetching}>
              다시 시도
            </Button>
          )}
        </div>
      ) : (
        <GameInfo game={query.data} />
      )}
    </header>
  )
}
