export function GameHeaderSkeleton() {
  return (
    <header className="border-b border-sb-hairline-cool bg-sb-canvas-base" aria-busy="true">
      <div className="border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 pt-sb-3 md:px-sb-12">
        <div className="h-5 w-24 animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
      </div>
      <div className="flex flex-col gap-sb-4 border-b border-sb-hairline bg-sb-canvas-surface px-sb-4 py-sb-4 md:flex-row md:items-center md:gap-[18px] md:px-sb-12 md:pb-3.5 md:pt-sb-4">
        <div className="h-[86px] w-[184px] shrink-0 animate-pulse rounded-sb-control bg-sb-canvas-soft" />
        <div className="flex min-w-0 flex-1 flex-col gap-sb-2">
          <div className="h-7 w-64 max-w-full animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
          <div className="h-5 w-full max-w-xl animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
          <div className="flex flex-wrap gap-sb-2">
            <div className="h-7 w-20 animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
            <div className="h-7 w-16 animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
            <div className="h-7 w-28 animate-pulse rounded-sb-tag bg-sb-canvas-soft" />
          </div>
        </div>
      </div>
      <span className="sr-only">게임 정보를 불러오는 중</span>
    </header>
  )
}
