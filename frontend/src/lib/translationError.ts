/** HTTP 상태별 번역 실패 안내 문구. */
export function translationErrorMessage(status: number): string {
  if (status === 401) return "번역을 보려면 로그인이 필요합니다."
  if (status === 404) return "번역 대상을 찾을 수 없습니다."
  if (status === 502) return "번역 서비스에 일시적으로 문제가 있습니다. 잠시 후 다시 시도해 주세요."
  return "번역을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
}
