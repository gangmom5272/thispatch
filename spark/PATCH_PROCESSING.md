# 패치 변경점 추출·전후 리뷰 집계

## 2026-09-14 실데이터 보완: change-rules-2

- `PatchChangeSectioner.split(rawBody)`가 BBCode/HTML h1~h6 기준으로 `headingPath`, `text`를 반환한다. 같은 구역의 평문 대상명, 하위 목록, 후속 설명을 보존한다. 다른 구역의 본문을 섞지 않는다. 소제목이 없으면 원문 본문을 한 구역으로 유지한다.
- 이것은 **의미 구역 준비 함수**이지 토큰 수 제한을 맞춘 최종 임베딩 청크 생성기가 아니다. 긴 구역을 다시 나눌 때도 대상명·소개 문맥을 보존해야 한다. HDFS/DB에 자동 연결하지 않았다.
- 추출기에 `extract(headingPath, text)` 오버로드를 추가했다. 예정·과거·홍보 구역 제목을 제외 근거로 사용하지만, 제목으로 대상 종류나 조건을 추측하지 않는다.
- `PatchChangeProcessor`는 선택적 STRING `heading_path`를 추출기에 전달한다. 기존 `chunk_id,text` 입력도 동작하지만, 상위 제목이 있는 데이터는 반드시 함께 전달해야 예정 구역 보호가 작동한다. DB 출력 컬럼은 그대로다.
- `퀘스트명 - Fixed`, Corrected/Disabled, 명확한 now/no longer 동작을 지원한다. 현재 변경을 설명하는 관계절의 과거 removed 때문에 Added 전체를 버리지 않는다.
- 문장마다 자르지 않고 독립적인 다음 변경 동사가 있을 때만 나눈다. 괄호·큰따옴표 내부는 분리하지 않고, `and fixed it ...`처럼 앞 대상을 참조하는 설명은 함께 둔다. 수치 증가/감소를 분리해야 하지만 주어 관계가 불명확한 복수 동사는 여전히 보류한다.
- 후속 설명은 유지하며 인용은 입력의 실제 연속 부분 문자열로 보존한다. `evidence_quote`가 항목 전체의 설명을 포함할 수 있다. 고유 대상명이나 상위 제목이 인용에 없으면 `chunk_id`로 같은 청크와 `heading_path`를 함께 읽어 비교해야 한다. Qwen 비교 API 자체는 이번 작업 범위가 아니다.
- 명시적 `We've added ... in this update` 소개 바로 뒤의 `이름 - 설명`을 추가 항목으로 처리한다. 중간에 다른 문단/구역/홍보가 나오면 이 근거를 끊는다. 소개 없이 서술형 문장을 모두 추가로 추측하지 않는다.
- 문장 어디에든 player/enemy가 등장하는 방식 대신 직접 대상 구절의 시작에서만 종류를 정한다. Roller Skates/쉐이더를 player로 연결하던 사례는 unknown으로, whips enemy collision은 weapon으로 처리한다. unknown은 추출 실패나 변경 없음이 아니다.

아래 초기 구현 설명 중 문장 분리와 미래 표현 처리는 이 v2 규칙을 우선한다. 규칙 기반이라 지원하지 않는 표현·플랫폼 조건·고유명사의 종류는 여전히 자동 확정할 수 없다. `valid`를 정답 또는 인간 검수 완료로 사용하면 안 된다.

v2 최종 검증: WSL Java 17에서 `:spark:test :spark:jar --offline` 성공. **138개 테스트 통과**, 실패/오류 0개(기존 116개 + 신규 22개). 실제 Steam 공지 5개도 로컬 감사 실행기로 재검토했다. BG3 목록 4개 유지, CS2 최상위 35개 포착, Cyberpunk 최상위 28개 포착, Terraria 427행, No Man's Sky는 소개가 포함된 구역에서 콘텐츠 9개를 추출했다. 포착 건수는 의미 정확도나 독립 평가 점수가 아니다. DB/HDFS 적재는 하지 않았다.

## 구현 범위

`spark/` 안에 순수 규칙 추출기, Spark 변경점 처리기, Spark 통계 집계기를 구현했다.
Flyway V1/V2를 기준으로 만들었으며 마이그레이션이나 DB 데이터는 수정하지 않았다.
LLM·임베딩 실행, 토큰 제한 기반 최종 청크 생성, 적용일 자동 추출, DB 적재는 이 코드에 포함하지 않는다.
입력 어댑터와 드라이버 적재 통로는 팀 계약이 연결된 후 작업해야 한다.

## patch_change

### 입력과 출력

`PatchChangeExtractor.extract(chunkText)`는 분리와 서식 정리가 완료된 **현재 패치의 원문 청크**를 받는다.
`PatchChangeProcessor.extract(chunks, changeTypes, directions, targets)`는 다음 입력을 사용한다.

- chunks: `chunk_id LONG`, `text STRING`. DB에 실제 생성된 청크 ID를 전달한다. 같은 ID의 다른 본문은 오류다.
- 세 가지 lookup 맵: 실제 테이블의 `code → SMALLINT id`. DB 조회는 드라이버 적재/입력 담당이 수행한다.
- 반환 Dataset: `chunk_id`, `change_type_id`, `direction_id`, `target_type_id`, `evidence_quote`, `validation_status`.
- `patch_change_id`는 DB가 생성한다. 시드 입력 순서로 FK 숫자를 하드코딩하지 않는다.

### 규칙과 이유

- `Added`, `Removed`, `Fixed`, `Resolved`, `Increased`, `Decreased`, `Reduced`, `Adjusted`, `Changed`, `Updated`, `Improved`, `Reworked`, `Deprecated` 등 명시적인 영문 변경 표현을 처리한다.
- `Weapon damage was increased from 1.2 to 1.5` 같은 대상 먼저 나오는 문장도 처리한다.
- 줄·문장·세미콜론과 다음 변경 동사가 명시된 `and` 경계로 분리한다. 소수점은 끊지 않는다.
- 추가/삭제/수정/지원 중단은 V2의 기존 코드로 변환한다. 증가/감소는 direction으로 구분한다.
- `Improved`를 수치 증가나 유저에게 긍정적인 영향으로 해석하지 않는다.
- 대상 종류는 명시된 일반 명사에서만 판단한다. `Axebot` 같은 고유명사는 unknown이다.
- 대상 종류가 여러 개 섞였거나 버그 설명의 조건 안에만 명사가 있으면 임의로 하나를 고르지 않는다.
- 상위 제목을 대상 종류나 적용 조건으로 자동 상속하지 않는다. 청크 밖 다른 맵 이름과 연결하지 않는다.
- 원문에 있는 문구를 evidence로 그대로 남긴다. 숫자 전후 값과 단위는 별도 컬럼을 추가하지 않고 evidence에 보존한다.
- `valid`: 지원 규칙에 맞고 유형·방향·대상 필드가 규칙에 따라 구성됨. 사람이 검증한 정답이라는 뜻이 아니다.
- `partial`: 변경 표현은 추출했지만 대상 종류를 unknown으로 남김.
- 미래 변경·미적용을 명시한 청크는 추출하지 않는다. 여러 변경이 한 문장에 불명확하게 섞인 경우도 건너뛴다.

추출 결과가 0행이라고 실제 변경이 없다는 뜻은 아니다. 서술형 변경, 한글 등 미지원 표현은 **원래 patch_chunk에 남는다**.
현재 코드가 extraction_status/embedding_status를 갱신하거나 청크를 삭제하지 않는다. 입력 담당은 미지원 청크를 별도 처리·검토할 수 있어야 한다.
영어 중심의 초기 규칙이며 전 게임의 변경점을 완전하게 추출하는 구현은 아니다.

### 적재 연결 시 주의

같은 chunk_id로 다시 추출한 결과를 단순 INSERT하면 중복된다. V2에는 변경점 중복 방지 키가 없다.
드라이버 적재 단계에서 **현재 원문 버전인지 확인하고 해당 청크의 기존 변경점 교체를 한 트랜잭션으로** 처리하는 계약이 필요하다.
이 문서는 적재 정책 제안일 뿐, 현재 삭제·INSERT 코드를 실행하지 않았다.
Spark 변환은 결정적이지만, 이것만으로 DB 재실행 중복까지 방지되는 것은 아니다.

## patch_stat

### 내부 입력 계약

`PatchStatAggregator.aggregate(reviews, patches, coverageStart, coverageEndExclusive, aggregatedAt)`를 호출한다.

- reviews: `common.ReviewSchema`의 `appid`, `recommendationid`, `created_ts`, `updated_ts`, `collected_ts`, `voted_up`.
- patches: `gid STRING`, `appid LONG`, `patched_ts LONG`, `eligible_for_review_stats BOOLEAN`.
- 패치 입력은 **모듈 내부 계약**이며 합의된 공지 HDFS 스키마나 DB 신규 컬럼이 아니다.
- patched_ts는 확인된 적용시각이다. 날짜만 확인된 경우 `TimeRule.startOfDay(적용일)`로 전달한다. 게시일로 자동 대체하지 않는다.
- eligible_for_review_stats는 적용 대상과 시점이 검증된 경우에만 true다. 판정기 PATCH/DEFAULT만 보고 true로 바꾸면 안 된다.
- coverageStart/End는 모든 대상 게임의 리뷰 수정 이력이 수집 완료되었다고 호출자가 보증하는 KST 날짜 구간이다.
  파일의 min/max 날짜나 하루치 샘플만 보고 완전 수집으로 간주하면 안 된다.
- 마감 이전 수집된 관측만 사용하며 수집 이후에 알려진 정보는 해당 실행에 포함하지 않는다.

### 계산 기준

적용일 D는 `TimeRule.statDate(patched_ts)`로 구한다. 정확한 시각이 있더라도 이번 집계는 날짜 단위로 통일한다.

- 이전: `[D-7일 00:00 KST, D일 00:00 KST)`.
- 이후: `[D일 00:00 KST, D+7일 00:00 KST)` — 적용일을 포함한다.
- 따라서 당일 패치 적용 전 몇 시간의 리뷰도 이후 구간에 포함될 수 있다. 날짜 단위 비교의 명시적인 한계다.
- 리뷰 버전의 `updated_ts`가 해당 구간에 있을 때만 집계한다. 작성일 기준 신규 리뷰만 세는 방식은 아니다.
- **패치·이전/이후 구간·리뷰 ID별 마지막 수정 버전 1개**를 사용한다. updated_ts 내림차순, 같으면 collected_ts 내림차순이다.
- 같은 리뷰가 이전과 이후에 각각 수정됐다면 양쪽에 한 번씩 들어갈 수 있다.
- 해당 구간 안에서 관측한 변경이 없는 옛 리뷰를 전체 평점 스냅샷처럼 포함하지 않는다.
- 관측하지 못한 최초 작성 시점의 추천 여부를 나중 버전으로 만들어내지 않는다.
- 추천 여부는 Steam `voted_up`만 사용한다. LLM 감정 분석은 없다.
- 긍정률 = 추천 리뷰 수 / 리뷰 수 × 100. 리뷰 0건이면 긍정률 NULL.
- delta_pct = 이후 긍정률 - 이전 긍정률, 단위는 **%p**. 한쪽이 NULL이면 차이도 NULL이다.
- 비율은 반올림 전 값을 빼고 최종 소수 둘째 자리에서 표시한다.
- 날짜 미확인, 대상 미승인, 양쪽 7일 이력 미완료 또는 이후 7일이 아직 지나지 않은 패치는 완료 행을 반환하지 않는다.
- 여러 패치 기간이 겹치면 각각 독립적으로 계산한다. 함께 바뀐 패치·이벤트 효과를 분리하거나 인과관계를 추론하지 않는다.

### 출력

V1 patch_stat의 `gid`, `appid`, `patched_at`, `before_review_count`, `before_positive_pct`,
`after_review_count`, `after_positive_pct`, `delta_pct`, `stat_date`, `aggregated_at`을 반환한다.
건수는 INTEGER 범위를 넘으면 오류를 내고, 비율은 DECIMAL(5,2)로 반환한다.
patched_at은 전달받은 적용시각을 보존한다. stat_date는 실행 시각의 KST 날짜, aggregated_at은 호출자가 전달한 고정 실행시각이다.
입력 원본의 unix 초는 바꾸지 않고 최종 출력에서만 시각 타입으로 변환한다.

전체 리뷰를 드라이버로 collect하지 않고 Spark에서 조인·기간별 중복 제거·집계한다.
조인 전에 날짜·대상 게임으로 필터링하며, 강제 broadcast는 하지 않는다.
집계 결과를 PostgreSQL에 적재하는 코드는 없고 익스큐터 JDBC도 없다.

## 브랜치와 Jira

패치 판정·변경점 추출·전후 통계는 `feat/patch-analysis`에서 관리한다.
리뷰 집계 브랜치의 공통 테스트 설정을 이어받아 분기했으며, 각 기능은 해당 테스트와 함께 커밋하고 통합 문서는 별도 커밋한다.

1. Steam 공지 규칙 기반 패치 판정 및 테스트 구현
2. 패치 변경점 규칙 기반 분리·추출 및 테스트 구현 (patch_change)
3. 패치 전후 7일 리뷰 반응 집계 및 테스트 구현 (patch_stat)

## 검증 결과 (2026-09-14)

`./gradlew :spark:test :spark:jar --offline` 성공. spark 모듈 **116개 테스트 모두 통과**, 실패·오류 0개.

- 변경점 순수 규칙 테스트 13개: 명시적 동사, 전후 수치·소수점 보존, 고유명사 unknown, 조건과 대상 구분, 복수 변경·부정·예정 문장 등.
- 변경점 Spark 처리 테스트 4개: 실제 조회 ID를 가정한 임의 lookup 번호 매핑, 중복 입력, 충돌 청크·누락 코드 거부, 빈 입력.
- 패치 통계 테스트 8개: KST 경계, 기간별 마지막 수정 버전, 늦은 수집, 관측되지 않은 원본, 중복·중첩 패치, 0건/NULL, 마감·수집 범위, 반올림, 입력 오류, 로컬 Parquet 읽기.
- 기존 리뷰 집계 45개와 패치 판정 46개도 함께 통과했다.

이번 새 로직은 합성 입력과 로컬 Spark/Parquet로 검증했다. 실제 마스터 HDFS의 리뷰 전체 이력, 실제 DB 청크·lookup과 연결한 통합 검증은 아직 하지 않았다.
실제 Steam 공지 전체의 변경점 추출 정확도를 측정한 결과도 아니다. 기존 패치 판정 273건 검증과 이번 변경점 추출 테스트는 구분한다.
