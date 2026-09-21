import { Link } from "react-router"
import { cn } from "@/lib/cn"
import { paths } from "@/router/paths"

interface BrandLogoProps {
  size?: number
  interactive?: boolean
  className?: string
}

export function BrandLogo({ size = 22, interactive = true, className }: BrandLogoProps) {
  const content = (
    <img
      src={`${import.meta.env.BASE_URL}logo.png`}
      alt="ThisPatch"
      width={1254}
      height={314}
      className="block aspect-[4/1] w-full object-cover"
    />
  )

  const styles = cn(
    "inline-flex max-w-full shrink-0 items-center",
    size >= 40 ? "w-64" : "w-28 sm:w-36",
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
        "cursor-pointer rounded-sb-control focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary",
      )}
    >
      {content}
    </Link>
  )
}
