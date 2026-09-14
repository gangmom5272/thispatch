import { useActionState } from "react"
import { useNavigate } from "react-router"
import { isApiError } from "@/api/error"
import { useLogin } from "@/hooks/queries/authQueries"

export function LoginForm() {
  const navigate = useNavigate()
  const { mutateAsync } = useLogin()

  const [error, formAction, isPending] = useActionState(
    async (_prev: string | null, formData: FormData) => {
      const email = String(formData.get("email") ?? "")
      const password = String(formData.get("password") ?? "")

      try {
        await mutateAsync({ email, password })
        void navigate("/")
        return null
      } catch (e) {
        return isApiError(e) ? e.message : "로그인에 실패했습니다."
      }
    },
    null,
  )

  return (
    <form action={formAction} className="flex flex-col gap-sb-4 text-left">
      <label className="flex flex-col gap-sb-2 text-left text-sb-body text-sb-ink">
        이메일
        <input
          name="email"
          type="email"
          autoComplete="email"
          required
          disabled={isPending}
          className="h-sb-control rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-ink placeholder:text-sb-ink-mute-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          placeholder="user@example.com"
        />
      </label>
      <label className="flex flex-col gap-sb-2 text-left text-sb-body text-sb-ink">
        비밀번호
        <input
          name="password"
          type="password"
          autoComplete="current-password"
          required
          disabled={isPending}
          className="h-sb-control rounded-sb-control border border-sb-hairline-strong bg-sb-canvas px-sb-3 text-sb-ink placeholder:text-sb-ink-mute-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
          placeholder="비밀번호"
        />
      </label>
      {error ? <p className="text-sb-caption text-sb-neg-text">{error}</p> : null}
      <button
        type="submit"
        disabled={isPending}
        className="h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-body text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
      >
        {isPending ? "로그인 중…" : "로그인"}
      </button>
    </form>
  )
}
