import { useEffect, useRef, useState, type FormEvent } from "react"
import { useNavigate } from "react-router"
import { isApiError } from "@/api/error"
import Input from "@/components/Input"
import { useWithdrawMember } from "@/hooks/queries/memberQueries"
import { useLogout } from "@/hooks/queries/sessionQueries"
import { paths } from "@/router/paths"

const CONFIRM_PHRASE = "탈퇴합니다"

/** 회원 탈퇴 확인 모달. 확인 문구를 입력해야 탈퇴 버튼이 활성화된다. */
export default function WithdrawDialog({
  nickname,
  onClose,
}: {
  nickname: string
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [phrase, setPhrase] = useState("")
  const navigate = useNavigate()
  const logout = useLogout()
  const { mutateAsync, isPending, error } = useWithdrawMember()

  useEffect(() => {
    dialogRef.current?.showModal()
  }, [])

  const close = () => dialogRef.current?.close()
  const confirmed = phrase.trim() === CONFIRM_PHRASE

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!confirmed || isPending) return
    try {
      await mutateAsync()
      void navigate(paths.home, { replace: true })
      logout()
    } catch {
      // 오류는 mutation의 error로 표시한다.
    }
  }

  return (
    <dialog
      ref={dialogRef}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget && !isPending) close()
      }}
      aria-labelledby="withdraw-title"
      className="m-auto w-full max-w-lg rounded-sb-modal border border-sb-hairline-strong bg-sb-canvas p-0 font-sb-sans text-sb-body text-sb-ink shadow-sb-modal backdrop:bg-sb-scrim"
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-sb-5 p-sb-6">
        <div className="flex flex-col gap-sb-2">
          <h2 id="withdraw-title" className="text-sb-title font-medium">
            정말 탈퇴하시겠어요?
          </h2>
          <p className="text-sb-ink-mute">
            {nickname} 계정이 즉시 비활성화되며 되돌릴 수 없습니다.
          </p>
        </div>

        <ul className="flex flex-col gap-sb-2 rounded-sb-control border border-sb-line-red bg-sb-tint-red px-sb-4 py-sb-3">
          {[
            "내 게임 목록과 진단 결과에 더 이상 접근할 수 없습니다.",
            "기획안 내역과 유사 사례 검색 기록을 볼 수 없습니다.",
            "같은 이메일로는 다시 가입할 수 없습니다.",
          ].map((text) => (
            <li key={text} className="flex items-start gap-sb-2">
              <span aria-hidden="true" className="text-sb-neg-text">
                ×
              </span>
              {text}
            </li>
          ))}
        </ul>

        <label className="flex flex-col gap-sb-2 text-sb-caption text-sb-ink-mute">
          계속하려면 &ldquo;{CONFIRM_PHRASE}&rdquo;라고 입력하세요.
          <Input
            value={phrase}
            onChange={(event) => setPhrase(event.target.value)}
            disabled={isPending}
            autoComplete="off"
            placeholder={CONFIRM_PHRASE}
            className="bg-sb-canvas-soft text-sb-body"
          />
        </label>

        {error && (
          <p role="alert" className="text-sb-caption text-sb-neg-text">
            {isApiError(error) ? error.message : "탈퇴 처리에 실패했습니다. 다시 시도해 주세요."}
          </p>
        )}

        <div className="flex justify-end gap-sb-3">
          <button
            type="button"
            onClick={close}
            disabled={isPending}
            className="h-sb-control cursor-pointer rounded-sb-control border border-sb-hairline-strong bg-sb-canvas-soft px-sb-4 text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="submit"
            disabled={!confirmed || isPending}
            className="h-sb-control cursor-pointer rounded-sb-control bg-sb-neg px-sb-4 font-medium text-sb-on-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isPending ? "탈퇴 처리 중…" : "탈퇴하기"}
          </button>
        </div>
      </form>
    </dialog>
  )
}
