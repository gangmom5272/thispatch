import { useActionState } from "react"
import { useNavigate } from "react-router"
import { isApiError } from "@/api/error"
import Input from "@/components/Input"
import { useSignup } from "@/hooks/queries/authQueries"
import { paths } from "@/router/paths"

export function SignupForm() {
  const navigate = useNavigate()
  const { mutateAsync } = useSignup()

  const [error, formAction, isPending] = useActionState(
    async (_prev: string | null, formData: FormData) => {
      const email = String(formData.get("email") ?? "")
      const password = String(formData.get("password") ?? "")
      const nickname = String(formData.get("nickname") ?? "")

      try {
        await mutateAsync({ email, password, nickname })
        void navigate(paths.login)
        return null
      } catch (e) {
        return isApiError(e) ? e.message : "회원가입에 실패했습니다."
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
          autoComplete="new-password"
          required
          disabled={isPending}
          placeholder="비밀번호"
        />
      </label>
      <label className="flex flex-col gap-sb-2 text-left text-sb-body text-sb-ink">
        닉네임
        <Input
          name="nickname"
          type="text"
          autoComplete="nickname"
          required
          disabled={isPending}
          placeholder="닉네임"
        />
      </label>
      {error ? <p className="text-sb-caption text-sb-neg-text">{error}</p> : null}
      <button
        type="submit"
        disabled={isPending}
        className="h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-body text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"
      >
        {isPending ? "가입 중…" : "회원가입"}
      </button>
    </form>
  )
}
