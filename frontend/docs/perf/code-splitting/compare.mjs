import fs from "node:fs"
const variants = ["before", "lazy", "static"]
const metric = (r) => {
  const a = r.audits
  const js = a["network-requests"].details.items.filter((i) => i.resourceType === "Script")
  return {
    perf: r.categories.performance.score * 100,
    FCP: a["first-contentful-paint"].numericValue,
    LCP: a["largest-contentful-paint"].numericValue,
    TBT: a["total-blocking-time"].numericValue,
    SI: a["speed-index"].numericValue,
    JS: js.reduce((s, i) => s + i.transferSize, 0) / 1024,
    unusedJS:
      (a["unused-javascript"].details?.items ?? []).reduce((s, i) => s + i.wastedBytes, 0) / 1024,
    mainThread: a["mainthread-work-breakdown"].numericValue,
    bootup: a["bootup-time"].numericValue,
  }
}
const med = (v) => {
  const s = [...v].sort((a, b) => a - b)
  return s[Math.floor(s.length / 2)]
}
for (const env of ["desktop", "mobile"])
  for (const page of ["/", "/login"]) {
    console.log(`\n### ${env} ${page}`)
    const data = Object.fromEntries(
      variants.map((v) => {
        const dir = `${v}/${env}`
        const rs = fs.existsSync(dir)
          ? fs
              .readdirSync(dir)
              .filter((f) => f.endsWith(".json"))
              .map((f) => JSON.parse(fs.readFileSync(dir + "/" + f)))
              .filter((r) => new URL(r.finalDisplayedUrl).pathname === page)
              .map(metric)
          : []
        return [v, rs]
      }),
    )
    console.log(
      "지표".padEnd(11) + variants.map((v) => `${v}(n=${data[v].length})`.padStart(16)).join(""),
    )
    for (const k of Object.keys(metric.length ? {} : {}).length
      ? []
      : ["perf", "FCP", "LCP", "TBT", "SI", "JS", "unusedJS", "mainThread", "bootup"])
      console.log(
        k.padEnd(11) +
          variants
            .map((v) =>
              (data[v].length
                ? med(data[v].map((r) => r[k])).toFixed(k === "JS" || k === "unusedJS" ? 1 : 0)
                : "-"
              ).padStart(16),
            )
            .join(""),
      )
  }
