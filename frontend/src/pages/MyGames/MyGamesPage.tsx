import { Link } from "react-router"
import { isApiError } from "@/api/error"
import { useMyGameList } from "@/hooks/queries/gameQueries"
import { useSession } from "@/hooks/queries/sessionQueries"
import GameCard from "@/pages/GameList/components/GameCard"
import { paths } from "@/router/paths"

const SKELETON_COUNT = 4

const panelClass =
  "flex flex-col items-start gap-sb-3 rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6"
const secondaryButtonClass =
  "flex h-sb-control cursor-pointer items-center gap-sb-2 rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
const primaryLinkClass =
  "inline-flex h-sb-control items-center gap-sb-2 rounded-sb-control bg-sb-primary px-sb-4 font-medium text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

/** 별을 눌러 등록한 내 게임만 모아 보여 준다. 로그인이 필요하다. */
export default function MyGamesPage() {
  const session = useSession()

  return (
    <main className="mx-auto flex max-w-sb-page flex-col gap-sb-6 px-sb-4 py-sb-6 md:px-sb-12">
      <div className="flex flex-col gap-sb-2">
        <h1 className="text-sb-section font-medium md:text-sb-display">내 게임</h1>
        <p className="text-sb-lead text-sb-ink-mute">
          별을 눌러 등록한 게임입니다. 카드를 누르면 분석 화면으로 이동합니다.
        </p>
      </div>
      {session.isSuccess ? <MyGameList /> : <MyGameGridSkeleton />}
    </main>
  )
}

function MyGameList() {
  const myGames = useMyGameList({})

  if (myGames.isPending) return <MyGameGridSkeleton />

  if (myGames.isError) {
    return (
      <div role="alert" className={panelClass}>
        <p>
          {isApiError(myGames.error)
            ? myGames.error.message
            : "내 게임을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."}
        </p>
        <button
          type="button"
          onClick={() => myGames.refetch()}
          disabled={myGames.isFetching}
          className={secondaryButtonClass}
        >
          다시 시도
        </button>
      </div>
    )
  }

  if (myGames.data.length === 0) {
    return (
      <div role="status" className={`${panelClass} items-center py-sb-12 text-center`}>
        <p className="text-sb-title font-medium">등록한 게임이 없습니다</p>
        <p className="text-sb-ink-mute">
          게임 탐색에서 관심 있는 게임의 별을 누르면 여기에 모입니다.
        </p>
        <Link to={paths.games} className={primaryLinkClass}>
          게임 탐색으로 이동 <span aria-hidden="true">→</span>
        </Link>
      </div>
    )
  }

  return (
    <section className="flex flex-col gap-sb-5">
      <h2 className="flex items-center gap-sb-3 text-sb-heading font-medium">
        등록한 게임
        <span className="rounded-sb-tag bg-sb-tint-primary px-sb-2 font-sb-mono text-sb-caption text-sb-primary-text tabular-nums">
          {myGames.data.length.toLocaleString("en-US")}
        </span>
        <span aria-hidden="true" className="h-px flex-1 bg-sb-hairline" />
      </h2>
      <ul aria-label="내 게임" className="grid grid-cols-1 gap-sb-5 sm:grid-cols-2 lg:grid-cols-4">
        {myGames.data.map((game) => (
          <li key={game.id} className="h-full">
            <GameCard game={game} isMine />
          </li>
        ))}
      </ul>
      <p className="flex items-center gap-sb-2 text-sb-ink-mute">
        <span aria-hidden="true" className="text-sb-amber-text">
          ★
        </span>
        게임 탐색에서 카드의 별을 누르면 여기에 추가되고, 별을 다시 누르면 등록이 해제됩니다.
      </p>
    </section>
  )
}

function MyGameGridSkeleton() {
  return (
    <ul
      aria-label="내 게임 불러오는 중"
      aria-busy="true"
      className="grid grid-cols-1 gap-sb-5 sm:grid-cols-2 lg:grid-cols-4"
    >
      {Array.from({ length: SKELETON_COUNT }, (_, index) => (
        <li
          key={index}
          className="h-72 animate-pulse rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface motion-reduce:animate-none"
        />
      ))}
    </ul>
  )
}
