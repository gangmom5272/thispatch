import { useState } from "react"
import gamePlaceholder from "@/assets/game-placeholder.jpg"
import { cn } from "@/lib/cn"

interface GameImageProps {
  /** Steam 이미지 URL — null/빈 값이면 대체 이미지를 쓴다. */
  src: string | null | undefined
  alt?: string
  className?: string
  loading?: "lazy" | "eager"
}

/**
 * 게임 캡슐·헤더 이미지를 렌더한다.
 * URL이 없거나 로드 실패 시 ThisPatch 대체 이미지를 표시한다.
 */
export function GameImage({ src, alt = "", className, loading = "lazy" }: GameImageProps) {
  // failed: 원격 이미지 onError 후 플레이스홀더로 전환했는지
  const [failed, setFailed] = useState(false)
  const resolvedSrc = !src || failed ? gamePlaceholder : src

  return (
    <img
      src={resolvedSrc}
      alt={alt}
      loading={loading}
      onError={() => {
        if (!failed) setFailed(true)
      }}
      className={cn("bg-sb-canvas object-cover", className)}
    />
  )
}
