import { BrandLogo } from "@/components/layout/BrandLogo"
import { SignupFooterLinks } from "@/pages/signup/components/SignupFooterLinks"
import { SignupForm } from "@/pages/signup/components/SignupForm"
import { SignupPanel } from "@/pages/signup/components/SignupPanel"

export default function SignupPage() {
  return (
    <main className="mx-auto flex max-w-sb-page justify-center px-sb-4 py-sb-12 md:px-sb-12">
      <SignupPanel>
        <h1 className="sr-only">회원가입</h1>
        <BrandLogo size={48} interactive={false} />
        <p className="mt-sb-2 text-left text-sb-body text-sb-ink-mute">
          이메일·비밀번호·닉네임으로 가입합니다. Steam 연동은 지원하지 않습니다.
        </p>
        <div className="mt-sb-6">
          <SignupForm />
        </div>
        <div className="mt-sb-4">
          <SignupFooterLinks />
        </div>
      </SignupPanel>
    </main>
  )
}
