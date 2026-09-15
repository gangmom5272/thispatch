---
paths:
  - "src/**/*.ts"
  - "src/**/*.tsx"
  - "src/**/*.css"
  - "index.html"
  - "vite.config.ts"
  - "vite.config.js"
  - "tailwind.config.ts"
  - "tailwind.config.js"
---

# 프론트엔드 기본 규칙

- UI 구현 전에 `docs/frontend-spec.md`를 읽고 기능, 상태, 문구, 화면 흐름을 확인한다.
- 코드 작성, 수정, 리뷰 전에 `docs/frontend-code-quality.md`와 `docs/frontend-clean-code-guide.md`의 관련 기준을 확인한다.
- 명세에 없는 사용자 기능은 추가하지 않는다.
- 기존 프로젝트의 디렉토리 구조, 컴포넌트 패턴, 명명 규칙을 우선 따른다.
- 스타일링은 Tailwind CSS 유틸리티와 Tailwind 테마 변수만 사용한다.
- 컴포넌트와 페이지에서 `fetch`, `axios`, `api.get`, `api.post` 또는 도메인 API 함수를 직접 호출하지 않는다.
- 서버 데이터는 TanStack Query로 관리하고, `useEffect`와 `useState`에 요청 결과를 저장하지 않는다.
- 요청 범위와 관계없는 코드 정리나 리팩터링을 함께 수행하지 않는다.
