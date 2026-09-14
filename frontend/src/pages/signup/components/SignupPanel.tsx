import type { ReactNode } from "react"

export function SignupPanel({ children }: { children: ReactNode }) {
  return (
    <section className="w-full max-w-md rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-6 text-left">
      {children}
    </section>
  )
}
