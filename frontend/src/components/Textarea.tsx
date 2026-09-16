import type { TextareaHTMLAttributes } from "react"
import { cn } from "@/lib/cn"

export default function Textarea({
  className,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={cn(
        "max-h-100 min-h-25 w-full field-sizing-content resize-y rounded-sb-control border border-sb-hairline-strong bg-sb-canvas-base p-sb-3 text-sb-body leading-relaxed text-sb-ink placeholder:text-sb-ink-mute-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50",
        className,
      )}
      {...props}
    />
  )
}
