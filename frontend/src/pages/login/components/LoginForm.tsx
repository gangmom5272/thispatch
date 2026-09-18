import { useActionState } from "react"
import { useLocation, useNavigate } from "react-router"
import { isApiError } from "@/api/error"
import Input from "@/components/Input"
import { useLogin } from "@/hooks/queries/authQueries"
import { paths } from "@/router/paths"

export function LoginForm() {
  const navigate = useNavigate()
  const location = useLocation()
  const { mutateAsync } = useLogin()
  // 보호된 페이지에서 넘어온 경우 로그인 후 원래 페이지로 돌아간다.
  const from: unknown = location.state?.from
  const returnTo = typeof from === "string" ? from : paths.games

  const [error, formAction, isPending] = useActionState(
    async (_prev: string | null, formData: FormData) => {
      const email = String(formData.get("email") ?? "")
      const password = String(formData.get("password") ?? "")

      try {
        await mutateAsync({ email, password })
        void navigate(returnTo, { replace: true })
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
        <Input
          name="email"
          type="email"
          autoComplete="email"
          required
          disabled={isPending}
          placeholder="user@example.com"
        />
      </label>
      <label className="flex flex-col gap-sb-2 text-left text-sb-body text-sb-ink">
        비밀번호
        <Input
          name="password"
          type="password"
          autoComplete="current-password"
          required
          disabled={isPending}
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
