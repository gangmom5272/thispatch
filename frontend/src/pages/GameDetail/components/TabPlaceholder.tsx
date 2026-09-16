interface TabPlaceholderProps {
  title: string
}

export function TabPlaceholder({ title }: TabPlaceholderProps) {
  return (
    <div className="mx-auto max-w-sb-page px-sb-4 py-sb-8 md:px-sb-12">
      <h2 className="text-sb-title font-medium text-sb-ink">{title}</h2>
      <p className="mt-sb-2 text-sb-body text-sb-ink-mute">이 화면은 준비 중입니다.</p>
    </div>
  )
}
