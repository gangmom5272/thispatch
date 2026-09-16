import { Outlet } from "react-router"
import { AppHeader } from "@/components/layout/AppHeader"

export function AppShell() {
  return (
    <div className="min-h-screen bg-sb-canvas-base font-sb-sans text-sb-body text-sb-ink scheme-dark">
      <AppHeader />
      <Outlet />
    </div>
  )
}
