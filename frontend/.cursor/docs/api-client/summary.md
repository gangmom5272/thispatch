# [S15P21A202-90] API 클라이언트·토큰 인터셉터

## 이슈

| 항목      | 내용                                                              |
| --------- | ----------------------------------------------------------------- |
| 하위 작업 | [S15P21A202-90](https://ssafy.atlassian.net/browse/S15P21A202-90) |
| 스토리    | [S15P21A202-75](https://ssafy.atlassian.net/browse/S15P21A202-75) |
| 브랜치    | `S15P21A202-90-fe-api-클라이언트-토큰-인터셉터`                   |

## 이미 -88에서 들어온 것

- `src/api/client.ts`: envelope unwrap, Bearer 요청 인터셉터, 401 refresh single-flight
- `src/lib/tokenStorage.ts`: access/refresh localStorage
- `src/api/session.ts` + `useSession`: GET `/session`
- `src/api/auth.ts`: login / signup / refresh — body `{ refreshToken }`
- MSW: session / login / signup / refresh

재구현·헤더/라우트 변경은 하지 않았다.

## 이번 보완점

1. **`client.ts`**
   - `skipAuthRefresh: true`인 요청에는 Authorization을 붙이지 않음 (만료 access를 refresh에 싣지 않음)
   - `skipAuthRefresh` / `_retry`를 axios `AxiosRequestConfig` 모듈 확장으로 정리 (캐스트 제거)
2. **`docs/api-guide.md`**
   - §7 TBD 닫음: 보관=`tokenStorage`(localStorage), refresh=`POST /auth/refresh` + body `{ refreshToken }`, single-flight·세션 계약 명시
   - §2 `reissuedToken` 예시를 RefreshToken **헤더** → body 방식으로 수정

## Refresh 계약

| 항목   | 값                                           |
| ------ | -------------------------------------------- |
| 경로   | `POST /auth/refresh`                         |
| 요청   | body `{ refreshToken }` (Authorization 없음) |
| 성공   | envelope `data.accessToken`                  |
| 실패   | `clearTokens()` 후 원요청 401 전파           |
| 동시성 | single-flight (`refreshPromise`)             |

## 검증

- `pnpm lint` — 통과
- `pnpm build` — 통과

## 제외 (의도적)

- 로그인/가입 UI, 라우트, AppShell
- `App.tsx` health의 `apiClient` 직접 사용
- 새 패키지 설치
