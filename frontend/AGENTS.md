## 프로젝트 기본 정보

- 기술 스택은 React 19, Vite, TypeScript, Tailwind CSS 4이다.
- 패키지 매니저는 `pnpm`만 사용한다.
- 주요 라이브러리는 React Router, TanStack Query, Zustand, axios이다.
- 새 페이지는 `src/pages/`에 만들고 라우트는 `route.ts`에 등록한다.
- 여러 페이지에서 재사용하는 컴포넌트는 `src/components/`에 둔다. 페이지에서만 사용하는 작은 하위 컴포넌트는 해당 페이지 파일 이 있는 디렉토리에 `/components` 를 만들어서 거기다가 둔다.

## 규칙과 문서 적용

- 세부 프론트엔드 규칙은 `.agents/rules/`에 분리되어 있다. 작업 대상 경로에 적용되는 규칙을 먼저 읽고 함께 적용한다.
- `.claude/rules/`는 Claude용 미러이므로 동일한 규칙을 `AGENTS.md`에 다시 나열하지 않는다.
- 작업 시작 전에 다음 문서 중 작업과 관련된 문서를 읽는다.
  - 코드 품질: `docs/frontend-code-quality.md`
  - 클린 코드: `docs/frontend_clean_code_guide.md`
  - API 및 서버 상태: `docs/api-guide.md`
- 사용자 요청과 프로젝트 문서가 충돌하면 사용자 요청을 우선한다. 문서끼리 충돌하거나 필수 문서를 읽을 수 없으면 임의로 판단하지 말고 그 사실을 보고한다.
- 기존 디렉토리 구조, 컴포넌트 패턴, 명명 규칙을 먼저 확인하고 명확한 이유 없이 새로운 패턴이나 별도 아키텍처를 도입하지 않는다.

## 작업 범위와 승인

- 명세에 없는 사용자 기능, 페이지, 버튼, 필터, 설정을 임의로 추가하지 않는다.
- 로딩, 빈 상태, 오류, 비활성화, 접근성, 반응형 처리와 요청 중 중복 실행 방지는 기능 완성에 필요한 기본 상태로 간주한다.
- 요청 범위와 관련 없는 리팩터링, 파일 이동, 이름 변경, 포맷 변경을 함께 수행하지 않는다.
- 전체 구현을 요청받으면 섹션 단위로 구현하고 검증하되 중간 승인을 기다리지 않고 요청된 범위까지 완료한다. 사용자가 단계별 검토를 요청한 경우에만 각 단계에서 확인을 기다린다.
- 파일 삭제, 대규모 파일 이동, 공개 API 변경, 설정 파일의 광범위한 변경 전에는 확인을 요청한다.
- 새로운 패키지 설치, 기존 패키지 제거, 주요 버전 변경 전에는 확인을 요청한다.

## 디자인 방향

### 기준과 시각적 성격

- 디자인 원본은 `thispatch.pen`의 `SB / ...` 화면과 `SB Components`이다. 현재 화면에서 사용하는 `sb-*` 변수를 기준으로 하며, 파일에 남아 있는 이전 `accent`, `font`, `control-height` 변수와 혼용하지 않는다.
- Steam 리뷰와 패치 이력을 비교하는 분석 도구로서 짙은 네이비 배경, 얇은 경계선, 밀도 있는 데이터 배치와 민트색 주요 액션을 사용한다. 블루 계열은 배경과 경계선에 적용하고, CTA와 선택 강조는 원본의 민트를 따른다.
- 기본 제품 화면은 다크 테마다. 운영체제 테마에 따라 임의로 라이트 팔레트로 바꾸지 않는다. 과도한 그림자, 장식용 그라데이션, 큰 둥근 카드의 반복은 피한다.

### 색상 토큰

Tailwind 4 토큰은 `src/theme.css`에서 관리하고 `src/index.css`에서 불러온다. 원본과 대조하기 쉽게 `sb-*` 이름을 유지한다. 아래 이름은 `--color-` 접두사를 생략한 것이며 `bg-sb-primary`, `text-sb-ink`, `border-sb-hairline`처럼 사용한다.

| 역할                              | 토큰                                                      | 값                                |
| --------------------------------- | --------------------------------------------------------- | --------------------------------- |
| 페이지 / 패널                     | `sb-canvas-base` / `sb-canvas-surface`                    | `#0e141b` / `#172433`             |
| 올라온 면 / 입력 배경 / 선택 배경 | `sb-canvas` / `sb-canvas-soft` / `sb-canvas-active`       | `#1b2838` / `#22303e` / `#24384d` |
| 헤더 / 랜딩의 어두운 섹션         | `sb-canvas-header` / `sb-canvas-night`                    | `#171a21` / `#0b1118`             |
| 기본 / 강조 / 차가운 경계선       | `sb-hairline` / `sb-hairline-strong` / `sb-hairline-cool` | `#27405a` / `#3d5f7c` / `#30506a` |
| 주요 액션 / 진한 강조 / 밝은 강조 | `sb-primary` / `sb-primary-deep` / `sb-primary-soft`      | `#3ecf8e` / `#24b47e` / `#4ade80` |
| 민트 글자 / 민트 배경 틴트        | `sb-primary-text` / `sb-tint-primary`                     | `#5fe0a4` / `#3ecf8e1f`           |
| 기본 글자 / 보조 글자 / 낮은 강조 | `sb-ink` / `sb-ink-mute` / `sb-ink-mute-2`                | `#ffffff` / `#b1c0ce` / `#94a4b4` |
| 비활성·장식 / 민트 위 글자        | `sb-ink-faint` / `sb-on-primary`                          | `#6f8296` / `#171717`             |
| 긍정 그래프 / 긍정 글자           | `sb-pos` / `sb-pos-text`                                  | `#3ecf8e` / `#4ade80`             |
| 부정 그래프 / 부정 글자           | `sb-neg` / `sb-neg-text`                                  | `#ff6b5e` / `#ff8a7e`             |
| 주의·마커 / 주의 글자             | `sb-mark` / `sb-amber-text`                               | `#ffc93c` / `#ffd166`             |
| 보라색 데이터 강조 / 글자         | `sb-accent-violet` / `sb-violet-text`                     | `#a36cd8` / `#c9a3ec`             |
| 모달 뒤 배경                      | `sb-scrim`                                                | `#0e141bcc`                       |

- 상태 박스는 `sb-tint-green/red/amber` 배경, `sb-line-green/red/amber` 경계선과 대응하는 상태 글자색을 조합한다. 차트의 낮은 강조에는 `sb-pos-dim`, `sb-neg-dim`을 사용한다.
- `sb-ink-faint`를 읽어야 하는 본문이나 안내문에 사용하지 않는다. 상태와 차트 계열은 색상 외에도 라벨, 범례, 아이콘으로 구분한다.

### 타이포그래피

- 본문·제목·컨트롤은 `font-sb-sans`(Noto Sans KR), 수치·날짜·코드는 `font-sb-mono`(JetBrains Mono)를 사용한다. 정렬된 수치에는 `tabular-nums`를 함께 적용한다.
- 아래 크기는 원본의 px 기준이다. 기본 두께는 `font-normal`(400), 제목·강조는 `font-medium`(500)이며 임의로 굵기를 늘리지 않는다.

| 유틸리티          | 크기 / 행간 | 용도                         |
| ----------------- | ----------- | ---------------------------- |
| `text-sb-caption` | 14px / 1.5  | 차트 축, 짧은 보조 정보      |
| `text-sb-body`    | 16px / 1.5  | 본문, 입력, 버튼, 메뉴, 태그 |
| `text-sb-lead`    | 18px / 1.75 | 랜딩 설명, 강조 설명         |
| `text-sb-title`   | 22px / 1.2  | 카드 제목, 지표              |
| `text-sb-heading` | 28px / 1.2  | 게임명, 하위 제목            |
| `text-sb-section` | 36px / 1.15 | 페이지·랜딩 섹션 제목        |
| `text-sb-display` | 64px / 1.1  | 데스크톱 랜딩 히어로         |

- 제목 자간은 토큰에 포함한다. 긴 리뷰 원문은 `leading-relaxed` 또는 `leading-loose`로 행간을 넓힌다. 원본에 행간이 없는 caption/body의 1.5는 구현 기본값이다.
- `index.html`에서 Google Fonts로 Noto Sans KR와 JetBrains Mono의 400·500 두께를 로드한다(`display=swap`). 기본 본문·제목과 `font-sans`는 Noto Sans KR, 코드와 `font-mono`는 JetBrains Mono 토큰에 연결한다. 로딩 중이거나 외부 폰트 요청이 실패하면 토큰의 시스템 폰트로 표시한다.

### 간격, 레이아웃과 컴포넌트

- 공통 간격은 `sb-1/2/3/4/5/6/8/12` = 4/8/12/16/20/24/32/48px이다. `gap-sb-2`, `p-sb-4`, `px-sb-12`처럼 사용한다. 이는 원본의 반복 간격을 정리한 기본 척도이며, 개별 화면의 특수 간격은 원본을 확인한다.
- 데스크톱 원본 너비는 1440px(`max-w-sb-page`), 헤더 높이는 56px(`h-sb-header`), 사이드바가 있는 레이아웃의 너비는 232px(`w-sb-sidebar`)다. 본문 좌우 여백은 48px, 랜딩은 120px(`px-sb-landing-gutter`), 랜딩 섹션 상하 여백은 110px(`py-sb-landing-section`)을 기준으로 한다.
- 기본 컨트롤은 `h-sb-control`(40px), `rounded-sb-control`(6px), 태그는 `rounded-sb-tag`(4px), 카드는 `rounded-sb-card`(8px), 모달은 `rounded-sb-modal`(12px)을 사용한다.
- 보조 버튼은 `bg-sb-canvas border border-sb-hairline-strong text-sb-ink`, 주요 버튼은 `bg-sb-primary text-sb-on-primary`로 구분한다. 선택 탭은 민트색 2px 하단 선을 사용한다.
- 일반 패널과 카드는 1px 경계선으로 구분한다. 그림자는 원본에서 사용한 `shadow-sb-hero`, `shadow-sb-modal`, `shadow-sb-popover`에 한정한다.
- 게임 목록은 이미지·게임명·태그·긍정률의 순서를 유지하고, 분석 화면은 지표에서 차트·토픽·리뷰 원문으로 이어지는 정보 위계를 유지한다.

### 구현 기본 상태와 사용 예

- 반응형과 상호작용은 원본에 없는 경우 다음을 구현 기본값으로 삼는다. 작은 화면은 좌우 여백 16px부터 시작하고 카드 열 수를 줄인다. 히어로 제목은 36px부터 확대하며, 표·차트는 필요한 영역 안에서 가로 스크롤한다. 1440px를 최소 화면 너비로 강제하지 않는다.
- 주요 버튼 hover는 `sb-primary-soft`, active는 `sb-primary-deep`를 사용한다. 키보드 포커스는 `focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sb-primary`, 비활성은 `disabled:opacity-50 disabled:cursor-not-allowed`를 기본으로 한다. 모션을 추가하면 `motion-reduce`를 지원한다.
- 로딩·빈 결과·오류에서도 같은 패널과 타이포그래피를 유지하고 원인과 다음 행동을 텍스트로 안내한다.
- 사용 예: 페이지 셸 `min-h-screen bg-sb-canvas-base font-sb-sans text-sb-body text-sb-ink scheme-dark`, 카드 `rounded-sb-card border border-sb-hairline-cool bg-sb-canvas-surface p-sb-4`, 기본 버튼 `h-sb-control cursor-pointer rounded-sb-control bg-sb-primary px-sb-4 text-sb-body text-sb-on-primary`.
- 현재 Vite 시작 화면의 `src/index.css`와 `src/App.css` 전역 스타일은 제품 디자인 시스템이 아니다. 제품 화면 구현 시 기존 전역 제목 스타일, 루트 너비·정렬, 자동 테마 전환을 함께 정리해야 한다. 이번 토큰 준비 작업은 해당 화면을 개편하지 않는다.

## 완료와 보고

- 코드 변경 후 `package.json`에 정의된 관련 검증 명령을 확인해 실행한다. 현재 기본 검증 명령은 `pnpm lint`와 `pnpm build`이다.
- 검증 명령이 없거나 실행할 수 없으면 성공했다고 표현하지 않고, 실행하지 못한 항목과 이유를 보고한다.
- 오류를 해결하기 위해 ESLint 규칙, TypeScript 설정, 테스트를 비활성화하거나 완화하지 않는다.
- 테스트 통과를 목적으로 기존 테스트를 삭제하거나 검증 수준을 낮추지 않는다.
- 작업 리포트에는 코드나 실행 결과로 확인한 사실만 작성하고, 확인할 수 없는 수치나 결과는 추정하지 않고 `TBD`로 표시한다.
