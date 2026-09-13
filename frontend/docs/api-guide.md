# API 통신 가이드

서버 통신 코드를 작성할 때 반드시 이 가이드를 따른다. 설계 배경은 [api-module-plan.md](./api-module-plan.md) 참고.

## 1. 아키텍처

```
pages/components → hooks/queries → api/도메인 함수 → api/client → axios
```

| 계층 | 위치 | 책임 | 금지 |
| --- | --- | --- | --- |
| 통신 | `src/api/client.ts` | axios 인스턴스, 인터셉터, envelope unwrap, fetcher(`api.get` 등) | `client` 인스턴스 export |
| 도메인 API | `src/api/<도메인>.ts` | 엔드포인트 1:1 함수, DTO 타입 | TanStack Query import |
| 서버 상태 | `src/hooks/queries/<도메인>Queries.ts` | 키 팩토리, `queryOptions` 팩토리, 커스텀 훅 | axios·fetcher 직접 호출 |
| UI | `src/pages`, `src/components` | 훅 사용, `isPending`/`isError` 분기 | `api/` 직접 import |

백엔드 공통 응답 envelope(`{ code, message, responseAt, data, success }`)는 `client.ts`가 벗겨서 반환하므로, 도메인 함수부터는 `data` 내용물 타입만 다룬다. 실패는 HTTP 상태와 무관하게 `success: false` 기준으로 판정되어 `ApiError`로 던져진다.

## 2. 새 도메인 API 추가하는 법

### ① `src/api/<도메인>.ts` — API 함수

```ts
import { api } from "@/api/client";
import type { Reservation } from "@/types";

interface ReservationFilters {
  status?: "예약 확정" | "예약 대기";
  direction?: "sent" | "received";
}

export function getReservations(
  filters: ReservationFilters,
  signal?: AbortSignal,
): Promise<Reservation[]> {
  return api.get<Reservation[]>({
    path: "/reservations",
    config: { params: filters, signal },
  });
}

export function cancelReservation(id: string): Promise<void> {
  return api.delete<void>({ path: `/reservations/${id}` });
}
```

fetcher는 위치 인자가 아니라 **단일 객체 인자**를 받는다: `api.get<T>({ path, headers?, config? })`, `api.post<T>({ path, body?, headers?, config? })`. `headers`는 엔드포인트 전용 헤더가 필요할 때만 넘기고, `config`에는 `params`(쿼리스트링)·`signal`만 넘길 수 있다.

- 함수명은 동사로 시작: `getXxx` / `createXxx` / `updateXxx` / `deleteXxx` / `toggleXxx`.
- 파라미터는 axios config 모양(`params?: {...}`, `headers?: {...}`)이 아니라 **도메인 타입 인자**로 받는다. 그 값이 쿼리스트링(`params`)으로 가는지 body로 가는지는 함수 내부에서 매핑한다 — 호출부는 통신 세부사항을 몰라야 한다.
- 조회 함수는 마지막 인자로 `signal?: AbortSignal`을 받아 fetcher에 그대로 전달한다 (TanStack Query가 언마운트·쿼리 키 변경 시 네트워크 요청을 자동 취소).
- DTO 타입은 함수 위에 정의하고, 두 파일 이상에서 쓰이면 `src/types.ts`로 승격.
- `data`가 없는 성공 응답은 `api.delete<void>({ path })`처럼 `T = void`로 호출.

#### 헤더 지정 규칙

헤더는 호출부(훅·컴포넌트)로 노출하지 않는다. 두 가지 경우만 있다:

1. **공통 헤더** (Authorization, Content-Type 등): `client.ts` 인터셉터 책임. 도메인 함수는 신경 쓰지 않는다.
2. **특정 엔드포인트 전용 헤더**: 그 도메인 함수 **내부에서** fetcher의 `headers` 인자로 지정한다.

```ts
export function uploadPropertyImage(
  propertyId: number,
  file: File,
): Promise<string> {
  const formData = new FormData();
  formData.append("image", file);
  return api.post<string>({
    path: `/properties/${propertyId}/images`,
    body: formData,
    headers: { "Content-Type": "multipart/form-data" },
  });
}

export function reissuedToken(refreshToken: string): Promise<Token> {
  return api.post<Token>({
    path: "/auth/refresh",
    headers: { RefreshToken: refreshToken },
  });
}
```

(참고: body가 `FormData`면 axios가 `multipart/form-data`를 자동 설정하므로 위 헤더는 생략해도 된다. 백엔드가 요구하는 커스텀 헤더 등 정말 필요한 경우에만 같은 방식으로 지정한다.)

### ② `src/hooks/queries/<도메인>Queries.ts` — 키·옵션·훅

```ts
import { queryOptions, useQuery } from "@tanstack/react-query";

import { getReservations, type ReservationFilters } from "@/api/reservation";

export const reservationKeys = {
  all: ["reservations"] as const,
  lists: () => [...reservationKeys.all, "list"] as const,
  list: (filters: ReservationFilters) =>
    [...reservationKeys.lists(), filters] as const,
};

export const reservationListOptions = (filters: ReservationFilters) =>
  queryOptions({
    queryKey: reservationKeys.list(filters),
    queryFn: ({ signal }) => getReservations(filters, signal),
  });

export function useReservationList(filters: ReservationFilters) {
  return useQuery(reservationListOptions(filters));
}
```

- 쿼리 키는 반드시 `xxxKeys` 팩토리로만 생성. 문자열 하드코딩 금지.
- 무효화도 팩토리 경유: `queryClient.invalidateQueries({ queryKey: reservationKeys.lists() })`.
- `queryOptions` 팩토리로 정의하면 훅·prefetch·`ensureQueryData`·`setQueryData`에서 같은 정의를 재사용할 수 있다.

### ③ 컴포넌트 — 훅 사용

```tsx
function ReservationList({ filters }: { filters: ReservationFilters }) {
  const { data, isPending, isError, refetch } = useReservationList(filters);

  if (isPending) return <ReservationSkeleton />;
  if (isError) return <QueryErrorFallback retry={refetch} />;
  return <ul>{data.map(/* ... */)}</ul>;
}
```

## 3. 네이밍 컨벤션

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| API 함수 | 동사 시작 camelCase | `getProperties`, `toggleSaved` |
| 쿼리 키 팩토리 | `<도메인>Keys` | `propertyKeys.list(filters)` |
| queryOptions 팩토리 | `<대상>Options` | `propertyListOptions(filters)` |
| 쿼리 훅 | `use<대상>` | `usePropertyList(filters)` |
| 뮤테이션 훅 | `use<동사><대상>` | `useToggleSaved`, `useUpdateProfile` |

## 4. 에러 처리

- 모든 통신 실패는 `ApiError(status, code, message)`로 정규화된다 (`src/api/error.ts`).
  - `status`: HTTP 상태코드 (네트워크 단절·타임아웃은 `0`)
  - `code`: envelope의 `code` 문자열
  - `message`: 백엔드가 준 사용자 노출용 문구
- 분기가 필요하면 `isApiError(error)` 타입 가드를 사용한다.
- 쿼리: 컴포넌트는 `isPending`/`isError`/`error`만 보고, 에러 시 재시도 가능한 폴백 UI를 렌더한다.
- 뮤테이션 + 폼: `useActionState`의 error 반환값으로 인라인 표시. 사용자 입력값이 날아가지 않게 한다.

```tsx
const { mutateAsync } = useUpdateProfile();
const [error, formAction, isPending] = useActionState(
  async (_prev: string | null, formData: FormData) => {
    try {
      await mutateAsync(toChanges(formData));
      return null;
    } catch (e) {
      return isApiError(e) ? e.message : "저장에 실패했습니다.";
    }
  },
  null,
);
```

- 4xx는 자동 재시도하지 않고, 5xx·네트워크 오류만 최대 2회 재시도한다 (`queryClient.ts` 전역 설정).
- `console.log`로 에러를 흘리지 않는다. 디버깅은 React Query Devtools 사용.

## 5. 낙관적 업데이트 (찜하기 토글 등)

서버 캐시 안에 사는 값의 선반영은 `useOptimistic`(캐시 밖 로컬 UI 값 전용)이 아니라 TanStack Query 캐시 계층에서 처리한다. `onMutate` 스냅샷 → `onError` 롤백 → `onSettled` 무효화:

```ts
export function useToggleSaved() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, saved }: { id: number; saved: boolean }) =>
      toggleSaved(id, saved),
    onMutate: async ({ id, saved }) => {
      await queryClient.cancelQueries({ queryKey: propertyKeys.all });
      const previous = queryClient.getQueriesData({ queryKey: propertyKeys.all });
      queryClient.setQueriesData<Property[]>(
        { queryKey: propertyKeys.lists() },
        (old) => old?.map((p) => (p.id === id ? { ...p, saved } : p)),
      );
      return { previous };
    },
    onError: (_error, _variables, context) => {
      context?.previous.forEach(([queryKey, data]) =>
        queryClient.setQueryData(queryKey, data),
      );
    },
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: propertyKeys.all }),
  });
}
```

## 6. 금지 사항

- 컴포넌트·페이지에서 `api/` 도메인 함수나 axios 직접 호출 금지 — 항상 `hooks/queries`의 훅 경유
- `client.ts`의 axios 인스턴스 export 금지 (인터셉터 우회 방지)
- 쿼리 키 문자열 하드코딩 금지 — `xxxKeys` 팩토리만 사용
- `useEffect` + `useState`로 서버 데이터 보관 금지 — 서버 상태는 TanStack Query가 소유
- envelope 타입(`ApiResponse`)을 통신 계층 밖에서 참조 금지

## 7. 미확정 사항 (TBD)

- accessToken·refreshToken 보관 위치 → 확정 시 `client.ts`의 요청 인터셉터·401 refresh single-flight 주석 해제 및 구현
- refresh 엔드포인트 경로·요청 형식
- 백엔드 미완성 엔드포인트는 도메인 함수 본문이 목데이터를 반환 중 — 함수 위 TODO 주석의 실제 호출로 교체하면 됨
