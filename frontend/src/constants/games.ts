import type { GameFilterConditions, GameSort, GameTag } from "../types"

export const DEFAULT_GAME_SORT: GameSort = "REVIEW_COUNT_DESC"

export const DEFAULT_GAME_FILTER: GameFilterConditions = { sort: DEFAULT_GAME_SORT, genreIds: [] }

export const GAME_SORT_OPTIONS: Array<{ value: GameSort; label: string }> = [
  { value: "POSITIVE_RATE_ASC", label: "긍정률 낮은 순" },
  { value: "REVIEW_COUNT_DESC", label: "리뷰 수 많은 순" },
  { value: "REACTION_CHANGE_DESC", label: "반응 변화 큰 순" },
  { value: "RELEASE_DATE_DESC", label: "최신순" },
]

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
