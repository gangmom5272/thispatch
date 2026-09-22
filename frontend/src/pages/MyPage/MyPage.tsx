import { useActionState, useState, type ReactNode } from "react"
import { isApiError } from "@/api/error"
import Input from "@/components/Input"
import { useUpdateNickname, useUpdatePassword } from "@/hooks/queries/memberQueries"
import { useSession } from "@/hooks/queries/sessionQueries"
import WithdrawDialog from "./components/WithdrawDialog"

const NICKNAME_MAX_LENGTH = 20

type FormResult = { status: "idle" } | { status: "success" | "error"; message: string }

const IDLE: FormResult = { status: "idle" }

const SECTIONS = [
  { id: "profile", label: "프로필" },
  { id: "password", label: "비밀번호 변경" },
  { id: "withdraw", label: "회원 탈퇴" },
] as const

const primaryButtonClass =
  "h-sb-control cursor-pointer self-end rounded-sb-control bg-sb-primary px-sb-4 font-medium text-sb-on-primary hover:bg-sb-primary-soft active:bg-sb-primary-deep focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary disabled:cursor-not-allowed disabled:opacity-50"

/** 회원 정보 수정(닉네임·비밀번호)과 회원 탈퇴. 로그인이 필요하다. */
export default function MyPage() {
  const session = useSession()

  const user = session.data?.user ?? null

  return (
    <main className="mx-auto flex max-w-sb-page flex-col gap-sb-8 px-sb-4 py-sb-6 md:px-sb-12">
      <div className="flex flex-col gap-sb-2">
        <h1 className="text-sb-section font-medium md:text-sb-display">마이페이지</h1>
        <p className="text-sb-lead text-sb-ink-mute">
          닉네임과 비밀번호를 바꾸거나 계정을 정리할 수 있습니다. 변경 사항은 저장 버튼을 눌러야
          반영됩니다.
        </p>
      </div>

      <div className="flex items-start gap-sb-12">
        <nav aria-label="마이페이지 섹션" className="hidden w-sb-sidebar shrink-0 md:block">
          <ul className="flex flex-col gap-sb-1">
            {SECTIONS.map((section) => (
              <li key={section.id}>
                <a
                  href={`#${section.id}`}
                  className="block rounded-sb-control border-l-2 border-transparent px-sb-3 py-sb-2 text-sb-ink-mute hover:text-sb-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
                >
                  {section.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        {user ? (
          <div className="flex w-full max-w-3xl flex-col gap-sb-6">
            <NicknameSection nickname={user.nickname} />
            <PasswordSection />
            <WithdrawSection nickname={user.nickname} />
          </div>
        ) : (
          <div
            aria-busy="true"
            aria-label="회원 정보 불러오는 중"
            className="h-96 w-full max-w-3xl animate-pulse rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface motion-reduce:animate-none"
          />
        )}
      </div>
    </main>
  )
}

function SectionCard({
  id,
  title,
  description,
  danger = false,
  children,
}: {
  id: string
  title: string
  description: string
  danger?: boolean
  children: ReactNode
}) {
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className={`flex scroll-mt-sb-6 flex-col gap-sb-5 rounded-sb-card border bg-sb-canvas-surface p-sb-6 ${danger ? "border-sb-line-red" : "border-sb-hairline-cool"}`}
    >
      <div className="flex flex-col gap-sb-1">
        <h2
          id={`${id}-title`}
          className={`text-sb-title font-medium ${danger ? "text-sb-neg-text" : ""}`}
        >
          {title}
        </h2>
        <p className="text-sb-ink-mute">{description}</p>
      </div>
      {children}
    </section>
  )
}

function ResultMessage({ result }: { result: FormResult }) {
  if (result.status === "idle") return null
  return (
    <p
      role={result.status === "error" ? "alert" : "status"}
      className={`text-sb-caption ${result.status === "error" ? "text-sb-neg-text" : "text-sb-pos-text"}`}
    >
      {result.message}
    </p>
  )
}

function Field({
  label,
  helper,
  children,
}: {
  label: string
  helper?: string
  children: ReactNode
}) {
  return (
    <label className="flex flex-col gap-sb-2 text-sb-caption text-sb-ink-mute">
      {label}
      {children}
      {helper && <span className="text-sb-ink-mute-2">{helper}</span>}
    </label>
  )
}

function NicknameSection({ nickname }: { nickname: string }) {
  const { mutateAsync } = useUpdateNickname()

  const [result, formAction, isPending] = useActionState(
    async (_prev: FormResult, formData: FormData): Promise<FormResult> => {
      const next = String(formData.get("nickname") ?? "")
      if (!next.trim()) {
        return { status: "error", message: "닉네임은 공백만으로 설정할 수 없습니다." }
      }
      if (next === nickname) {
        return { status: "error", message: "현재 닉네임과 같습니다. 다른 닉네임을 입력해 주세요." }
      }
      try {
        await mutateAsync({ nickname: next })
        return { status: "success", message: "닉네임을 변경했습니다." }
      } catch (e) {
        return {
          status: "error",
          message: isApiError(e) ? e.message : "닉네임을 변경하지 못했습니다. 다시 시도해 주세요.",
        }
      }
    },
    IDLE,
  )

  return (
    <SectionCard id="profile" title="프로필" description="다른 사용자에게 보이는 이름입니다.">
      <form action={formAction} className="flex flex-col gap-sb-4">
        <Field
          label="닉네임"
          helper={`1~${NICKNAME_MAX_LENGTH}자 · 공백만으로는 설정할 수 없습니다.`}
        >
          <Input
            name="nickname"
            type="text"
            autoComplete="nickname"
            required
            maxLength={NICKNAME_MAX_LENGTH}
            defaultValue={nickname}
            disabled={isPending}
            className="bg-sb-canvas-soft text-sb-body"
          />
        </Field>
        <ResultMessage result={result} />
        <button type="submit" disabled={isPending} className={primaryButtonClass}>
          {isPending ? "저장 중…" : "닉네임 저장"}
        </button>
      </form>
    </SectionCard>
  )
}

function PasswordSection() {
  const { mutateAsync } = useUpdatePassword()

  const [result, formAction, isPending] = useActionState(
    async (_prev: FormResult, formData: FormData): Promise<FormResult> => {
      const currentPassword = String(formData.get("currentPassword") ?? "")
      const newPassword = String(formData.get("newPassword") ?? "")
      const confirmPassword = String(formData.get("confirmPassword") ?? "")
      if (!newPassword.trim()) {
        return { status: "error", message: "새 비밀번호는 공백만으로 설정할 수 없습니다." }
      }
      if (newPassword !== confirmPassword) {
        return { status: "error", message: "새 비밀번호 확인이 일치하지 않습니다." }
      }
      if (newPassword === currentPassword) {
        return { status: "error", message: "현재 비밀번호와 다른 비밀번호를 입력해 주세요." }
      }
      try {
        await mutateAsync({ currentPassword, newPassword })
        return {
          status: "success",
          message: "비밀번호를 변경했습니다. 다른 기기에서는 다시 로그인해야 합니다.",
        }
      } catch (e) {
        return {
          status: "error",
          message: isApiError(e)
            ? e.message
            : "비밀번호를 변경하지 못했습니다. 다시 시도해 주세요.",
        }
      }
    },
    IDLE,
  )

  const inputClass = "bg-sb-canvas-soft text-sb-body"

  return (
    <SectionCard
      id="password"
      title="비밀번호 변경"
      description="변경하면 다른 기기에서는 다시 로그인해야 합니다."
    >
      <form action={formAction} className="flex flex-col gap-sb-4">
        <Field label="현재 비밀번호">
          <Input
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            disabled={isPending}
            className={inputClass}
          />
        </Field>
        <Field label="새 비밀번호" helper="공백만으로는 설정할 수 없습니다.">
          <Input
            name="newPassword"
            type="password"
            autoComplete="new-password"
            required
            disabled={isPending}
            className={inputClass}
          />
        </Field>
        <Field label="새 비밀번호 확인">
          <Input
            name="confirmPassword"
            type="password"
            autoComplete="new-password"
            required
            disabled={isPending}
            placeholder="새 비밀번호를 한 번 더 입력"
            className={inputClass}
          />
        </Field>
        <ResultMessage result={result} />
        <button type="submit" disabled={isPending} className={primaryButtonClass}>
          {isPending ? "변경 중…" : "비밀번호 변경"}
        </button>
      </form>
    </SectionCard>
  )
}

function WithdrawSection({ nickname }: { nickname: string }) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <SectionCard
      id="withdraw"
      title="회원 탈퇴"
      description="탈퇴하면 계정이 비활성화되어 내 게임과 기획안 내역에 접근할 수 없고, 같은 이메일로 다시 가입할 수 없습니다."
      danger
    >
      <div className="flex items-center justify-between gap-sb-3">
        <p className="text-sb-caption text-sb-ink-mute-2">
          탈퇴 전 확인 절차가 한 번 더 진행됩니다.
        </p>
        <button
          type="button"
          onClick={() => setIsOpen(true)}
          className="h-sb-control shrink-0 cursor-pointer rounded-sb-control border border-sb-line-red bg-sb-tint-red px-sb-4 text-sb-neg-text hover:border-sb-neg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
        >
          회원 탈퇴
        </button>
      </div>
      {isOpen && <WithdrawDialog nickname={nickname} onClose={() => setIsOpen(false)} />}
    </SectionCard>
  )
}
