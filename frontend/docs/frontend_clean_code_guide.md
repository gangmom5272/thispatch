# AI Agent Guidelines: Frontend Clean Code Principles
> **Based on TOSSH SLASH 21 Presentation: "실무에서 바로 쓰는 Frontend Clean Code" (진유림)**

This guide provides concrete rules, architectural patterns, and actionable checklists for AI Agents and developers to refactor and write maintainable, clean frontend code in React/TypeScript projects.

---

## 1. Core Philosophy: Clean Code in Practice

- **Definition of Clean Code in Production**: Code that allows developers (and AI agents) to **find desired logic quickly**.
- **Business Value**: Clean Code = Reduced Maintenance Time (Code Reading, Debugging, Code Review) = Saving Resources and Time.
- **Key Realization**: *Clean Code is NOT just short code.* It is code with clear domain context, proper cohesion, single responsibility, and consistent abstraction levels.
- **Mindset**: *"It was right then, but wrong now."* Features accumulate over time. Always view the big picture when modifying existing files rather than naively appending `if-else` blocks.

---

## 2. Three Pillars of Frontend Clean Code

### Pillar 1: Cohesion (응집도)
> **Group code with the same purpose together, but expose critical business data/actions.**

#### Rules
1. **Do not naively hide everything in custom hooks (Black-box Trap)**:
   - Grouping implementation details is good, but hiding core business triggers, labels, or routing actions makes it harder to trace flow.
2. **Expose Core Data/Actions, Encapsulate Implementation Details**:
   - **Core Data/Actions (Outside)**: Popup title, main messages, submit action handlers, success callbacks.
   - **Details (Inside)**: Open/close modal state (`isOpen`), animation triggers, DOM event bindings.
3. **Prefer Declarative Programming over Imperative**:
   - Tell *what* to do rather than listing step-by-step *how* to do it.

#### Code Comparison

**Bad (Imperative / Scattered Logic)**
```tsx
// ❌ Implementation details, open states, and submit handling are scattered across the component
function QuestionPage() {
  const [popupOpened, setPopupOpened] = useState(false);

  async function handleClick() {
    setPopupOpened(true);
  }

  async function handlePopupSubmit() {
    await sendQuestion(expertId);
    alert("질문을 전송했습니다.");
  }

  return (
    <>
      <button onClick={handleClick}>질문하기</button>
      <Popup title="보험 질문하기" open={popupOpened}>
        <div>전문가가 설명드려요</div>
        <button onClick={handlePopupSubmit}>확인</button>
      </Popup>
    </>
  );
}
```

**Good (Declarative / Balanced Cohesion)**
```tsx
// ✅ Core parameters (title, contents) are passed explicitly; modal state & execution are encapsulated
function QuestionPage() {
  const [openPopup] = usePopup();

  async function handleClick() {
    const confirmed = await openPopup({
      title: "보험 질문하기",
      contents: <div>전문가가 설명드려요</div>,
    });
    if (confirmed) {
      await submitQuestion();
    }
  }

  return <button onClick={handleClick}>질문하기</button>;
}
```

---

### Pillar 2: Single Responsibility Principle (단일 책임)
> **Create functions and components with explicit names that do ONE thing.**

#### Rules
1. **Explicit Naming**: If a function checks agreement, opens a popup, and submits a request, naming it `handleQuestionSubmit` is misleading and dangerous. Break it down or name it precisely.
2. **Functional / Feature Wrappers for Side Effects**:
   - Separate logging, analytics, and intersection observation from UI component handlers.
   - Use wrapper components (e.g., `<LogClick>`, `<IntersectionArea>`) to decouple concerns.
3. **Use Domain-Specific Korean Variable Names for Complex Business Conditions**:
   - When business logic has multi-layered rules, domain-descriptive Korean names improve readability and prevent misinterpretation.

#### Refactoring Patterns

##### Pattern A: Feature Wrapper Components (Logging / Analytics)

**Bad (Side-effects mixed into click handler)**
```tsx
// ❌ Logging code mixed directly into UI event handler
<button onClick={async () => {
  log('제출 버튼 클릭');
  await openConfirm();
}}>
  제출
</button>
```

**Good (Declarative Wrapper Component)**
```tsx
// ✅ LogClick handles analytics logging; button only handles UI action
<LogClick message="제출 버튼 클릭">
  <button onClick={openConfirm}>제출</button>
</LogClick>
```

##### Pattern B: Observer/Visibility Abstraction

**Bad (IntersectionObserver boilerplate inside UI code)**
```tsx
// ❌ DOM observer logic mixed with fetching logic
const targetRef = useRef(null);
useEffect(() => {
  const observer = new IntersectionObserver(([{ isIntersecting }]) => {
    if (isIntersecting) fetchMoreData();
  });
  if (targetRef.current) observer.observe(targetRef.current);
  return () => observer.disconnect();
}, []);

return <div ref={targetRef}>더 보기</div>;
```

**Good (IntersectionArea Abstraction)**
```tsx
// ✅ IntersectionObserver details encapsulated inside wrapper
<IntersectionArea onImpression={fetchMoreData}>
  <div>더 보기</div>
</IntersectionArea>
```

##### Pattern C: Complex Domain Condition Naming

```tsx
// ✅ Clear domain concept naming using Korean variables for complex conditions
const 패널티풀림 = reasons.includes('PENALTY') === false;
const 평점4점이상 = review.rate >= 80;

if (패널티풀림 && 평점4점이상) {
  // Execute logic
}
```

---

### Pillar 3: Abstraction Level Consistency (추상화 및 단계 통일)
> **Extract core concepts cleanly and maintain a uniform level of abstraction within the same component/function.**

#### Rules
1. **Match Abstraction Levels within a JSX Block**:
   - Do NOT mix high-level component abstractions (`<Reviews />`) with low-level DOM manipulations or raw `.map()` loops (`{STARS.map(...)}`) in the same JSX block.
2. **Avoid Premature Abstraction**:
   - Do not abstract code prematurely when duplication is minimal or when future requirements are likely to diverge. Abstraction should simplify, not restrict flexibility.
3. **Function Abstraction**:
   - Group raw API fetch + data formatting calls into domain-descriptive helper functions (e.g., `getPlannerLabel(plannerId)`).

#### Code Comparison

**Bad (Mixed Abstraction Levels)**
```tsx
// ❌ High-level components (<Reviews />) mixed with low-level array rendering (STARS.map) and raw state checks
return (
  <>
    <Title>별점을 매겨주세요</Title>
    <div>
      {STARS.map((star) => (
        <Star key={star.id} active={rating >= star.value} />
      ))}
    </div>
    <Reviews />
    {rating !== 0 && <Agreement />}
    <Button rating={rating} />
  </>
);
```

**Good (Consistent High-Level Abstraction)**
```tsx
// ✅ Unified abstraction level: All child elements are abstracted domain components
return (
  <>
    <Title>별점을 매겨주세요</Title>
    <Stars rating={rating} />
    <Reviews />
    {rating !== 0 && <AgreementButton />}
  </>
);
```

---

## 3. Refactoring Workflow for AI Agents

When modifying or generating React components, follow this 4-step execution flow:

```
┌────────────────────────────────────────────────────────┐
│ 1. Analyze Big Picture & Context                       │
│    - Don't just append `if-else` or new states         │
│    - Identify duplicate or scattered domain logic     │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 2. Unify Purpose & Cohesion                            │
│    - Extract core data/handlers from details           │
│    - Group related logic into cohesive units           │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 3. Enforce Single Responsibility                       │
│    - Split monster functions into precise helpers     │
│    - Use wrapper components for logging/observers      │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 4. Align Abstraction Levels                            │
│    - Ensure JSX blocks stay at consistent abstraction  │
│    - Standardize function granularity                  │
└────────────────────────────────────────────────────────┘
```

---

## 4. AI Code Review Checklist

Before finalizing code edits or outputting new files, verify against this checklist:

- [ ] **Cohesion**: Is single-purpose code kept together, without hiding critical business logic/props in black-box custom hooks?
- [ ] **Declarative Style**: Does the component describe *what* it renders rather than imperative step-by-step operations?
- [ ] **Single Responsibility**:
  - [ ] Do functions do only ONE task as described by their names?
  - [ ] Are side-effects (logging, impression observation) separated into dedicated components/hooks?
- [ ] **Abstraction Level**:
  - [ ] Are JSX elements in the return statement at a consistent level of detail?
  - [ ] Is there any premature abstraction that reduces maintainability?
- [ ] **Readability & Naming**:
  - [ ] Are complex domain conditions clearly named using readable identifiers (or Korean names where appropriate)?