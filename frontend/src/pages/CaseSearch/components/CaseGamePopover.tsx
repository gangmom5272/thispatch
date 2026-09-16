import { isApiError } from "@/api/error"
import { useGameDetail } from "@/hooks/queries/gameQueries"

interface CaseGamePopoverProps {
  id: string
  gameId: number
  title: string
  capsuleImageUrl: string | null
}

export default function CaseGamePopover({
  id,
  gameId,
  title,
  capsuleImageUrl,
}: CaseGamePopoverProps) {
  const game = useGameDetail(gameId)
  const imageUrl = capsuleImageUrl ?? game.data?.capsuleImageUrl ?? null

  return (
    <div
      id={id}
      role="tooltip"
      className="absolute top-full left-0 z-20 mt-sb-2 w-96 max-w-[calc(100vw-32px)] overflow-hidden rounded-sb-card border border-sb-hairline-strong bg-sb-canvas shadow-sb-popover"
    >
      {imageUrl ? (
        <img src={imageUrl} alt="" className="h-32 w-full bg-sb-canvas-soft object-cover" />
      ) : (
        <div aria-hidden="true" className="h-32 w-full bg-sb-canvas-soft" />
      )}
      <div className="flex flex-col gap-sb-2 p-sb-4">
        <p className="text-sb-lead font-medium">{title}</p>
        {game.isPending && <p className="text-sb-ink-mute">게임 정보를 불러오는 중…</p>}
        {game.isError && (
          <p className="text-sb-ink-mute">
            {isApiError(game.error) ? game.error.message : "게임 정보를 불러오지 못했습니다."}
          </p>
        )}
        {game.isSuccess && (
          <>
            <p className="text-sb-ink-mute">
              {[
                game.data.tags.map((tag) => tag.name).join(" · "),
                game.data.releasedOn ? `${game.data.releasedOn} 출시` : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </p>
            {game.data.description && (
              <>
                <hr className="border-sb-hairline" />
                <p className="leading-relaxed">{game.data.description}</p>
              </>
            )}
            <hr className="border-sb-hairline" />
            <dl className="flex flex-wrap gap-sb-4">
              <div className="flex gap-sb-2">
                <dt className="text-sb-ink-mute">리뷰</dt>
                <dd className="font-sb-mono tabular-nums">
                  {game.data.reviewCount === null
                    ? "—"
                    : `${game.data.reviewCount.toLocaleString("en-US")}건`}
                </dd>
              </div>
              <div className="flex gap-sb-2">
                <dt className="text-sb-ink-mute">전체 긍정률</dt>
                <dd className="font-sb-mono tabular-nums">
                  {game.data.positiveRate === null ? "—" : `${game.data.positiveRate}%`}
                </dd>
              </div>
            </dl>
          </>
        )}
        <p className="text-sb-caption text-sb-ink-mute">
          출처 · Steam Store 설명과 장르 메타데이터
        </p>
      </div>
    </div>
  )
}
