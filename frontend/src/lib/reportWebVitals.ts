import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric } from 'web-vitals'

type ReportHandler = (metric: Metric) => void

export function reportWebVitals(onReport: ReportHandler = console.log) {
  onCLS(onReport)
  onFCP(onReport)
  onINP(onReport)
  onLCP(onReport)
  onTTFB(onReport)
}
