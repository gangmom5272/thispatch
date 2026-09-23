import { isApiError } from "@/api/error"
import GameSummaryTooltip from "@/components/GameSummaryTooltip"
import { useGameDetail } from "@/hooks/queries/gameQueries"

interface CaseGamePopoverProps {
  id: string
  gameId: number
}

/** 게임 목록(/games) 카드와 같은 요약 툴팁. 사례 응답에 요약이 없어 게임 상세를 조회해 채운다. */
export default function CaseGamePopover({ id, gameId }: CaseGamePopoverProps) {
  const game = useGameDetail(gameId)

  return game.isSuccess ? (
    <GameSummaryTooltip
      id={id}
      summary={{
        releasedOn: game.data.releasedOn,
        description: game.data.description,
        reviewCount: game.data.reviewCount,
        userTags: game.data.tags.map((tag) => tag.name),
      }}
    />
  ) : (
    <p
      id={id}
      role="tooltip"
      className="rounded-sb-card border border-sb-hairline-strong bg-sb-canvas p-sb-4 text-sb-ink-mute shadow-sb-popover"
    >
      {game.isError
        ? isApiError(game.error)
          ? game.error.message
          : "게임 정보를 불러오지 못했습니다."
        : "게임 정보를 불러오는 중…"}
    </p>
  )
}
