import { useState } from "react"
import { cn } from "@/lib/cn"

const gamePlaceholder = `${import.meta.env.BASE_URL}game-placeholder.jpg`

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
  // loaded: 이미지가 그려지기 전까지 자리를 채우는 스켈레톤을 보여준다.
  const [loaded, setLoaded] = useState(false)
  const resolvedSrc = !src || failed ? gamePlaceholder : src

  return (
    <img
      // 캐시된 이미지는 onLoad가 붙기 전에 끝날 수 있어 마운트 시점에도 확인한다.
      ref={(node) => {
        if (node?.complete) setLoaded(true)
      }}
      src={resolvedSrc}
      alt={alt}
      loading={loading}
      onLoad={() => setLoaded(true)}
      onError={() => {
        if (!failed) setFailed(true)
      }}
      className={cn(
        "bg-sb-canvas object-cover",
        !loaded && "animate-pulse bg-sb-canvas-soft motion-reduce:animate-none",
        className,
      )}
    />
  )
}
