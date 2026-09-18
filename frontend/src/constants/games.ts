import type { GameFilterConditions, GameRangeFilters, GameSort, GameTag } from "../types"

export const DEFAULT_GAME_SORT: GameSort = "REVIEW_COUNT_DESC"

export const DEFAULT_GAME_FILTER: GameFilterConditions = { sort: DEFAULT_GAME_SORT, genreIds: [] }

export const GAME_SORT_OPTIONS: Array<{ value: GameSort; label: string }> = [
  { value: "REVIEW_COUNT_DESC", label: "리뷰 수 많은 순" },
  { value: "POSITIVE_RATE_ASC", label: "긍정률 낮은 순" },
  { value: "REACTION_CHANGE_DESC", label: "반응 변화 큰 순" },
  { value: "RELEASE_DATE_DESC", label: "최신순" },
]

export type GameRangeKey = Exclude<keyof GameRangeFilters, "developer">

export interface GameRangeFilterGroup {
  label: string
  from: GameRangeKey
  to: GameRangeKey
  min: number
  max: number
  unit: string
}

/** 숫자 범위 필터 그룹. URL·다이얼로그·적용 칩이 같은 정의를 공유한다. */
export const GAME_RANGE_FILTERS: GameRangeFilterGroup[] = [
  {
    label: "출시연도",
    from: "releaseYearFrom",
    to: "releaseYearTo",
    min: 1,
    max: 9999,
    unit: "년",
  },
  {
    label: "리뷰 수",
    from: "minReviewCount",
    to: "maxReviewCount",
    min: 0,
    max: 2147483647,
    unit: "개",
  },
  { label: "긍정률", from: "minPositiveRate", to: "maxPositiveRate", min: 0, max: 100, unit: "%" },
]

/** "2020~2025년", "1,000개 이상", "80% 이하" 형태의 범위 설명. 둘 다 없으면 null */
export function formatGameRange(
  group: GameRangeFilterGroup,
  from: number | undefined,
  to: number | undefined,
): string | null {
  const fmt = (n: number) => (group.unit === "년" ? String(n) : n.toLocaleString("en-US"))
  if (from !== undefined && to !== undefined) {
    return from === to ? `${fmt(from)}${group.unit}` : `${fmt(from)}~${fmt(to)}${group.unit}`
  }
  if (from !== undefined) return `${fmt(from)}${group.unit} 이상`
  if (to !== undefined) return `${fmt(to)}${group.unit} 이하`
  return null
}

export function isGameSort(value: string | null): value is GameSort {
  return GAME_SORT_OPTIONS.some((option) => option.value === value)
}

export const GAME_GENRES: GameTag[] = [
  { id: 1, name: "로그라이크" },
  { id: 2, name: "액션" },
  { id: 3, name: "RPG" },
  { id: 4, name: "전략" },
  { id: 5, name: "시뮬레이션" },
  { id: 6, name: "어드벤처" },
  { id: 7, name: "생존" },
  { id: 8, name: "인디" },
  // 카드 태그 2줄 클램프 QA용 — 다수 태그 목 게임에 사용
  { id: 9, name: "오픈월드" },
  { id: 10, name: "멀티플레이" },
  { id: 11, name: "스토리 중심" },
  { id: 12, name: "협동" },
  { id: 13, name: "샌드박스" },
  { id: 14, name: "탐험" },
]
