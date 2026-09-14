import { Link } from "react-router"

export default function Header() {
  return (
    <header className="h-sb-header border-b border-sb-hairline bg-sb-canvas-header">
      <div className="mx-auto flex h-full max-w-sb-page items-center px-sb-4 md:px-sb-12">
        <Link
          to="/"
          className="flex items-center gap-sb-2 rounded-sb-control text-sb-lead text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          <span aria-hidden="true" className="size-5 rounded-sb-tag bg-sb-primary" />
          ThisPatch
        </Link>
      </div>
    </header>
  )
}
