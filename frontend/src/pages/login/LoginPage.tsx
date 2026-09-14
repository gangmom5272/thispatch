import { BrandLogo } from "@/components/layout/BrandLogo"
import { LoginFooterLinks } from "@/pages/login/components/LoginFooterLinks"
import { LoginForm } from "@/pages/login/components/LoginForm"
import { LoginPanel } from "@/pages/login/components/LoginPanel"

export default function LoginPage() {
  return (
    <main className="mx-auto flex max-w-sb-page justify-center px-sb-4 py-sb-12 md:px-sb-12">
      <LoginPanel>
        <h1 className="sr-only">로그인</h1>
        <BrandLogo size={48} interactive={false} />
        <p className="mt-sb-2 text-left text-sb-body text-sb-ink-mute">
          이메일과 비밀번호로 ThisPatch에 로그인하세요.
        </p>
        <div className="mt-sb-6">
          <LoginForm />
        </div>
        <div className="mt-sb-4">
          <LoginFooterLinks />
        </div>
      </LoginPanel>
    </main>
  )
}
