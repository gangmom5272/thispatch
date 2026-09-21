import { Link } from "react-router"
import { isApiError } from "@/api/error"
import { translationErrorMessage } from "@/lib/translationError"
import { paths } from "@/router/paths"

const linkClass =
  "rounded-sb-tag text-sb-primary underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"

interface TranslationErrorNoticeProps {
  error: unknown
  className?: string
}

/**
 * 번역 API 실패 안내. 401이면 로그인 CTA, 그 외는 상태별 문구만 보여 준다.
 */
export function TranslationErrorNotice({ error, className }: TranslationErrorNoticeProps) {
  // status: ApiError면 HTTP 코드, 아니면 0(일반 실패 문구)
  const status = isApiError(error) ? error.status : 0

  return (
    <div role="alert" className={className ?? "flex flex-col gap-sb-2"}>
      <p className="text-sb-body text-sb-neg-text">{translationErrorMessage(status)}</p>
      {status === 401 ? (
        <Link to={paths.login} className={`self-start text-sb-body ${linkClass}`}>
          로그인하기
        </Link>
      ) : null}
    </div>
  )
}
