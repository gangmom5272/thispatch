import { BrandLogo } from "@/components/layout/BrandLogo"

/** 게임 목록 전용 레거시 헤더 — 로고는 앱 진입점(게임 목록)으로 연결한다. */
export default function Header() {
  return (
    <header className="h-sb-header border-b border-sb-hairline bg-sb-canvas-header">
      <div className="mx-auto flex h-full max-w-sb-page items-center px-sb-4 md:px-sb-12">
        <BrandLogo />
      </div>
    </header>
  )
}
