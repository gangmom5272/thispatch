import { Link } from "react-router"

export function LoginFooterLinks() {
  return (
    <p className="text-sb-caption text-sb-ink-mute">
      계정이 없으신가요?{" "}
      <Link
        to="/signup"
        className="text-sb-ink underline-offset-2 hover:text-sb-primary-soft hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary"
      >
        회원가입
      </Link>
    </p>
  )
}
