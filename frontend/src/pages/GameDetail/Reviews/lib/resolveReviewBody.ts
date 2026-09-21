/**
 * 번역/원문 토글에 따라 표시할 본문을 고른다.
 * 번역문은 목록 필드가 아니라 번역 API의 translatedText를 쓴다.
 */
export function resolveReviewBody(
  originalBody: string,
  translatedText: string | undefined,
  showOriginal: boolean,
  isTranslationPending: boolean,
): string {
  if (showOriginal) return originalBody
  if (isTranslationPending || !translatedText) return "번역 중…"
  return translatedText
}
