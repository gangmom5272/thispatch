# Playwright E2E Test Rules

이 문서는 AI 또는 개발자가 Playwright를 사용하여 E2E 테스트를 작성할 때 따라야 하는 규칙을 정의한다.

테스트의 목적은 **구현 세부사항을 검증하는 것이 아니라 실제 사용자가 서비스의 핵심 기능을 정상적으로 사용할 수 있는지 검증하는 것**이다.

---

## 1. 기본 원칙

### 1.1 사용자 행동을 기준으로 테스트한다

E2E 테스트는 내부 구현이 아니라 사용자가 관찰할 수 있는 동작을 검증해야 한다.

```ts
// ❌ DOM 구현 세부사항 검증
expect(await page.locator('.modal').count()).toBe(1);

// ✅ 사용자가 실제로 확인할 수 있는 상태 검증
await expect(
  page.getByRole('dialog', { name: '회원 탈퇴' }),
).toBeVisible();
```

다음과 같은 내부 구현을 직접 검증하지 않는다.

* React state
* 컴포넌트 내부 함수
* CSS class
* DOM 계층 구조
* Zustand / Redux 내부 상태
* 특정 React 컴포넌트의 존재 여부

---

### 1.2 테스트 하나는 하나의 사용자 시나리오를 검증한다

하나의 테스트에서는 하나의 명확한 목적만 검증한다.

```ts
test('로그인한 사용자는 게시글을 작성할 수 있다', async ({ page }) => {
  // ...
});
```

다음과 같이 여러 독립적인 기능을 하나의 테스트에 넣지 않는다.

```text
로그인
→ 게시글 작성
→ 게시글 수정
→ 댓글 작성
→ 게시글 삭제
```

단, 하나의 완결된 핵심 사용자 흐름 자체를 검증하는 테스트라면 여러 단계가 포함될 수 있다.

예:

```text
상품 선택
→ 장바구니
→ 주문
→ 결제
→ 주문 완료
```

---

## 2. 테스트 우선순위

모든 기능을 무조건 E2E 테스트하지 않는다.

다음 우선순위를 기준으로 테스트한다.

### P0 — Critical

서비스가 정상적으로 동작하기 위해 반드시 보장되어야 하는 기능.

예:

* 로그인
* 회원가입
* 핵심 데이터 조회
* 핵심 데이터 생성
* 주문
* 결제
* 예약
* 신청
* 인증 및 권한

### P1 — Important

핵심 기능을 보조하는 주요 기능.

예:

* 수정
* 삭제
* 검색
* 필터
* 정렬
* 페이지네이션

### P2 — Optional

서비스 이용에 치명적이지 않은 기능.

예:

* 테마 변경
* Tooltip
* Animation
* UI Preference

E2E 테스트는 P0 → P1 → P2 순서로 작성한다.

---

## 3. 테스트 이름

테스트 이름만 읽어도 어떤 사용자 행동과 결과를 검증하는지 알 수 있어야 한다.

### 권장

```ts
test('올바른 계정으로 로그인할 수 있다', async () => {});

test('비밀번호가 틀리면 오류 메시지를 보여준다', async () => {});

test('로그인하지 않은 사용자는 게시글 작성 페이지에 접근할 수 없다', async () => {});

test('게시글 작성 완료 후 상세 페이지로 이동한다', async () => {});
```

### 금지

```ts
test('login test', async () => {});

test('button test', async () => {});

test('post test', async () => {});

test('test1', async () => {});
```

CI에서 테스트가 실패했을 때 테스트 이름만 보고 문제를 추측할 수 있어야 한다.

---

# 4. Locator 규칙

Locator는 다음 우선순위를 따른다.

```text
1. getByRole()
2. getByLabel()
3. getByText()
4. getByPlaceholder()
5. getByTestId()
6. locator() / CSS Selector
```

---

## 4.1 `getByRole()`을 최우선으로 사용한다

가능하다면 사용자가 실제로 인식하는 역할과 accessible name으로 요소를 찾는다.

```ts
await page.getByRole('button', {
  name: '로그인',
}).click();
```

```ts
await page.getByRole('heading', {
  name: '게시글 작성',
}).isVisible();
```

---

## 4.2 Form 요소는 `getByLabel()`을 우선 사용한다

```ts
await page.getByLabel('이메일').fill('test@example.com');

await page.getByLabel('비밀번호').fill('password');
```

접근 가능한 label이 존재하지 않는다면 UI 구현 자체의 접근성 문제인지 먼저 검토한다.

---

## 4.3 `getByTestId()`는 마지막 수단으로 사용한다

사용자가 인식할 수 있는 Role, Label, Text 등으로 안정적으로 식별하기 어려울 때만 사용한다.

```tsx
<div data-testid="chart-container" />
```

```ts
page.getByTestId('chart-container');
```

단순히 테스트 작성이 편하다는 이유만으로 모든 요소에 `data-testid`를 추가하지 않는다.

---

## 4.4 CSS class 기반 Locator를 사용하지 않는다

```ts
// ❌
page.locator('.login-button');

// ❌
page.locator('.primary-button');

// ❌
page.locator('#login-form > div:nth-child(2) > button');
```

CSS 또는 DOM 구조가 변경되어도 사용자 기능은 동일할 수 있기 때문에 테스트가 구현 세부사항에 의존하지 않도록 한다.

---

## 4.5 `nth()` 사용을 최소화한다

```ts
// ❌ 가능하면 사용하지 않는다.
page.getByRole('button').nth(2);
```

대신 의미 있는 정보로 대상을 특정한다.

```ts
page.getByRole('button', {
  name: '삭제',
});
```

동일한 요소가 여러 개인 경우 상위 영역을 먼저 특정한다.

```ts
const post = page.getByRole('article', {
  name: 'Playwright 테스트',
});

await post.getByRole('button', {
  name: '삭제',
}).click();
```

---

# 5. Action 규칙

사용자의 실제 행동에 가까운 API를 사용한다.

대표적으로 다음 API를 사용한다.

```text
click()
fill()
check()
uncheck()
selectOption()
press()
hover()
```

예:

```ts
await page.getByLabel('이메일').fill('test@example.com');

await page
  .getByRole('button', { name: '로그인' })
  .click();
```

DOM API를 직접 호출하여 사용자 행동을 우회하지 않는다.

```ts
// ❌ 특별한 이유가 없다면 사용하지 않는다.
await page.evaluate(() => {
  document.querySelector('button')?.click();
});
```

---

# 6. Waiting 규칙

## 6.1 `waitForTimeout()` 사용을 금지한다

다음과 같은 임의의 sleep을 작성하지 않는다.

```ts
// ❌ 금지
await page.waitForTimeout(1000);

// ❌ 금지
await page.waitForTimeout(3000);
```

네트워크나 렌더링 속도는 환경마다 다르기 때문에 고정 시간 대기는 flaky test를 만든다.

---

## 6.2 Playwright의 Auto-waiting을 사용한다

```ts
await page
  .getByRole('button', { name: '로그인' })
  .click();
```

Playwright가 요소가 실제로 상호작용 가능한 상태가 될 때까지 기다리도록 한다.

---

## 6.3 상태 변화는 Assertion으로 기다린다

```ts
// ❌
await page.waitForTimeout(2000);

expect(...);

// ✅
await expect(
  page.getByText('저장되었습니다.'),
).toBeVisible();
```

---

# 7. Assertion 규칙

가능한 한 Playwright의 Web-first Assertion을 사용한다.

### 권장

```ts
await expect(locator).toBeVisible();

await expect(locator).toBeHidden();

await expect(locator).toHaveText('hello');

await expect(locator).toHaveValue('hello');

await expect(locator).toBeEnabled();

await expect(page).toHaveURL('/home');

await expect(page).toHaveTitle(/Home/);
```

---

## 7.1 직접 값을 꺼내 비교하지 않는다

```ts
// ❌
const text = await locator.textContent();

expect(text).toBe('저장되었습니다.');
```

가능하다면 다음처럼 작성한다.

```ts
// ✅
await expect(locator).toHaveText('저장되었습니다.');
```

Playwright Assertion의 자동 retry 기능을 활용하기 위함이다.

---

# 8. 테스트 독립성

모든 테스트는 독립적으로 실행될 수 있어야 한다.

다음 상황에서도 정상적으로 동작해야 한다.

```bash
npx playwright test

npx playwright test create.spec.ts

npx playwright test --grep "게시글 수정"
```

---

## 8.1 다른 테스트의 결과에 의존하지 않는다

```ts
// ❌
test('게시글을 생성한다', ...);

// 앞 테스트에서 생성된 게시글이 존재한다고 가정
test('게시글을 수정한다', ...);
```

대신 각 테스트에서 필요한 데이터를 직접 준비한다.

---

## 8.2 테스트 실행 순서를 가정하지 않는다

```text
Test A
↓
Test B
↓
Test C
```

형태의 실행 순서에 의존하지 않는다.

각 테스트는 별도의 BrowserContext에서 독립적으로 실행된다는 것을 전제로 작성한다.

---

# 9. 테스트 데이터 준비

테스트에서 검증하려는 기능과 관계없는 UI 동작은 API를 이용하여 준비하는 것을 우선 고려한다.

예를 들어 게시글 수정 테스트를 위해:

```text
로그인
→ 게시글 작성 화면 이동
→ 게시글 작성
→ 상세 페이지 이동
→ 수정
```

전체 과정을 거치지 않는다.

게시글 작성이 수정 테스트의 목적이 아니라면 API로 데이터를 준비한다.

```ts
test('게시글을 수정할 수 있다', async ({
  page,
  request,
}) => {
  const response = await request.post('/api/posts', {
    data: {
      title: '수정 전 제목',
      content: '내용',
    },
  });

  const post = await response.json();

  await page.goto(`/posts/${post.id}`);

  // 여기서부터 실제 수정 기능 테스트
});
```

---

# 10. 테스트 데이터 충돌 방지

병렬 실행을 고려하여 테스트 데이터가 서로 충돌하지 않게 한다.

고정된 값을 무조건 공유하지 않는다.

```ts
// ❌ 여러 테스트에서 동일한 데이터 사용
const title = '테스트 게시글';
```

필요하다면 고유한 값을 생성한다.

```ts
const title = `테스트 게시글-${Date.now()}`;
```

또는 테스트용 factory/helper를 사용한다.

단, 무작위 데이터 때문에 테스트 재현이 어려워지지 않도록 한다.

---

# 11. Authentication

로그인 기능 자체를 테스트하는 경우를 제외하고 매 테스트마다 UI 로그인을 반복하지 않는다.

```text
로그인 E2E 테스트
→ 실제 UI 로그인

일반 인증 사용자 테스트
→ storageState 재사용
```

Playwright의 `storageState`를 사용하여 인증 상태를 공유한다.

예:

```text
playwright/
└── .auth/
    └── user.json
```

인증 정보가 포함된 파일은 Git에 커밋하지 않는다.

```gitignore
playwright/.auth/
```

---

# 12. API Mocking

Mock은 필요한 경우에만 사용한다.

핵심 사용자 흐름은 가능하면 실제 Backend와 연결하여 검증한다.

예:

```text
로그인
회원가입
게시글 작성
결제
예약
```

---

## 12.1 Mock이 적합한 경우

실제 환경에서 만들기 어렵거나 비결정적인 상황은 Mock할 수 있다.

예:

* HTTP 500
* HTTP 403
* Timeout
* 빈 데이터
* 특정 Edge Case
* Rate Limit
* 비정상 Response
* 외부 API 장애

예:

```ts
await page.route('**/api/posts', async (route) => {
  await route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({
      message: 'Internal Server Error',
    }),
  });
});
```

---

## 12.2 모든 API를 Mock하지 않는다

모든 API를 Mock하면 E2E 테스트가 아닌 프론트엔드 통합 테스트에 가까워질 수 있다.

E2E의 목적이 다음 전체 연결을 검증하는 것이라면:

```text
Frontend
    ↓
Network
    ↓
Backend
    ↓
Database
```

핵심 흐름에서는 실제 시스템을 사용한다.

---

# 13. Happy Path만 작성하지 않는다

중요한 기능에는 최소한 다음 관점을 고려한다.

```text
정상 상황
사용자 오류
시스템 오류
권한 오류
```

예를 들어 로그인이라면:

```ts
test.describe('로그인', () => {
  test('올바른 계정으로 로그인할 수 있다', async () => {});

  test('비밀번호가 틀리면 오류 메시지를 보여준다', async () => {});

  test('로그인 API 요청이 실패하면 오류 메시지를 보여준다', async () => {});
});
```

모든 기능에 억지로 모든 경우를 작성할 필요는 없으며 중요도와 위험도를 기준으로 선택한다.

---

# 14. `beforeEach` / Hook

여러 테스트에서 공통으로 필요한 **가벼운 초기 상태**는 `beforeEach`를 사용할 수 있다.

```ts
test.describe('로그인', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
  });

  test('로그인할 수 있다', async ({ page }) => {
    // ...
  });
});
```

단, `beforeEach`에 지나치게 많은 로직을 숨기지 않는다.

테스트 파일을 읽었을 때 테스트에 필요한 전제조건을 이해할 수 있어야 한다.

---

# 15. Fixture

반복되는 테스트 환경 또는 공통 기능은 Fixture로 추출할 수 있다.

Built-in fixture의 사용을 우선한다.

```ts
test('...', async ({
  page,
  context,
  request,
}) => {
  // ...
});
```

Custom Fixture는 다음 상황에서 고려한다.

* 여러 테스트에서 동일한 초기화가 반복됨
* 특정 사용자 권한 환경이 반복됨
* 테스트용 API client가 반복됨
* 공통 테스트 context가 필요함

단순히 코드 몇 줄을 줄이기 위해 과도하게 Fixture를 만들지 않는다.

---

# 16. Page Object Model

처음부터 모든 페이지를 Page Object로 추상화하지 않는다.

### 초기

```ts
await page.getByLabel('이메일').fill(email);

await page.getByLabel('비밀번호').fill(password);

await page
  .getByRole('button', { name: '로그인' })
  .click();
```

반복이 충분히 생긴 경우에만 Page Object를 고려한다.

```ts
class LoginPage {
  constructor(private readonly page: Page) {}

  async login(email: string, password: string) {
    await this.page.getByLabel('이메일').fill(email);
    await this.page.getByLabel('비밀번호').fill(password);

    await this.page
      .getByRole('button', { name: '로그인' })
      .click();
  }
}
```

원칙:

> 중복이 발생해서 추상화한다.
> 추상화하기 위해 추상화하지 않는다.

---

# 17. 불필요한 Helper를 만들지 않는다

다음과 같은 의미 없는 wrapper를 만들지 않는다.

```ts
// ❌
async function clickButton(page: Page, name: string) {
  await page.getByRole('button', { name }).click();
}
```

Playwright API 자체가 충분히 읽기 쉽다면 직접 사용한다.

```ts
// ✅
await page
  .getByRole('button', { name: '저장' })
  .click();
```

Helper는 **도메인 의미가 있을 때** 작성한다.

```ts
await createTestPost(request);

await loginAsAdmin(page);

await seedUser(request);
```

---

# 18. 파일 구조

기능 또는 도메인을 기준으로 테스트를 구성한다.

예:

```text
e2e/
├── auth/
│   ├── login.spec.ts
│   └── signup.spec.ts
│
├── posts/
│   ├── create.spec.ts
│   ├── update.spec.ts
│   └── delete.spec.ts
│
├── fixtures/
│   └── test.ts
│
├── pages/
│   └── ...
│
├── helpers/
│   └── ...
│
└── auth.setup.ts
```

테스트 수가 적다면 불필요하게 복잡한 디렉터리 구조를 만들지 않는다.

---

# 19. 테스트 코드 구조

가능하면 테스트를 다음 흐름으로 읽을 수 있게 작성한다.

```text
Arrange
→ 테스트 상태 준비

Act
→ 사용자 행동

Assert
→ 사용자에게 나타나는 결과 확인
```

예:

```ts
test('게시글을 작성할 수 있다', async ({
  page,
}) => {
  // Arrange
  await page.goto('/posts/new');

  // Act
  await page
    .getByLabel('제목')
    .fill('Playwright 테스트');

  await page
    .getByLabel('내용')
    .fill('E2E 테스트입니다.');

  await page
    .getByRole('button', { name: '등록' })
    .click();

  // Assert
  await expect(
    page.getByRole('heading', {
      name: 'Playwright 테스트',
    }),
  ).toBeVisible();
});
```

단순한 테스트라면 `Arrange`, `Act`, `Assert` 주석을 굳이 작성하지 않아도 된다.

코드 자체만으로 구조가 명확한 것을 우선한다.

---

# 20. 구현 코드 수정 원칙

테스트를 통과시키기 위해 실제 서비스 동작을 임의로 변경하지 않는다.

특히 다음 행동을 금지한다.

```text
테스트만을 위한 production 분기 추가

테스트 때문에 사용자에게 보이는 텍스트 변경

테스트를 통과시키기 위한 setTimeout 추가

테스트 환경에서만 기능을 비활성화

실제 버그를 테스트 코드에서 우회
```

테스트 작성 중 실제 서비스 코드에서 문제를 발견했다면 테스트를 억지로 맞추지 말고 문제를 명확하게 보고한다.

---

# 21. Flaky Test 방지

다음 코드는 flaky test의 원인이 될 가능성이 높기 때문에 작성하지 않는다.

```ts
// ❌ 고정 시간 대기
await page.waitForTimeout(3000);

// ❌ DOM 위치 기반
page.locator('div:nth-child(3) > button');

// ❌ CSS 구현 기반
page.locator('.primary-button');

// ❌ 테스트 실행 순서 의존

// ❌ 다른 테스트 데이터 의존

// ❌ 의미 없는 retry 로직

// ❌ 직접 구현한 polling
while (...) {
  // ...
}
```

Playwright의 다음 기능을 우선 사용한다.

```text
Locator
Auto-waiting
Web-first Assertion
Test Isolation
Fixture
```

---

# 22. Retry

Retry는 flaky test를 숨기는 용도로 사용하지 않는다.

다음 상황이 발생하면 원인을 조사한다.

```text
첫 실행 실패
↓
Retry 성공
```

CI 안정성을 위해 retry를 사용할 수 있지만, 반복적으로 retry가 발생하는 테스트는 수정해야 한다.

예:

```ts
export default defineConfig({
  retries: process.env.CI ? 1 : 0,
});
```

---

# 23. Trace / Screenshot

CI에서 테스트 실패 원인을 확인할 수 있도록 디버깅 정보를 남긴다.

권장 설정:

```ts
export default defineConfig({
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
});
```

테스트가 실패하면 우선 Trace를 확인한다.

확인할 대상:

```text
Action
Locator
DOM
Network
Console
Screenshot
Error
```

---

# 24. Browser 전략

처음부터 모든 PR에서 모든 브라우저를 실행할 필요는 없다.

예를 들어 다음 전략을 사용할 수 있다.

```text
Pull Request
→ Chromium
→ Critical / Smoke Test

Main Merge
→ Chromium
→ 전체 E2E

Scheduled Test
→ Chromium
→ Firefox
→ WebKit
```

프로젝트 요구사항에 따라 조정한다.

---

# 25. 테스트 Tag

테스트 규모가 커지면 목적에 따라 Tag를 사용할 수 있다.

예:

```text
@smoke

@critical

@regression
```

예:

```ts
test(
  '로그인할 수 있다',
  {
    tag: '@smoke',
  },
  async ({ page }) => {
    // ...
  },
);
```

---

# 26. 금지 사항

AI는 다음 코드를 특별한 이유 없이 생성하지 않는다.

```ts
// ❌ arbitrary sleep
await page.waitForTimeout(3000);

// ❌ fragile selector
page.locator('div:nth-child(3) > button');

// ❌ implementation selector
page.locator('.submit-button');

// ❌ unnecessary test id
page.getByTestId('button-3');

// ❌ manual DOM interaction
page.evaluate(() => {
  document.querySelector('button')?.click();
});

// ❌ manual polling
while (...) {}

// ❌ meaningless assertion
expect(true).toBe(true);

// ❌ test dependency
// 이전 테스트에서 만들어진 데이터를 사용

// ❌ forced action without clear reason
locator.click({ force: true });
```

특히 `force: true`가 필요하다면 테스트보다 UI 구현 또는 테스트 시나리오에 문제가 없는지 먼저 확인한다.

---

# 27. AI 테스트 생성 절차

AI가 새로운 E2E 테스트를 작성할 때 반드시 다음 순서를 따른다.

## Step 1. 기능 파악

먼저 다음을 확인한다.

```text
사용자는 누구인가?

무엇을 하려고 하는가?

성공 결과는 무엇인가?

실패할 수 있는 지점은 어디인가?
```

---

## Step 2. 기존 테스트 확인

새로운 테스트를 작성하기 전에 기존 E2E 테스트를 확인한다.

확인할 내용:

```text
기존 Fixture

Authentication 방식

Helper

Page Object

Locator Convention

테스트 데이터 생성 방식

기존 테스트 Naming Convention
```

같은 기능을 중복으로 구현하지 않는다.

---

## Step 3. 테스트 시나리오 정의

코드를 작성하기 전에 테스트 목적을 명확하게 정의한다.

예:

```text
Given
로그인한 사용자가 게시글 작성 페이지에 있다.

When
제목과 내용을 입력하고 등록한다.

Then
작성한 게시글 상세 페이지가 표시된다.
```

---

## Step 4. 필요한 데이터 준비 방법 결정

먼저 다음 순서로 고려한다.

```text
Fixture
↓
API를 통한 Setup
↓
UI를 통한 Setup
```

실제로 검증해야 하는 행동이 아니라면 UI Setup을 최소화한다.

---

## Step 5. Locator 선택

다음 우선순위를 지킨다.

```text
getByRole
↓
getByLabel
↓
getByText
↓
getByPlaceholder
↓
getByTestId
↓
locator
```

---

## Step 6. Action 작성

실제 사용자 행동과 동일하게 작성한다.

---

## Step 7. 사용자 관점의 결과 검증

내부 상태가 아니라 화면 또는 사용자에게 observable한 결과를 검증한다.

---

## Step 8. 독립성 확인

해당 테스트 하나만 실행해도 성공할 수 있는지 확인한다.

---

## Step 9. Flaky 가능성 확인

다음을 검사한다.

```text
waitForTimeout이 있는가?

nth()에 의존하는가?

CSS class에 의존하는가?

다른 테스트의 데이터를 사용하는가?

불필요한 force 옵션이 있는가?

비동기 상태를 일반 expect로 검사하는가?
```

---

# 28. AI가 테스트 작성 전에 판단해야 하는 것

AI는 요구사항 하나마다 무조건 테스트를 생성하지 않는다.

먼저 다음을 판단한다.

```text
이 기능이 E2E 테스트가 필요한가?

Unit Test가 더 적절하지 않은가?

Component Test가 더 적절하지 않은가?

이미 다른 E2E 테스트에서 충분히 검증하고 있지 않은가?
```

다음과 같은 것은 E2E보다 Unit/Component Test가 적합할 가능성이 높다.

```text
utility 함수

데이터 변환 함수

validation 함수

React hook 내부 로직

단순 컴포넌트 rendering

상태 관리 함수
```

---

# 29. 테스트 완료 체크리스트

AI는 테스트 작성을 완료하기 전에 다음 체크리스트를 확인한다.

## Scenario

* [ ] 테스트 목적이 하나로 명확한가?
* [ ] 실제 사용자 행동을 검증하는가?
* [ ] 테스트 이름만 보고 시나리오를 이해할 수 있는가?

## Locator

* [ ] `getByRole()`을 우선 사용했는가?
* [ ] Form 입력은 `getByLabel()`을 고려했는가?
* [ ] CSS class에 의존하지 않는가?
* [ ] DOM 구조에 의존하지 않는가?
* [ ] 불필요한 `data-testid`를 사용하지 않았는가?
* [ ] 불필요한 `nth()`를 사용하지 않았는가?

## Waiting

* [ ] `waitForTimeout()`을 사용하지 않았는가?
* [ ] Playwright Auto-waiting을 활용하는가?
* [ ] Web-first Assertion을 사용하는가?

## Isolation

* [ ] 다른 테스트 결과에 의존하지 않는가?
* [ ] 단독 실행할 수 있는가?
* [ ] 테스트 데이터가 병렬 실행 시 충돌하지 않는가?

## Authentication

* [ ] 로그인 자체를 테스트하는 것이 아니라면 불필요한 UI 로그인을 반복하지 않는가?
* [ ] 기존 `storageState` 또는 인증 Fixture를 재사용할 수 없는지 확인했는가?

## Test Data

* [ ] 검증 대상이 아닌 데이터 준비를 불필요하게 UI로 하지 않는가?
* [ ] 기존 Fixture 또는 API setup을 활용할 수 있는가?

## Mock

* [ ] Mock이 정말 필요한가?
* [ ] 실제 E2E 흐름을 지나치게 Mock하고 있지 않은가?

## Quality

* [ ] 의미 없는 Helper를 만들지 않았는가?
* [ ] 불필요한 Page Object를 만들지 않았는가?
* [ ] `force: true`로 문제를 숨기지 않았는가?
* [ ] 테스트를 통과시키기 위해 production 동작을 왜곡하지 않았는가?

---

# 30. 최종 원칙

E2E 테스트를 작성할 때 가장 중요한 질문은 다음과 같다.

> 이 테스트는 사용자의 행동을 검증하고 있는가, 아니면 현재 코드의 구현 방식을 검증하고 있는가?

항상 **사용자가 무엇을 보고, 무엇을 클릭하고, 무엇을 입력하고, 어떤 결과를 얻는가**를 기준으로 테스트한다.

좋은 Playwright 테스트는 단순히 현재 코드를 통과하는 테스트가 아니다.

좋은 E2E 테스트는 구현이 리팩터링되더라도 사용자 경험이 동일하다면 계속 통과하고, 사용자 경험이 실제로 깨졌을 때만 실패해야 한다.

---

## Reference

* Playwright Writing Tests
  https://playwright.dev/docs/writing-tests

* Playwright Best Practices
  https://playwright.dev/docs/best-practices

* Playwright Locators
  https://playwright.dev/docs/locators

* Playwright Auto-waiting / Actionability
  https://playwright.dev/docs/actionability

* Playwright Assertions
  https://playwright.dev/docs/test-assertions

* Playwright Test Isolation
  https://playwright.dev/docs/browser-contexts

* Playwright Fixtures
  https://playwright.dev/docs/test-fixtures

* Playwright Authentication
  https://playwright.dev/docs/auth

* Playwright API Testing
  https://playwright.dev/docs/api-testing

* Playwright Mock APIs
  https://playwright.dev/docs/mock

* Playwright Network
  https://playwright.dev/docs/network

* Playwright Projects
  https://playwright.dev/docs/test-projects

* Playwright Trace Viewer
  https://playwright.dev/docs/trace-viewer
