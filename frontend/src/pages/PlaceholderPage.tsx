export function PlaceholderPage({ title }: { title: string }) {
  return (
    <main className="mx-auto max-w-sb-page px-sb-4 py-sb-12 md:px-sb-12">
      <h1 className="text-sb-heading font-medium text-sb-ink">{title}</h1>
      <p className="mt-sb-2 text-sb-body text-sb-ink-mute">이 화면은 준비 중입니다.</p>
    </main>
  )
}
