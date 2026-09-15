import { Link, Outlet, useParams } from "react-router"
import { isApiError } from "@/api/error"
import { useGameDetail } from "@/hooks/queries/gameQueries"
import { GameDetailTabs } from "@/pages/GameDetail/components/GameDetailTabs"
import { GameHeader } from "@/pages/GameDetail/components/GameHeader"
import { GameHeaderSkeleton } from "@/pages/GameDetail/components/GameHeaderSkeleton"
import { paths } from "@/router/paths"

function parseGameId(raw: string | undefined): number | null {
  if (!raw || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id >= 1 ? id : null
}

function GameDetailStatus({
  title,
  message,
  action,
}: {
  title: string
  message: string
  action?: { to: string; label: string }
}) {
  return (
    <div className="mx-auto max-w-sb-page px-sb-4 py-sb-12 md:px-sb-12">
      <h1 className="text-sb-heading font-medium text-sb-ink">{title}</h1>
      <p className="mt-sb-2 text-sb-body text-sb-ink-mute">{message}</p>
      {action ? (
        <Link
          to={action.to}
          className="mt-sb-4 inline-flex h-sb-control items-center rounded-sb-control bg-sb-primary px-sb-4 text-sb-body text-sb-on-primary hover:bg-sb-primary-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          {action.label}
        </Link>
      ) : null}
    </div>
  )
}

export default function GameDetailPage() {
  const { gameId: rawGameId } = useParams()
  const gameId = parseGameId(rawGameId)
  const query = useGameDetail(gameId)

  if (gameId === null) {
    return (
      <GameDetailStatus
        title="잘못된 게임 주소"
        message="게임 ID가 올바르지 않습니다. 게임 목록에서 다시 선택해 주세요."
        action={{ to: paths.games, label: "게임 목록으로" }}
      />
    )
  }

  if (query.isPending) {
    return (
      <>
        <GameHeaderSkeleton />
        <Outlet />
      </>
    )
  }

  if (query.isError) {
    const status = isApiError(query.error) ? query.error.status : 0
    if (status === 401) {
      return (
        <GameDetailStatus
          title="로그인이 필요합니다"
          message="게임 상세를 보려면 로그인해 주세요."
          action={{ to: paths.login, label: "로그인" }}
        />
      )
    }
    if (status === 404) {
      return (
        <GameDetailStatus
          title="게임을 찾을 수 없습니다"
          message="요청한 게임이 없거나 삭제되었을 수 있습니다."
          action={{ to: paths.games, label: "게임 목록으로" }}
        />
      )
    }
    return (
      <GameDetailStatus
        title="불러오지 못했습니다"
        message={
          isApiError(query.error)
            ? query.error.message
            : "게임 정보를 불러오는 중 오류가 발생했습니다."
        }
        action={{ to: paths.games, label: "게임 목록으로" }}
      />
    )
  }

  return (
    <>
      <GameHeader game={query.data} />
      <GameDetailTabs />
      <Outlet />
    </>
  )
}
