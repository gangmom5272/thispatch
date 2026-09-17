import type { PlaytimeBandStats } from "@/types/statistics"

/** 분 단위 플레이타임을 한국어 라벨로 바꾼다. */
export function formatPlaytimeMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes}분`
  // hours: 60분 단위 환산값
  const hours = minutes / 60
  if (Number.isInteger(hours)) return `${hours}시간`
  const rounded = Math.round(hours * 10) / 10
  return `${rounded}시간`
}

/** 밴드 min~max를 "0 – 2시간" / "100시간 이상" 형태로 만든다. */
export function formatBandRangeLabel(band: PlaytimeBandStats): string {
  const start = formatPlaytimeMinutes(band.minMinutes)
  if (band.maxMinutesExclusive === null) {
    return `${start} 이상`
  }
  const end = formatPlaytimeMinutes(band.maxMinutesExclusive)
  return `${start} – ${end}`
}

/** bandId(B1~B4)를 API bandNo(1~4)로 변환. ALL/unknown은 null. */
export function bandIdToBandNo(band: string): number | null {
  const match = /^B([1-4])$/.exec(band)
  return match ? Number(match[1]) : null
}

/** 긍정률 표시용. null이면 대시. */
export function formatPositiveRate(rate: number | null): string {
  if (rate === null) return "—"
  return `${rate.toFixed(1)}%`
}
