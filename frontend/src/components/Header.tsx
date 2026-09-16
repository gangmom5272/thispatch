import { Link } from "react-router"
import { paths } from "@/router/paths"

/** 게임 목록 전용 레거시 헤더 — 로고는 앱 진입점(게임 목록)으로 연결한다. */
export default function Header() {
  return (
    <header className="h-sb-header border-b border-sb-hairline bg-sb-canvas-header">
      <div className="mx-auto flex h-full max-w-sb-page items-center px-sb-4 md:px-sb-12">
        <Link
          to={paths.home}
          className="flex items-center gap-sb-2 rounded-sb-control text-sb-lead text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          <span aria-hidden="true" className="size-5 rounded-sb-tag bg-sb-primary" />
          ThisPatch
        </Link>
      </div>
    </header>
  )
}
