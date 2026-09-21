/** Asia/Seoul calendar helpers. */

const SEOUL = "Asia/Seoul"

export function formatSeoulDate(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SEOUL,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ""
  return `${get("year")}-${get("month")}-${get("day")}`
}

export function todaySeoul(): string {
  return formatSeoulDate(new Date())
}

/** Inclusive calendar day delta on YYYY-MM-DD (UTC date math). */
export function addDaysIso(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map(Number)
  const utc = new Date(Date.UTC(y, m - 1, d))
  utc.setUTCDate(utc.getUTCDate() + deltaDays)
  const yy = utc.getUTCFullYear()
  const mm = String(utc.getUTCMonth() + 1).padStart(2, "0")
  const dd = String(utc.getUTCDate()).padStart(2, "0")
  return `${yy}-${mm}-${dd}`
}

export function initialReactionTrendsStartDate(endDate = todaySeoul()): string {
  return addDaysIso(endDate, -41)
}

export function formatDisplayRange(startDate: string, endDate: string, dayCount: number): string {
  return `${startDate} ~ ${endDate} · ${dayCount}일`
}

export function formatShortMd(isoDate: string): string {
  const [, m, d] = isoDate.split("-")
  return `${m}-${d}`
}

export function formatCollectedLabel(iso: string | null): string | null {
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: SEOUL,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ""
  return `수집 ${get("month")}-${get("day")} ${get("hour")}:${get("minute")}`
}

/** ISO 시각을 `YYYY.MM.DD HH:mm`(서울) 표기로 바꾼다. 파싱할 수 없으면 원문을 돌려 준다. */
export function formatSeoulDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SEOUL,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ""
  return `${get("year")}.${get("month")}.${get("day")} ${get("hour")}:${get("minute")}`
}
