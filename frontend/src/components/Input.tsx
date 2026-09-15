import type { InputHTMLAttributes } from "react"
import { cn } from "@/lib/cn"

export default function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "h-sb-control rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-body text-sb-ink placeholder:text-sb-ink-mute-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  )
}
