# API Index

이 디렉터리는 백엔드 구현·검증의 기준이다. 공통 규칙: [conventions.md](conventions.md)

## Endpoints

| Domain | Document | Endpoints | 화면/기능 |
|---|---|---|---|
| member | [member.md](member.md) | `GET /session` | 로그인 상태 헤더 |
| member | [member.md](member.md) | `POST /auth/login` | 로그인 |
| member | [member.md](member.md) | `POST /auth/signup` | 회원가입 |
| member | [member.md](member.md) | `GET /auth/steam/login` | Steam 로그인 시작 |
| member | [member.md](member.md) | `GET /auth/steam/callback` | Steam 인증·신규 회원 생성·로그인 코드 발급 |
| member | [member.md](member.md) | `POST /auth/steam/token` | Steam 로그인 토큰 교환 |
| member | [member.md](member.md) | `POST /auth/steam/signup` | Steam 최초 닉네임 설정 (인증 필수) |
| member | [member.md](member.md) | `PATCH /members/me/nickname` | 마이페이지 닉네임 변경 (인증 필수) |
| member | [member.md](member.md) | `PATCH /members/me/password` | 마이페이지 비밀번호 변경 (LOCAL 회원, 인증 필수) |
| member | [member.md](member.md) | `POST /auth/refresh` | 토큰 갱신 |
| member | [member.md](member.md) | `POST /auth/logout` | 로그아웃 |
| member | [member.md](member.md) | `DELETE /members/me` | 회원탈퇴 |
| game | [game.md](game.md) | `GET /genres` | 장르 필터 |
| game | [game.md](game.md) | `GET /games` | 전체 게임 목록/검색/자동완성 |
| game | [game.md](game.md) | `GET /members/me/games` | 내 게임 목록/검색 |
| game | [game.md](game.md) | `GET /games/{gameId}` | 게임 정보/hover |
| game | [game.md](game.md) | `POST /games/{gameId}/my-game` | 내 게임 등록 |
| game | [game.md](game.md) | `DELETE /games/{gameId}/my-game` | 내 게임 등록 해제 |
| review | [review.md](review.md) | `GET /games/{gameId}/reviews` | 리뷰 목록 |
| review | [review.md](review.md) | `GET /games/{gameId}/reviews/representative` | 최근 대표 리뷰 |
| review | [review.md](review.md) | `GET /reviews/{reviewId}/translation` | 리뷰 한국어 번역 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/reaction-trends` | 반응 추세 차트/기간 합계 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/summaries/reaction-trends` | 반응 추세 AI 요약 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/playtime-topics` | 플레이타임×토픽 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/summaries/playtime-topics` | 플레이타임 AI 요약 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/language-analysis` | 언어별 통계 |
| statistics | [statistics.md](statistics.md) | `GET /games/{gameId}/language-analysis/{languageCode}` | 언어 상세/AI 요약/대표 리뷰 |
| patch | [patch.md](patch.md) | `POST /games/{gameId}/plan-structures` | 기획안 구조화 |
| patch | [patch.md](patch.md) | `POST /games/{gameId}/case-searches` | 유사 사례 검색/상세 비교 |
| patch | [patch.md](patch.md) | `GET /members/me/patch-plans` | 마이페이지 기획안 내역 목록 조회 (인증 필수) |
| patch | [patch.md](patch.md) | `GET /members/me/patch-plans/{planId}` | 마이페이지 기획안 내역 상세 조회 (인증 필수) |
| patch | [patch.md](patch.md) | `GET /games/{gameId}/patches/{patchId}` | 패치 원문 상세 |
| patch | [patch.md](patch.md) | `GET /patches/{patchId}/translation` | 패치노트 한국어 번역 |

## 구현·검증 규칙

- API 변경 시 해당 도메인 문서를 갱신한다.
- 공통 규칙 변경은 `conventions.md`, endpoint 추가·삭제·이동은 이 index에 반영한다.
- 활성 endpoint는 한 도메인 문서에만 정의한다.
- Method, URL, 인증, request, response, 타입, 상태 코드는 이 디렉터리의 문서와 일치해야 한다.
