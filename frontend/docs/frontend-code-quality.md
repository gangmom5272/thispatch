## Frontend Code Quality (4 Criteria)

**모든 프론트엔드 코드 작성/수정/리뷰 시 아래 4가지 기준으로 자가 점검한다.**
출처: [토스 Frontend Fundamentals](https://frontend-fundamentals.com/code-quality/code/) — "좋은 코드란 변경하기 쉬운 코드"

기준끼리 상충할 수 있다. 그럴 때는 맹목적으로 다 적용하지 말고 상황에 맞는 우선순위를 판단하고, 판단 근거를 짧게 남긴다.

예시는 이 프로젝트의 도메인(`Property`, `PropertyCard`, `AuthContext` 등)을 기준으로 작성했다.

### 1. 가독성 (Readability) — 코드를 읽고 동작을 이해하기 쉬운가

- **맥락 줄이기**: 한 함수/컴포넌트 안에 조건 분기가 많아 한눈에 안 읽히면 이름 있는 단위(변수, 함수, 컴포넌트)로 쪼갠다.
- **이름 붙이기**: 매직 넘버, 복잡한 조건식(`if (a && !b || c)`)에는 의미를 드러내는 이름을 붙인다.
- **위에서 아래로 읽히게 하기**: 중첩 삼항 연산자, 실행 순서와 어긋나는 조건 배치를 피한다. 코드는 위에서 아래로 읽었을 때 이해되게 작성한다.

#### 예시 — 맥락 줄이기

```tsx
// Before: 로딩·에러·빈 목록·정상 목록 분기가 한 컴포넌트 안에 뒤섞여 있어
// 한눈에 "지금 어떤 상태를 그리는 화면인지" 읽기 어렵다.
function PropertyListPage({
  loading,
  error,
  properties,
}: PropertyListPageProps) {
  return (
    <main>
      {loading ? (
        <div className="grid grid-cols-3 gap-4">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-40" />
          ))}
        </div>
      ) : error ? (
        <p className="text-destructive">매물을 불러오지 못했습니다</p>
      ) : properties.length === 0 ? (
        <p className="text-muted-foreground">조건에 맞는 매물이 없습니다</p>
      ) : (
        <div className="grid grid-cols-3 gap-4">
          {properties.map((p) => (
            <PropertyCard key={p.id} property={p} />
          ))}
        </div>
      )}
    </main>
  );
}
```

```tsx
// After: 각 상태를 이름 있는 컴포넌트로 분리하면
// PropertyListPage는 "어떤 상태일 때 무엇을 그리는지"만 담당한다.
function PropertyListPage({
  loading,
  error,
  properties,
}: PropertyListPageProps) {
  return (
    <main>
      {loading && <PropertyListSkeleton />}
      {!loading && error && <PropertyListError />}
      {!loading && !error && properties.length === 0 && <PropertyListEmpty />}
      {!loading && !error && properties.length > 0 && (
        <PropertyGrid properties={properties} />
      )}
    </main>
  );
}

function PropertyListSkeleton() {
  return (
    <div className="grid grid-cols-3 gap-4">
      {Array.from({ length: 6 }, (_, i) => (
        <Skeleton key={i} className="h-40" />
      ))}
    </div>
  );
}

function PropertyListError() {
  return <p className="text-destructive">매물을 불러오지 못했습니다</p>;
}

function PropertyListEmpty() {
  return <p className="text-muted-foreground">조건에 맞는 매물이 없습니다</p>;
}
```

#### 예시 — 이름 붙이기

```tsx
// Before: 600이 뭘 뜻하는지, 조건식이 어떤 상황을 판별하는지
// 호출부만 봐서는 알 수 없다.
setTimeout(() => setLoading(false), 600);

if (
  (property.dealType === "월세" && property.monthlyRent > 0) ||
  property.deposit === 0
) {
  showFreeDepositBadge();
}
```

```tsx
// After: 이름이 곧 설명이 되어 조건을 다시 읽을 필요가 없다.
const MOCK_LOADING_MS = 600;
setTimeout(() => setLoading(false), MOCK_LOADING_MS);

const isFreeDepositMonthlyRent =
  property.dealType === "월세" && property.monthlyRent > 0;
const hasNoDeposit = property.deposit === 0;

if (isFreeDepositMonthlyRent || hasNoDeposit) {
  showFreeDepositBadge();
}
```

#### 예시 — 위에서 아래로 읽히게 하기

```tsx
// Before: 중첩 삼항 연산자는 조건이 늘어날수록
// 어떤 값이 어떤 조건에 대응하는지 시각적으로 추적하기 어렵다.
const priceLabel =
  property.dealType === "매매"
    ? "매매가"
    : property.dealType === "전세"
      ? "전세 보증금"
      : property.dealType === "월세"
        ? "보증금 / 월세"
        : "";
```

```tsx
// After: 조건-결과 매핑을 위에서 아래로 순서대로 읽을 수 있는 형태로 바꾼다.
const DEAL_TYPE_PRICE_LABEL: Record<DealType, string> = {
  매매: "매매가",
  전세: "전세 보증금",
  월세: "보증금 / 월세",
};

const priceLabel = DEAL_TYPE_PRICE_LABEL[property.dealType] ?? "";
```

### 2. 예측 가능성 (Predictability) — 이름과 시그니처만 보고 동작을 예측할 수 있는가

- 같은 이름/패턴의 함수·훅은 같은 종류의 값을 반환한다 (예: 모든 API 함수의 에러 처리 방식 통일).
- 성공/실패 시 반환 타입을 일관되게 유지한다 (어떤 곳은 `null`, 어떤 곳은 `throw` 하는 식으로 섞지 않는다).
- 이름이나 시그니처에 드러나지 않는 숨은 부작용(내부에서 몰래 전역 상태 변경, API 호출 등)을 만들지 않는다.

#### 예시 — 같은 패턴의 함수는 같은 종류의 값을 반환

```ts
// Before: 이름 패턴(fetchX)은 같은데 하나는 실패 시 null,
// 하나는 실패 시 throw — 호출부마다 에러 처리 방식을 다시 확인해야 한다.
async function fetchProperty(id: number): Promise<Property | null> {
  const res = await api.get(`/properties/${id}`);
  if (!res.ok) return null;
  return res.json();
}

async function fetchUser(id: number): Promise<User> {
  const res = await api.get(`/users/${id}`);
  if (!res.ok) throw new Error("failed to fetch user");
  return res.json();
}
```

```ts
// After: 모든 fetchX 함수는 실패 시 throw로 통일한다.
// 호출부는 fetchX 계열이면 항상 try/catch로 처리하면 된다는 걸 예측할 수 있다.
async function fetchProperty(id: number): Promise<Property> {
  const res = await api.get(`/properties/${id}`);
  if (!res.ok) throw new ApiError("failed to fetch property", res.status);
  return res.json();
}

async function fetchUser(id: number): Promise<User> {
  const res = await api.get(`/users/${id}`);
  if (!res.ok) throw new ApiError("failed to fetch user", res.status);
  return res.json();
}
```

#### 예시 — 숨은 부작용 없애기

```ts
// Before: 이름은 "조회(get)"인데 내부에서 몰래 조회 이력을 기록한다.
// 호출부는 getIsSaved(property)를 여러 번 호출해도 안전하다고 생각하지만
// 실제로는 호출할 때마다 서버에 로그가 쌓인다.
function getIsSaved(property: Property): boolean {
  api.post("/analytics/property-viewed", { propertyId: property.id }); // 숨은 부작용
  return property.saved;
}
```

```ts
// After: 순수하게 값만 반환하는 함수와, 부작용을 일으키는 함수를 분리하고
// 부작용이 있는 쪽은 이름에서부터 드러낸다.
function getIsSaved(property: Property): boolean {
  return property.saved;
}

function reportPropertyViewed(property: Property): void {
  api.post("/analytics/property-viewed", { propertyId: property.id });
}
```

### 3. 응집도 (Cohesion) — 함께 수정되어야 할 코드가 실제로 함께 있는가

- 함께 수정되는 파일들은 같은 디렉토리(feature 단위)에 둔다. 타입/유틸을 무조건 공용 디렉토리로 흩뿌리지 않는다.
- 매직 넘버·설정값은 관련 로직 근처에 상수로 묶어 관리한다.
- 폼(form)처럼 필드 간 연관이 강한 경우, 필드 단위보다 폼 전체 단위의 응집도를 우선 고려한다.

#### 예시 — feature 단위 디렉토리

```text
Before: 매물(property) 기능 하나를 수정하려면 4개 디렉토리를 오가야 한다.
src/
├── components/
│   └── PropertyCard.tsx
├── hooks/
│   └── useProperty.ts
├── types/
│   └── property.ts
└── lib/
    └── propertyFormat.ts

After: 매물 관련 코드를 한 디렉토리에 모아, 기능 단위로 함께 열고 함께 수정한다.
src/
└── features/
    └── property/
        ├── PropertyCard.tsx
        ├── useProperty.ts
        ├── types.ts
        └── format.ts
```

#### 예시 — 매직 넘버는 관련 로직 근처에 상수로

```tsx
// Before: 스켈레톤 개수(6)가 JSX 안에 하드코딩되어 있고,
// 같은 값이 다른 곳에 또 등장하면 둘 다 고쳐야 하는지 알 수 없다.
function PropertyListSkeleton() {
  return (
    <div className="grid grid-cols-3 gap-4">
      {Array.from({ length: 6 }, (_, i) => (
        <PropertyCardSkeleton key={i} />
      ))}
    </div>
  );
}
```

```tsx
// After: 이 로직에서만 쓰는 상수를 바로 위에 선언해
// "몇 개를 보여줄지"를 바꾸는 사람이 어디를 고쳐야 하는지 바로 알 수 있다.
const SKELETON_COUNT = 6;

function PropertyListSkeleton() {
  return (
    <div className="grid grid-cols-3 gap-4">
      {Array.from({ length: SKELETON_COUNT }, (_, i) => (
        <PropertyCardSkeleton key={i} />
      ))}
    </div>
  );
}
```

#### 예시 — 폼은 필드 단위가 아닌 폼 전체 단위로

```tsx
// Before: 필드마다 개별 state — 필드가 늘어날수록 관리 지점이 늘고,
// "검색 조건 초기화"처럼 폼 전체를 다루는 로직을 작성하기 번거롭다.
const [query, setQuery] = useState("");
const [region, setRegion] = useState("all");
const [price, setPrice] = useState("all");
const [buildingType, setBuildingType] = useState("all");

const resetFilters = () => {
  setQuery("");
  setRegion("all");
  setPrice("all");
  setBuildingType("all");
};
```

```tsx
// After: 필드 간 연관이 강한 필터를 하나의 객체로 묶어
// 폼 전체를 하나의 단위로 다룬다 (PropertyFilterBar의 실제 방식).
const [filters, setFilters] = useState<Filters>(DEFAULT_FILTERS);

const resetFilters = () => setFilters(DEFAULT_FILTERS);
const setField = (key: keyof Filters) => (value: string) =>
  setFilters((prev) => ({ ...prev, [key]: value }));
```

### 4. 결합도 (Coupling) — 코드를 수정했을 때 영향 범위가 얼마나 좁은가

- 컴포넌트/훅은 책임을 하나씩만 지도록 분리한다.
- 섣부른 공통화로 서로 무관한 곳을 하나의 추상화로 묶지 않는다 — 적절한 중복은 결합도를 낮추므로 허용한다 (Simplicity First 원칙과 균형 필요).
- Props Drilling이 깊어지면 컴포넌트 합성(composition)이나 Context로 결합도를 낮춘다.

#### 예시 — 책임을 하나씩만 지도록 분리

```tsx
// Before: 데이터 조회, 로컬 저장, 렌더링을 한 컴포넌트가 다 떠맡는다.
// API 응답 형식이 바뀌면 이 컴포넌트 전체를 다시 읽어야 한다.
function PropertyDetailPage({ id }: { id: number }) {
  const [property, setProperty] = useState<Property | null>(null);

  useEffect(() => {
    fetch(`/api/properties/${id}`)
      .then((res) => res.json())
      .then((data) => {
        setProperty(data);
        localStorage.setItem("lastViewedPropertyId", String(id));
      });
  }, [id]);

  if (!property) return <DetailSkeleton />;
  return <PropertyDetailView property={property} />;
}
```

```tsx
// After: 데이터 조회/부작용은 훅으로, 렌더링은 컴포넌트로 책임을 나눈다.
// 조회 방식이 바뀌면 useProperty만, 화면이 바뀌면 PropertyDetailView만 고치면 된다.
function useProperty(id: number) {
  const [property, setProperty] = useState<Property | null>(null);

  useEffect(() => {
    fetchProperty(id).then((data) => {
      setProperty(data);
      rememberLastViewedProperty(id);
    });
  }, [id]);

  return property;
}

function PropertyDetailPage({ id }: { id: number }) {
  const property = useProperty(id);
  if (!property) return <DetailSkeleton />;
  return <PropertyDetailView property={property} />;
}
```

#### 예시 — 섣부른 공통화 지양

```tsx
// Before: 매물 카드와 유저 카드는 우연히 레이아웃이 비슷할 뿐 도메인이 다른데,
// 하나의 컴포넌트로 억지로 묶어 type 분기가 계속 늘어난다.
// 매물 카드만 바꾸고 싶어도 유저 카드에 영향이 없는지 항상 같이 확인해야 한다.
function EntityCard({
  type,
  entity,
}: {
  type: "property" | "user";
  entity: Property | User;
}) {
  if (type === "property") {
    const property = entity as Property;
    return <Card>{property.title}</Card>;
  }
  const user = entity as User;
  return <Card>{user.name}</Card>;
}
```

```tsx
// After: 각자 독립된 컴포넌트로 유지한다.
// 코드는 일부 중복되지만, 한쪽을 고쳐도 다른 쪽에 영향을 주지 않는다.
function PropertyCard({ property }: { property: Property }) {
  return <Card>{property.title}</Card>;
}

function UserCard({ user }: { user: User }) {
  return <Card>{user.name}</Card>;
}
```

#### 예시 — Props Drilling 대신 합성(composition)이나 Context

```tsx
// Before: user는 GlobalNav만 쓰는데, App → PropertyListPage → PropertyGrid →
// PropertyCard까지 3단계를 그냥 통과만 한다. 중간 컴포넌트들이
// 실제로 쓰지도 않는 prop 때문에 서로 결합된다.
function App() {
  const user = useCurrentUser();
  return <PropertyListPage user={user} />;
}
function PropertyListPage({ user }: { user: User | null }) {
  return <PropertyGrid user={user} />;
}
function PropertyGrid({ user }: { user: User | null }) {
  return <PropertyCard user={user} />;
}
function PropertyCard({ user }: { user: User | null }) {
  return <span>{user?.name}</span>;
}
```

```tsx
// After: 실제로 값을 쓰는 컴포넌트가 Context에서 직접 꺼내 쓴다
// (이 프로젝트의 AuthContext / useAuth 방식). 중간 컴포넌트들은
// user와 무관해져 결합도가 낮아진다.
function App() {
  return <PropertyListPage />;
}
function PropertyListPage() {
  return <PropertyGrid />;
}
function PropertyGrid() {
  return <PropertyCard />;
}
function PropertyCard() {
  const { user } = useAuth();
  return <span>{user?.name}</span>;
}
```
