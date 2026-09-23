# 유사 사례 검색 필터 순서 비교

`compare_case_search.sql`은 변경 전(30개 선정 후 변경 종류·방향 필터)과 변경 후
(모든 조건을 통과한 최대 30개 선정)를 같은 벡터·DB 스냅샷에서 비교한다.
읽기 전용 트랜잭션이며 통계·데이터·전역 설정을 바꾸지 않는다. 질의 제한 시간은 30초다.

## 입력 준비

1. 실제 검색 요청의 확정 슬롯을 고정한다. 한국어와 영어를 번역해 섞지 않는다.
2. 백엔드 `CaseSearchService.toPlanChange`와 같은 문장을 `/embed/query`에 **한 번** 보내
   벡터를 저장한다. `title`은 빈 문자열이고, 임베딩 응답의 `model`을 그대로 사용한다.
   문장 틀은 `Decrease 저격총 (weapon) 연사 속도 10%. Scope: <실제 scope>`와 같다.
   수치·범위가 없는 슬롯은 해당 부분을 생략한다. 실행 전후 벡터를 다시 만들지 않는다.
3. 슬롯마다 `change_type`, `direction`(소문자), `embedding`(512개 숫자 배열)을 묶어
   `slots.json`에 배열로 저장한다. 최대 20개 슬롯을 사용한다. 예시 구조는 다음과 같다.

```text
[
  {"change_type": "modify", "direction": "decrease", "embedding": [실제 벡터 512개]},
  {"change_type": "modify", "direction": "decrease", "embedding": [실제 벡터 512개]}
]
```

## 실행

psql과 PostgreSQL·pgvector가 있는 환경에서 기존 DB 접속 환경변수 또는 서비스 설정을 사용한다.
아래 명령은 저장소 루트 기준이며, 비밀번호를 명령줄에 직접 쓰지 않는다.

```powershell
# PowerShell 7. 큰 벡터를 명령줄 인자로 넣지 않고 표준 입력으로 전달한다.
$querySlots = Get-Content -Raw ./slots.json | ConvertFrom-Json -NoEnumerate
$slotJson = ConvertTo-Json -InputObject $querySlots -Depth 10 -Compress
$escapedSlots = $slotJson.Replace('\', '\\').Replace("'", "\'")
$comparisonSql = Get-Content -Raw backend/tools/compare_case_search.sql
"\set slots '$escapedSlots'`n$comparisonSql" | psql -X --set='genre_ids={실제 장르 ID 목록}' --set='model=임베딩 응답의 model'
```

`genre_ids`는 실제 검색과 같은 배열이어야 한다. 빈 배열 `{}`만 전체 장르를 의미한다.
`ef_search`는 기본 100이며 운영 설정이 다르면 `--set=ef_search=실제값`을 추가한다.
잘못된 JSON·벡터 형식이나 제한 시간 초과는 실패로 처리된다.

## 확인할 결과

- `matched_chunks`: 슬롯별 검색 결과의 합계. 동일 청크가 여러 슬롯에 걸리면 각각 센다.
- `distinct_patches`: 모든 슬롯에서 검색된 패치의 중복 제거 후 개수. 같은 패치에 몰려도 추가 보충하지 않는다.
- `evidence`: 슬롯 번호·패치 ID·청크·유사도·원문. 추가로 찾은 사례가 실제 대상과 속성에 관련되는지
  사람이 판정한다. 개수가 늘었다는 이유만으로 검색 품질 향상으로 판정하지 않는다.

HNSW 근사 검색이라 후보 집합은 정확 검색과 다를 수 있고, 탐색 한도에 걸리면 30개 미만일 수 있다.
이 스크립트는 저장소의 후보 선정 단계를 비교하며 카드 생성·최종 API 응답 전체를 재현하지 않는다.
psql의 실행 시간은 두 버전을 합친 시간이라 개별 검색 지연 시간 비교에 사용하지 않는다.
운영 데이터에서의 수치와 관련성 판정은 아직 기록하지 않았다.

한국어/영어 질의 비교는 별도 실행으로 남긴다. 언어마다 벡터를 한 번 만들고 각각 before/after를
비교해야 필터 변경 효과와 질의 언어 효과를 구분할 수 있다. 원문 데이터·벡터 파일은 커밋하지 않는다.
