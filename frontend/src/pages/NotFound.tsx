import { Link } from "react-router"
import { paths } from "@/router/paths"

export default function NotFound() {
  return (
    <section className="flex flex-col items-center justify-center gap-4 py-24 text-center">
      <h1 className="text-4xl font-semibold">404</h1>
      <p className="text-(--text)">페이지를 찾을 수 없습니다.</p>
      <Link to={paths.home} className="text-(--accent) underline">
        홈으로 돌아가기
      </Link>
    </section>
  )
}
