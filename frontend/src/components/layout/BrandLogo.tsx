import { Link } from "react-router"
import { cn } from "@/lib/cn"
import { paths } from "@/router/paths"

function BrandMarkIcon({ size }: { size: number }) {
  return (
    <svg
      aria-hidden
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="shrink-0 text-sb-primary"
    >
      <circle cx="18" cy="18" r="3" />
      <circle cx="6" cy="6" r="3" />
      <path d="M13 6h3a2 2 0 0 1 2 2v7" />
      <path d="M11 18H8a2 2 0 0 1-2-2V9" />
    </svg>
  )
}

interface BrandLogoProps {
  size?: number
  interactive?: boolean
  className?: string
}

export function BrandLogo({ size = 22, interactive = true, className }: BrandLogoProps) {
  const content = (
    <>
      <BrandMarkIcon size={size} />
      <span>ThisPatch</span>
    </>
  )

  const styles = cn(
    "inline-flex items-center gap-[9px] font-medium tracking-[-0.2px] text-sb-ink",
    size >= 40 ? "text-sb-heading" : "text-sb-lead",
    className,
  )

  if (!interactive) {
    return <span className={cn(styles, "pointer-events-none select-none")}>{content}</span>
  }

  return (
    <Link
      to={paths.home}
      className={cn(
        styles,
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary",
      )}
    >
      {content}
    </Link>
  )
}
