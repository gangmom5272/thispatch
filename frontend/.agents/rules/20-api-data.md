---
paths:
  - "src/api/**/*.ts"
  - "src/**/api/**/*.ts"
  - "src/**/queries/**/*.ts"
  - "src/**/mutations/**/*.ts"
  - "src/**/hooks/**/*.ts"
  - "src/**/*.query.ts"
  - "src/**/*.queries.ts"
  - "src/**/*.mutation.ts"
  - "src/**/*.mutations.ts"
---

# API 통신 및 TanStack Query

## 호출 계층

- 새 도메인 API 추가 시 `docs/api-guide.md`의 절차를 따른다.
- API 호출 계층은 `컴포넌트 → Query/Mutation Hook → Options 팩토리 → 도메인 API 함수 → src/api/client.ts` 순서를 따른다.
- 도메인 API 함수만 `src/api/client.ts`의 `api` 인스턴스를 호출할 수 있다.
- 컴포넌트와 페이지에서는 `fetch`, `axios`, `api.get`, `api.post` 또는 도메인 API 함수를 직접 호출하지 않는다.

## Query

- 서버 데이터는 TanStack Query로만 다룬다.
- `useEffect`와 `useState`를 조합해 서버 요청 결과를 보관하지 않는다.
- 조회 요청은 도메인별 `xxxKeys` 팩토리와 `queryOptions` 팩토리로 정의한다.
- Query Key에 영향을 주는 모든 입력값을 Query Key에 포함한다.
- Query Key에는 직렬화 가능한 값만 사용한다.
- Query 함수에서 전달받은 `AbortSignal`을 API 클라이언트까지 전달해 불필요해진 요청을 취소할 수 있도록 한다.
- 동일한 요청의 `staleTime`, `gcTime`, `retry` 정책을 컴포넌트마다 임의로 다르게 지정하지 않고 Options 팩토리에 정의한다.

## Mutation

- 변경 요청은 도메인별 Mutation 함수와 `mutationOptions` 팩토리로 정의한다.
- Mutation 성공 후 서버 응답으로 캐시를 직접 갱신할지, 관련 쿼리를 무효화할지 명확히 결정한다.
- 낙관적 업데이트를 적용한 경우 오류 발생 시 이전 캐시 상태로 롤백한다.
- 인증, 권한, 검증 오류처럼 재시도로 해결되지 않는 오류를 무조건 재시도하지 않는다.
- 요청 중 동일한 작업이 중복 실행되지 않도록 제출 및 상호작용 상태를 제어한다.
