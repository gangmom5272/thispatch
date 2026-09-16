export function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

export function formatDeltaPp(value: number): string {
  const sign = value > 0 ? "+" : value < 0 ? "-" : ""
  return `${sign}${Math.abs(value).toFixed(1)}%p`
}
