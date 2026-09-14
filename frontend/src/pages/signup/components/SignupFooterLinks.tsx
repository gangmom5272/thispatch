import { Link } from "react-router"
import { paths } from "@/router/paths"

export function SignupFooterLinks() {
  return (
    <p className="text-sb-caption text-sb-ink-mute">
      이미 계정이 있으신가요?{" "}
      <Link
        to={paths.login}
        className="text-sb-ink underline-offset-2 hover:text-sb-primary-soft hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        로그인
      </Link>
    </p>
  )
}
