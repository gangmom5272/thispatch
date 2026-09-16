# 리뷰 통계 샘플 집계

현재 구현은 **입력 Parquet의 관측 기록만 계산하는 읽기 전용 미리보기**입니다.
HDFS 원본과 PostgreSQL은 수정하지 않습니다. 운영용 증분 갱신/적재는 아직 연결하지 않았습니다.

## 집계 기준

- `ReviewSchema`에서 필요한 6개 컬럼의 타입을 확인합니다. 암묵적 캐스팅은 하지 않습니다.
- 필수값 NULL, 음수 작성 시각, 잘못된 ID, 작성 > 수정 또는 수정 > 수집 시각은 오류로 중단합니다.
- 동일 리뷰·수정 시각·수집 시각의 집계 관련 값이 충돌하면 임의 선택하지 않고 중단합니다.
- `ReviewSchema.DEDUP_KEY`인 `(recommendationid, updated_ts)`마다 가장 늦은 수집을 선택합니다.
- 활동일은 `TimeRule.statDate(updated_ts)`로 계산합니다. 수정되지 않은 리뷰는 작성 시각과 같습니다.
- 게임·리뷰·KST 활동일마다 가장 최신 수정 버전을 선택합니다.
- 작성일과 활동일이 같으면 신규, 다르면 수정입니다. 당일 작성 후 당일 수정은 신규 한 건입니다.
- 단순 재수집은 새로운 활동이 아닙니다. 서로 다른 날의 관측된 수정 버전은 각각 보존합니다.
- 처음부터 수정된 버전만 있다면 최초 작성일의 평가를 만들어 넣지 않습니다.
- 과거 활동일의 결과는 보유한 관측 기록 기준이며, 당시 전체 리뷰 상태의 완전한 스냅샷이 아닙니다.

`review_count = new_review_count + edited_review_count`입니다.
`negative_count = review_count - new_positive_count - edited_positive_count`입니다.
Spark 집계 개수는 BIGINT입니다. 현재 DB INTEGER로 적재할 때는 적재 담당이 범위를 검사해야 합니다.
`aggregated_at`은 실행 시각이며, 기본키 `daily_stat_id`는 DB 생성 대상으로 출력하지 않습니다.

## 실행

Java 17과 Spark 의존성이 필요합니다. Windows에 Java 17이 없다면 설치된 WSL 환경을 사용합니다.
저장소 루트에서:

```bash
bash gradlew :spark:test :spark:jar --console=plain
/opt/spark/bin/spark-submit --master 'local[2]' \
  --class com.ssafy.thispatch.spark.DailyStatJob \
  spark/build/libs/thispatch-spark.jar 2026-09-11
```

잡은 `HdfsPaths.reviewDeltaOf(date)`만 읽습니다. 인자의 날짜는 **수집 파티션 날짜**이지 집계 활동일 필터가 아닙니다.
스키마, 언어/게임별 건수, 입력/오류 건수, 시각 범위, 집계 결과 최대 100행과 전체 합계를 출력합니다.
HDFS 이름 해석과 NameNode뿐 아니라 실제 DataNode 접근도 가능해야 합니다.
`local[2]`는 소량 샘플 검증용이며 클러스터 분산 실행 검증을 대신하지 않습니다.

## 운영 연결 전 합의할 사항

- 이 delta만으로 계산한 결과를 전체 날짜 통계에 덮어쓰면 안 됩니다.
- 운영 재집계는 영향받은 게임/활동일에 대해 base와 미통합 delta의 충분한 이력을 읽어 수행해야 합니다.
- 동일 입력 재실행 시 누적 덧셈이 아니라 `(appid, stat_date)`의 결과 교체가 필요합니다.
- 현재 V1에 해당 UNIQUE가 없으므로 적재 담당자와 중복 방지 및 빈 결과 처리 정책을 정해야 합니다.
- 입력 컬럼을 먼저 줄인 뒤 버전 중복·일별 최신 선택·집계를 수행합니다. 정확성 검사를 위한 충돌 집계도
  셔플을 발생시키므로 대규모 운영 전 실행 계획과 데이터 품질 검사의 분리 여부를 측정해야 합니다.
- 이 잡은 `weighted_vote_score`를 사용하거나 캐스팅하지 않습니다. 변환 단계의 NULL 지표는 별도입니다.

## 테스트

KST 자정 경계, 같은 날 작성/수정, 과거 수정 버전 보존, 반복 수집/재실행, 미관측 최초 평가,
게임 분리, Parquet 저장/재읽기, 빈 입력, 잘못된 필수값, 충돌 관측을 검증합니다.
테스트에서만 작은 결과를 드라이버로 수집합니다. 실제 잡은 원본 전체 `collect` 및 JDBC를 사용하지 않습니다.

### 2026-09-11 검증 결과

- WSL Java 17에서 `:spark:test :spark:jar` 성공, 테스트 12개 통과.
- 합성 데이터의 실제 Parquet 저장/재읽기와 로컬 Spark 집계를 검증했습니다.
- 실제 HDFS 샘플은 아직 검증하지 못했습니다. `dispatch-master:9000` 파일 목록 조회가
  시간 초과되었고, 현재 해석된 주소 `70.12.108.85:9000`의 TCP 연결 검사도 실패했습니다.
  서버/네트워크 연결을 확인한 뒤 위 미리보기 명령으로 실제 7,959건을 확인해야 합니다.

## band_stat: 플레이타임별 집계

`BandStatAggregator.selectLatestReviews`는 게임·리뷰별로 가장 최신 수정 시각의 버전 한 건을
선택합니다. 같은 수정 시각이면 최신 수집 시각을 사용합니다. 일별 수정 이력을 세는
`daily_stat`과 달리, `band_stat`은 보유 데이터에서 리뷰마다 한 번만 셉니다.

- 작성 당시 플레이타임 `playtime_at_review`(분)를 사용합니다. `playtime_forever`로 대체하지 않습니다.
- 최신 버전의 플레이타임이 NULL이면 과거 버전의 값을 가져오지 않습니다.
- NULL과 음수는 구간 계산에서 제외하고, 게임별 `missing_playtime_count`, `negative_playtime_count`로
  출력합니다. 0분은 유효합니다. `latest_review_count = included_review_count + 두 제외 건수`입니다.
- 필수값 오류와 동일 시각의 상충 관측은 일별 집계와 마찬가지로 중단합니다.
- 유효한 최신 리뷰를 게임·플레이타임별로 먼저 집계합니다. 이 분포의 누적 리뷰 수를 사용해
  25%, 50%, 75%의 정확한 nearest-rank 경계(순번 `ceil(N × 비율)`)를 계산합니다.
- 구간은 `[0,Q1)`, `[Q1,Q2)`, `[Q2,Q3)`, `[Q3,∞)`입니다. 경계와 같은 값은
  해당 경계에서 시작하는 구간에 포함하고, 동점은 나누지 않습니다.
  비어 있는 구간도 리뷰·긍정 수 0으로 남기며 `band_no` 1~4를 유지합니다.
  경계가 같으면 하한과 상한이 같은 빈 구간이 생길 수 있습니다.
- 유효한 플레이타임이 하나도 없는 게임은 통계 행이 없습니다. 제외 현황에는 남습니다.
- `playtime_from`은 포함, `playtime_to`는 미포함인 **분 단위 정수 경계**입니다.
  첫 구간은 0부터, 마지막 구간의 상한은 NULL(제한 없음)입니다.

예: 플레이타임이 10, 20, 30, 40, 50, 60, 70, 80분이면 다음과 같습니다.

| band_no | playtime_from | playtime_to | review_count |
| --- | --- | --- | --- |
| 1 | 0 | 20 | 1 |
| 2 | 20 | 40 | 2 |
| 3 | 40 | 60 | 2 |
| 4 | 60 | NULL | 3 |

20분은 두 번째 구간에 포함합니다. 이 경계는 시간으로 반올림해
재분류하지 말고 분 단위 그대로 사용해야 합니다. 구간은 게임별 데이터 분포에 따라 달라지며
매번 인원이 정확히 25%이거나 모든 게임의 1번 구간이 같은 시간을 의미하지 않습니다.

### 실행 및 운영 연결 주의

저장소 루트에서 위 빌드 명령 실행 후:

```bash
/opt/spark/bin/spark-submit --master 'local[2]' \
  --class com.ssafy.thispatch.spark.BandStatJob \
  spark/build/libs/thispatch-spark.jar 2026-09-11
```

이 잡도 `HdfsPaths.reviewDeltaOf(date)`만 읽는 **샘플 미리보기**입니다. 최신 리뷰별 제외 현황,
구간별 건수(최대 100행), 전체 리뷰·긍정 합계를 출력하고 DB/HDFS에는 쓰지 않습니다.
Spark의 분산 집계 결과 개수는 BIGINT이며, DB INTEGER 적재 시 범위 검사와 기본키 생성은
별도 적재 단계의 책임입니다.

운영에서는 게임별 전체 최신 리뷰를 복원할 수 있는 base와 미통합 delta를 함께 사용해야 합니다.
샘플의 구간 경계를 전체 게임의 통계로 저장하면 안 됩니다. 리뷰가 추가되면 경계가 움직일 수
있으므로 증분 건수만 더하지 않고 영향을 받은 게임의 구간을 다시 계산합니다.
구간 개수가 줄거나 전부 제외되는 경우 이전 구간 행을 남기지 않도록 적재 담당자와
게임 단위 결과 교체 및 `(appid, band_no)` 중복 방지 정책을 합의해야 합니다.
`band_topic_stat`과 리뷰 목록에 구간을 연결할 때도 같은 집계 회차의 동일 경계를 사용해야 합니다.

추가 테스트는 네 구간의 정확한 건수, 동점·빈 구간 유지, 소표본·게임 분리, 최신 버전 선택,
결측·음수·0분, 중복/입력 순서, Parquet 읽기, 최대 정수 경계, 필수값과 충돌 검사를 포함합니다.

2026-09-11 WSL Java 17의 로컬 Spark에서 band 12개와 daily 12개, 총 24개 테스트가
모두 통과했고 JAR 빌드도 성공했습니다. 실제 팀 HDFS 샘플 검증 및 클러스터 처리량 검증은
포함하지 않습니다.

## language_stat: 언어별 집계

`LanguageStatAggregator`는 게임·리뷰별 최신 수정 버전을 선택한 뒤 게임·언어별로
`review_count`, `positive_count`, `aggregated_at`을 계산합니다. 같은 수정 시각이면
최신 수집 시각을 선택합니다. `language_stat_id`는 DB 적재 시 생성합니다.

- 현재 테이블에 날짜/기간 컬럼이 없으므로 날짜별 또는 최근 7일 집계로 해석하지 않습니다.
- 언어와 긍정/부정이 수정되면 최신 값으로 한 번만 셉니다. 리뷰 중복 제거 키에 언어를 넣지 않습니다.
- 플레이타임 결측 때문에 언어 집계에서 제외하지 않습니다. 플레이타임은 입력으로 요구하지 않습니다.
- `voted_up`의 추천 여부로 긍정 수를 셉니다. LLM 감정 분류나 언어별 요약/대표 리뷰 생성은 하지 않습니다.
- `language_code`는 받은 값을 그대로 사용합니다. NULL·빈 문자열·앞뒤 공백·20자 초과는
  오류로 중단하고 임의로 다른 언어나 `unknown`으로 바꾸지 않습니다.
- 코드가 실제 DB `language` 테이블에 존재하는지는 적재 단계에서 확인해야 합니다.
  현재 로컬 Flyway에는 언어 시드 INSERT가 보이지 않으므로 적재 담당자와 확인해야 합니다.
- 동일 리뷰의 같은 수정·수집 시각에 서로 다른 언어나 추천 여부가 있으면 충돌로 중단합니다.

### 실행

```bash
/opt/spark/bin/spark-submit --master 'local[2]' \
  --class com.ssafy.thispatch.spark.LanguageStatJob \
  spark/build/libs/thispatch-spark.jar 2026-09-11
```

다른 잡과 마찬가지로 `HdfsPaths.reviewDeltaOf(date)`만 읽는 미리보기입니다. 입력 건수,
게임·언어별 결과 최대 100행, 게임별 합계 최대 100행과 전체 합계를 출력합니다.
DB/HDFS에는 쓰지 않으며, delta 샘플 결과를 전체 게임의 통계로 덮어쓰면 안 됩니다.
운영 시에는 전체 최신 리뷰를 복원한 뒤 게임 단위 결과 교체가 필요합니다.
리뷰 언어 변경으로 사라진 언어의 이전 행도 제거해야 하며,
`(appid, language_code)` 중복 방지와 INTEGER 범위 검사는 적재 담당과 합의해야 합니다.

### 검증 상태

사용자 요청에 따라 언어 집계의 실행 테스트와 실제 HDFS 샘플 검증은 마스터 연결 후로 미룹니다.
게임/언어 분리, 언어 변경, 최신 버전 우선순위, 반복 입력, 빈 입력, 코드/필수값 검증,
충돌 관측 및 Parquet 재읽기에 대한 테스트 11개를 작성했습니다. 기존 24개 통과 기록은
daily/band에만 해당하며 새 언어 테스트까지 통과했다는 의미가 아닙니다.
2026-09-11 WSL Java 17에서 `:spark:compileTestJava :spark:jar`는 성공했습니다.

테스트를 실행하지 않고 소스와 테스트 코드의 컴파일 및 JAR 생성만 확인하려면:

```bash
bash gradlew :spark:compileTestJava :spark:jar --console=plain
```

마스터 연결 후 테스트 실행은 기존 `:spark:test` 명령을 사용하고, 실제 HDFS 검증은
각 미리보기 잡을 별도로 실행합니다. 자동 테스트 자체는 로컬 Spark와 합성 데이터를 사용합니다.

## band_topic_stat: 토픽별 추천·비추천 집계

`BandStatAggregator`는 이미 추천·비추천을 모두 포함합니다. `review_count`가 전체,
`positive_count`가 추천 수이고 비추천 수는 둘의 차이입니다. 4분위 경계와 동점 처리도 유지합니다.

`BandTopicStatAggregator`는 같은 회차의 최신 리뷰와 `band_stat` 경계를 받아, 해당 구간에서
각 토픽을 언급한 추천 리뷰 수와 비추천 리뷰 수를 셉니다. LLM이나 감정 분석은 사용하지 않습니다.
추천 리뷰의 모든 토픽에 추천 표시가 반영되며, 이는 각 토픽에 대한 칭찬 여부를 뜻하지 않습니다.

```java
Dataset<Row> latest = BandStatAggregator.selectLatestReviews(reviews);
Dataset<Row> bands = BandStatAggregator.aggregateLatestReviews(latest, aggregatedAt);
Dataset<Row> topicCounts = BandTopicStatAggregator.aggregateLatestReviews(latest, bands, topicAssignments);
```

`topicAssignments`는 집계 함수 **내부 연결 형식**으로만 정의했습니다.
`appid` LONG, `recommendationid` LONG, `updated_ts` LONG, `topic_id` SHORT이며,
리뷰·수정 버전·토픽마다 한 행입니다. 실제 HDFS 토픽 산출물의 확정 스키마가 아닙니다.
DB의 `review_id`와 Steam의 `recommendationid`는 같은 키로 간주하지 않습니다.

- 입력 연결부에서 분류 실행 회차 하나를 선택한 뒤 전달해야 합니다. 서로 다른 분류 회차의
  토픽을 단순히 합치면 안 됩니다. 파일 형식·스키마·분류 완료 상태는 AI 담당과 합의가 필요합니다.
- 리뷰 ID만 아니라 `updated_ts`까지 일치해야 연결합니다. 과거 본문의 토픽은 현재 리뷰에 붙이지 않습니다.
- 같은 리뷰·토픽이 반복되어도 한 번만 셉니다. 여러 토픽이 붙은 리뷰는 각 토픽에 한 번씩 셉니다.
- 플레이타임 결측·음수 제외와 구간 경계는 `band_stat`과 같습니다. 토픽이 붙은 리뷰만으로
  4분위를 다시 계산하지 않습니다.
- 출력은 `appid`, `band_no`, `topic_id`, `positive_count`, `negative_count`입니다.
  적재 담당자가 **같은 회차의** `(appid, band_no)`를 DB의 `band_stat_id`로 연결해야 합니다.
- 언급 리뷰 수는 추천 수 + 비추천 수, 언급률의 분모는 해당 구간 전체 리뷰 수입니다.
  토픽별 합계는 다중 라벨 때문에 구간 전체 리뷰 수보다 클 수 있습니다.
- 토픽 미연결 리뷰에는 임의의 토픽을 붙이지 않습니다. 현재 함수만으로는 미분류·실패·토픽 없음이
  구별되지 않으므로 분류 완료/연결률 확인 전에는 완성된 토픽 통계로 표시하거나 적재하면 안 됩니다.
  언급 건수 0과 미처리를 같은 것으로 표시하지 않아야 합니다.

### DB 변경과 진행 상태

`V7__add_band_topic_positive_count.sql`은 `positive_count`와 음수 방지 CHECK를 추가합니다.
기존에는 비추천 수만 저장했으므로 과거 행의 추천 수를 0으로 추정하지 않고 NULL로 둡니다.
새 집계 결과는 0을 포함한 실제 건수를 넣고, 과거 행은 게임 단위로 구간과 토픽을 함께 재집계합니다.
재집계 전 NULL을 0으로 바꿔 비율을 표시하면 안 됩니다. 모두 재집계한 뒤 NOT NULL 전환이 가능합니다.
DB에는 적용하지 않았습니다. 배포 시 Flyway가 실행할 변경 파일만 작성했습니다.

토픽 HDFS 읽기/실행 잡과 실제 적재 연결은 아직 만들지 않았습니다. 공통 모듈에서 산출물 스키마가
확정된 후 연결합니다. 14일 필터나 고정 시간 구간은 이번 변경에 넣지 않았습니다.
집계 테스트 10개를 작성했으나 사용자 요청대로 실행은 보류했습니다. 검증 완료로 간주하지 않습니다.


## 패치 공지 판정과 집계 연결

`NewsToParquet`가 `PatchClassificationProcessor.classifyRows(news)`를 호출해 모든 수집본에
공통 `PatchClassifier`의 `is_patch`, `patch_reason`을 붙인다. 판정으로 원문 행을 제외하지 않는다.
`0:unjudged`는 아직 판정하지 않은 경우에만 쓰며 실제 판정 함수는 반환하지 않는다.

`PatchStatAggregator`는 저장된 `is_patch`를 직접 읽고 최신 수집본의 true만 집계한다.
게시 시각은 `published_ts`를 그대로 사용한다. AI 호출이나 DB 적재는 포함하지 않는다.

`PatchClassificationJob [news_raw Parquet 경로]`는 최신 수집본을 재판정하는 읽기 전용 미리보기다.
입출력 계약과 실행 예시는 [PATCH_CLASSIFICATION.md](PATCH_CLASSIFICATION.md#news_raw-데이터-연결)를 참고한다.
