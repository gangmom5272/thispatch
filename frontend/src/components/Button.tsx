import type { ButtonHTMLAttributes } from "react"
import { cn } from "@/lib/cn"

type ButtonVariant = "primary" | "secondary"

const variantClass: Record<ButtonVariant, string> = {
  primary:
    "bg-sb-primary font-medium text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep",
  secondary: "border border-sb-hairline-strong bg-sb-canvas text-sb-ink",
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant: ButtonVariant
}

export default function Button({ variant, type = "button", className, ...props }: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        "inline-flex h-sb-control cursor-pointer items-center justify-center gap-sb-2 rounded-sb-control px-sb-4 text-sb-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50",
        variantClass[variant],
        className,
      )}
      {...props}
    />
  )
}
