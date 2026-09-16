# Steam 공지 패치 판정

## 2026-09-16: 표본 검수에 따른 판정 보완 (patch-rules-5)

- is here는 배포 확정 표현으로 추가하지 않는다. 버전 번호, is available, 외부 상세 링크만으로도 확정하지 않는다.
- BBCode는 태그 이름 전체가 맞을 때만 제거한다. [Update v0.9.7], [Patch], [Items] 같은 실제 제목·본문을 서식으로 지우지 않는다.
- 버전 번호만 있는 제목은 실제 변경 문장이 두 개 이상 있을 때 인정한다.
- [Added]/[Fixed] 접두어, 번호 목록, Added/Fixed 아래 항목, '도구명: fix ...' 형식, 주어 뒤의 완료형 변경 문장을 인식한다.
- Fixes 등 명시된 변경 소제목 안에서는 문장 끝의 fixed 같은 변경 표현도 인식한다. 일반 본문의 기능 설명에 동일한 기준을 적용하지 않는다.
- We're live with a hotfix는 실제 변경 문장이 함께 있을 때 배포 근거로 사용한다.
- 예정 점검 제목, 예정된 버전/업데이트, will undergo a flash update는 적용 패치로 확정하지 않는다. 조건문·미래형 설명도 완료된 변경 문장으로 세지 않는다.
- 판정 인터페이스, 게시일 기준 정책은 유지한다.

검수에 사용한 60건은 우리 미확정/develop true인 6,608건에서 뽑은 개발 표본이다.
수정 후 표본 결과와 전체 95,913건 판정 분포는 정확도 점수가 아니다.
is here나 링크만 있는 공지 등은 추가 근거가 없으면 미확정으로 남는다.


### 수정 후 검증

- 최종 판정·계약·Dataset 연결 테스트 87개 통과, 실패/오류 0개. JAR 빌드 성공.
- 같은 실제 공지 95,913건을 수정 전후 함수로 실행했다. PATCH 22,795 → 23,814, NOT_PATCH 2,450 → 3,814, REVIEW_REQUIRED 70,668 → 68,285.
- 기존 미확정에서 PATCH로 바뀐 건수는 다음 JSON의 transitions에 기록했다. 전체 정답 라벨이 없으므로 증가분 전부가 정답이라는 의미는 아니다.
- 60건 개발 표본 중 PATCH로 검수한 30건은 13건을 새로 인식하고 17건은 미확정이다. 제외 대상 22건, 테스트 전용 2건, 근거 부족 6건에서는 PATCH 확정이 없었다.
- 별도로 뽑은 판정 변경 사례 20건 중 17건을 읽고 회귀를 보완했다. 나머지 3건은 수동 검수하지 않았으며, 보완에 사용한 표본을 독립 정확도 평가로 표시하지 않는다.
- 긴 서론의 적용 예고는 첫 완료 변경 문장 이전까지 확인한다. 완료 변경 뒤의 차기 업데이트 계획으로 현재 패치를 취소하지 않는다.
- [규칙 5 검증 결과와 소스 해시](tools/patch-classification-validation/results-rule5-20260916.json)

## news_raw 데이터 연결

2026-09-16 합의: `PatchClassifier`를 공통 판정 함수로 사용한다. 부록 A의 예전 PoC 규칙은 사용하지 않는다.
패치 판정·전후 리뷰 집계는 Spark에서 수행하며 AI 호출이나 DB 적재는 포함하지 않는다.

### 변환 → 판정 저장 → 집계

1. `NewsToParquet.toOurShape(raw)`가 원본 필드를 `NewsSchema.NEWS_SOURCE`에 맞춘다.
2. `PatchClassificationProcessor.classifyRows(news)`가 **모든 수집본**에 정본 판정을 적용한다.
3. `NewsSchema.NEWS_RAW`의 원문 13개 필드와 `is_patch`, `patch_reason` 2개 필드로 Parquet을 쓴다.
4. `PatchStatAggregator.aggregate(...)`가 `collected_ts`가 있으면 gid별 최신 수집본을 먼저 고르고 `is_patch=true`만 집계한다.
   먼저 true를 걸러내면 최신 본문이 비패치로 바뀌어도 과거 true가 살아남으므로 순서를 바꾸지 않는다.

판정 결과로 원문 행을 삭제하지 않는다. 기존 gid/collected_ts 중복 제거는 유지하며 서로 다른 수집 시각은 보존한다.
게시 시각 `published_ts`도 그대로 보존한다. 패치 결정일은 이 게시 시각의 KST 날짜다.
재변환은 보존된 원본 JSON에서 현재 규칙으로 다시 판정한다. 변환 코드에 판정 규칙을 복사하지 않는다.
DB 재적재와 기존 판정 보호는 적재 담당(-25)의 범위다.

### 합의된 출력

| 상태 | is_patch | patch_reason |
|---|---|---|
| 패치 확정 (`PATCH`) | true | `PATCH_TITLE`, `PATCHNOTES_TAG` 등 실제 사유 |
| 비패치 (`NOT_PATCH`) | false | `ANNOUNCEMENT_OR_PREVIEW` 등 제외 사유 |
| 근거 부족·충돌 (`REVIEW_REQUIRED`) | false | `MISSING_TITLE_OR_BODY`, `TAG_CONFLICT_...` 등 실제 사유 |
| 아직 판정 안 함 | false | **`0:unjudged`** |

`0:unjudged`는 판정 전 적재용 예약값이며 `classify()`가 반환하지 않는다.
근거 부족은 이미 판정을 실행한 결과이므로 미판별과 다르다. 사유는 대문자 코드 그대로 저장하며 임의로 숫자 접두어를 붙이지 않는다.
`scope`는 진단 정보다. 합의된 boolean은 `decision == PATCH`이며 scope에 따라 별도 false 변환을 하지 않는다.
집계는 `patch_reason`을 재해석하거나 다른 판정 함수를 호출하지 않는다.
과거 판정 필드가 없는 Parquet은 공통 스키마로 읽으면 추가 필드가 null이다. 자동 확정하지 않으며 재변환 전까지 집계에서 제외한다.

### 공통 Java 함수

```java
PatchClassifier.Result result = PatchClassifier.classify(title, contents, tags);
boolean isPatch = result.isPatch();
String patchReason = result.reason();
```

- 서명: `public static Result classify(String title, String contents, List<String> tags)`.
- 선택 오버로드: `classify(String sourceGameName, String title, String contents, List<String> tags)`.
- Dataset 입력: `NewsSchema.NEWS_SOURCE` 전체 컬럼. title/contents/feed_tags/published_ts 결측은 허용한다.
- `feed_tags`는 쉼표 연결 STRING이며 처리기가 목록으로 변환한다. 원본 tags는 STRING 배열이다.
- 검증된 게임명을 가진 호출자는 선택적 STRING `source_game_name`을 추가할 수 있다. 공지에서 이름을 추측하지 않는다.
- 식별자·수집 시각 오류나 같은 gid/collected_ts에 서로 다른 원문이 있으면 명시적으로 실패한다.
- `classifyRows`는 입력 행 수를 보존하며, 기존 판정이 있더라도 원문으로 재계산해 대체한다.
- `classify`는 미리보기용으로 gid별 최신 수집본만 고른다. 변환기에서는 `classifyRows`를 사용한다.

Dataset 처리기는 `is_patch`, `patch_reason` 외에 진단용 `patch_decision`, `patch_scope`,
`patch_stage`, `patch_evidence`, `patch_rule_version`도 반환한다. 변환기는 합의한 15개 필드만 Parquet에 저장한다.

### 읽기 전용 미리보기

```bash
spark-submit --class com.ssafy.thispatch.spark.PatchClassificationJob thispatch-spark.jar
# 기본 입력: HdfsPaths.NEWS_RAW

spark-submit --master 'local[2]' --class com.ssafy.thispatch.spark.PatchClassificationJob thispatch-spark.jar /path/to/news_raw
```

미리보기는 원문을 현재 규칙으로 다시 판정해 건수와 예시를 표시하며 파일·DB에 쓰지 않는다.
운영 HDFS 재변환·배포는 이 코드 수정과 별개다.


### 실제 공지 연결 검증 (2026-09-15)

보관한 Steam 공지 390개 게임, 95,915행을 NewsSchema.NEWS_RAW 형태의 로컬 Parquet으로 준비하고
새 `PatchClassificationJob`을 실제 실행했다. 중복 공지 2행을 최신 수집본으로 정리한 **95,913건 전부**가 반환됐다.

| 판정 | 건수 |
|---|---:|
| PATCH | 22,795 |
| NOT_PATCH | 2,450 |
| REVIEW_REQUIRED | 70,668 |

- 이는 전체 공지에 대한 판정별 건수이며 정확도 점수가 아니다. 검토 필요 비중이 높고 운영용 자동 판정 품질은 별도 검증이 필요하다.
- 운영 HDFS에 접속한 결과가 아니라, 이전에 보관한 실제 공지를 현재 원문 스키마로 변환해 로컬 Spark에서 검증한 결과다.
- 게시 시각·본문·태그는 원본을 사용했다. 기존 감사 자료에는 행별 collected_ts가 없으므로 각 수집 실행의 완료 시각을 **검증용 입력 시각**으로 사용했다. 운영 수집 시각의 정확성을 검증한 것은 아니다.
- 실제 news_raw처럼 source_game_name을 추가하지 않았다. 타 게임 명시 공지는 검증된 게임명 부재로 검토 대상이 될 수 있다.
- 전체 Spark **169개 테스트 통과**, 실패·오류 0개, JAR 빌드 성공. 신규 연결 테스트 8개로 Parquet 읽기, 3상태 보존, 최신 본문 선택, 빈 본문, 태그 변환, 범위·게임명, 충돌 입력, 스키마, 빈 입력을 검증했다.
- [검증 요약 및 코드·입력 해시](tools/patch-classification-validation/results-connection-20260915.json)를 남겼다. DB·운영 파일 적재는 하지 않았다.

### 합의 후 연결 검증 (2026-09-16)

로컬 테스트는 변환 함수 → Parquet 저장 → 공통 스키마로 읽기 → 집계 순서로 확인한다.
비패치·근거 부족·빈 본문·과거 수집본 보존, 최신 판정 우선, 미판별 제외, 게시일 기준 집계를 검증한다.
95,913건 판정 분포는 위 규칙 검증 기록이며 이번 출력 연결만으로 정확도가 개선되었다는 의미는 아니다.

- 공통 모듈 16개 테스트 통과. Spark 전체 194개 중 192개 통과 후, 신규 테스트 2개의 JUnit boolean 단언문 타입 오류를 수정했다.
- 실패했던 재판정·Parquet 집계 연결 2개를 재실행해 모두 통과했다. 전체 실행 이후 제품 코드는 바뀌지 않았다.
- JAR 빌드 성공. 운영 HDFS 재변환·DB 적재는 실행하지 않았다.
- [출력 계약 연결 검증 기록](tools/patch-classification-validation/results-output-contract-20260916.json)


## 2026-09-15 판정 함수 통일

판정 구현은 `PatchClassifier` 하나로 유지한다. `ai/docs/PatchJudge.java`,
`ai/batch/dev_collect_steam.py`의 `judge`, `dev_make_news_input.py`의 정규식·과거 CSV 판정 대체 경로를 제거했다.
개발 수집기는 판정 없는 원문을 만들고, AI 입력 리더는 `is_patch`가 없는 입력을 자동 전체 처리하지 않는다.
Java 정본의 규칙 자체는 수정하지 않았으므로 버전은 `patch-rules-4`를 유지한다.

### 기존 규칙과 동일 입력 비교

삭제 전 Java `PatchJudge.judge`와 현재 `PatchClassifier.classify`를 아래 합성 입력 12건으로 실제 실행했다.
외부 Spark 의존성이 있는 Dataset 어댑터만 임시 비교 실행에서 제외했고, 기존 `judge`와 정규식은 그대로 사용했다.
이는 호환성과 의도한 차이를 확인하는 사례이며, 실제 공지 정확도 평가가 아니다.
490건 사람 라벨 원본은 확보하지 않았으므로 기존 PoC 재현율을 현 규칙의 수치로 사용하지 않는다.

| 사례 | 기존 isPatch | 통일 decision | scope |
|---|---|---|---|
| short-hotfix | false | PATCH | DEFAULT |
| tagged-preview | true | REVIEW_REQUIRED | DEFAULT |
| substring-tag | true | REVIEW_REQUIRED | DEFAULT |
| empty-body | true | REVIEW_REQUIRED | DEFAULT |
| version-only-update | true | REVIEW_REQUIRED | DEFAULT |
| verbs-without-context | true | REVIEW_REQUIRED | DEFAULT |
| launch-with-version | true | REVIEW_REQUIRED | DEFAULT |
| standard-tag | true | PATCH | DEFAULT |
| update-with-changes | true | PATCH | DEFAULT |
| test-branch | true | PATCH | TEST |
| sale | false | REVIEW_REQUIRED | DEFAULT |
| structured-changes | false | PATCH | DEFAULT |

입력과 기대값은 `PatchClassifierContractTest`의 12개 사례로 보존한다.
일반 세일 공지처럼 기존 false가 `REVIEW_REQUIRED`로 바뀌는 경우도 있다. 새 판정의 근거 부족은
비패치 확정과 다르며, 모든 기존 false를 그대로 유지한다고 주장하지 않는다.
`PATCH`의 대상 범위는 별도로 확인한다. 테스트 브랜치 판정을 일반 서버 집계 승인으로 해석하지 않는다.

### 담당 경계

- 수집·변환: 원문, `published_ts`, `collected_ts`, 태그를 전달한다. 판정 함수를 복제하지 않는다.
- 패치 판정: `PatchClassifier.Result`의 decision/scope/stage/reason/evidence를 반환한다.
- 패치 집계: 공지 게시일(KST)을 기준으로 전후 7일을 계산한다. 본문에서 적용일을 추출하지 않는다.
- 전달 연결: 3상태를 보존하는 파일 필드·경로와 AI 대상 범위는 담당자 합의 후 연결한다. `Result`를 새 HDFS 스키마로 확정한 것이 아니다.
- DB 스키마·마이그레이션·적재는 이 작업에 포함하지 않는다.

현재 `NewsToParquet`에서 판정은 실행하지 않는다. AI 호환 입력의 `is_patch`는 boolean이어야 하며,
미판정 원문은 AI 임베딩 전에 명시적으로 중단한다. 자세한 현황은 [AI 계약 부록 A](../ai/CONTRACT.md#부록-a-패치-판정-정본과-전달-상태)를 따른다.

### 통일 변경 검증 (2026-09-15)

- WSL Java 17: `./gradlew :spark:test :spark:jar --offline` 성공. Spark 전체 **161개** 테스트 통과(통일 비교 사례 12개 포함), 실패·오류 0개.
- `python -m unittest discover -s ai/tests -v`: **11개** 통과. 실제 임시 Parquet로 판정 컬럼 누락·잘못된 타입·혼합 파일/파티션 차단, true/false/null 처리, 최신 수집본 선택, 개발 수집·PoC 입력의 판정 제거를 검증했다.
- `embed_chunks.py --dt 2026-09-15 --no-embed`: 미판정 입력은 출력 생성 전 명시적으로 실패. true/false가 있는 호환 입력 2건에서는 true 1건만 읽어 청크 1개·변경점 0개를 출력했다. 두 출력의 컬럼 타입과 `_SUCCESS`를 확인했다.
- 리뷰 토픽 모델·Qwen 추론은 검증하지 못했다. 빈 임시 작업 폴더에서 기동 점검 시 `classify_reviews.py --limit 500`은 로컬 `joblib` 미설치, `qwen_backfill.py --limit 12`는 기존 `read_news` import 누락(NameError)으로 실패했다. 두 모델 배치 코드는 이번 통일 작업에서 수정하지 않았다.
- 실제 HDFS 전달 연결, 3상태 파일 계약, 490건 사람 라벨 정확도 평가는 미완료다. DB 파일 변경·접속·적재는 없다.

## 2026-09-15 서버 오류 수정 공지 보완: patch-rules-4 (현재)

제목에 수정 완료(`fixed`)가 있고 본문에 서버 오류 수정 완료 또는 fix 배포 완료가 명시된 경우를
변경 근거에 포함한다. 패치 결정일은 [PatchDateResolver](PATCH_DATE.md)에서 공지 게시일(KST)로 정한다.
기존 범위 판정과 예정·홍보·회고 제외 기준은 유지한다.

## 2026-09-14 추가 표본 보완: patch-rules-3

- 다른 게임 패치를 현재 게임의 리뷰와 연결하지 않도록 수집 게임명을 입력받는다.
  `classify(sourceGameName, title, contents, tags)`를 사용한다. 게임명은 수집 appid의 메타데이터에서 제공해야 한다.
  `update for ... is now live`처럼 변경 대상이 명시된 표현을 수집 게임명과 비교한다.
  이름 불일치 또는 입력 누락은 OTHER_GAME / REVIEW_REQUIRED로 보낸다. 이 범위에는 대상 미확인도 포함된다.
  별칭·다국어 게임명 비교와 모든 형태의 타 게임 홍보를 해결한 것은 아니다. 상점 링크 하나만으로 타 게임 패치라고 판단하지 않는다.
- 제목이 Mobile/Android/iOS 범위이고 PC/Steam/전체 플랫폼 표기가 없으면 NON_STEAM / REVIEW_REQUIRED로 보낸다.
  제목에 PC와 모바일이 같이 등장하거나 본문에만 모바일이 언급되는 경우에는 이 규칙으로 제외하지 않는다.
  플랫폼 확정값이라기보다 플랫폼 검증이 필요한 후보 범위이다.
- Experimental·Unstable 제목을 TEST로 인식한다. Stable과 함께 적힌 제목은 MIXED다.
  본문 끝에 현재 테스트 브랜치를 제공한다는 명시적 문장이 있어도 반영한다.
- `these patch notes will not go live until ...` 등의 직접적인 적용 보류 문장을 본문 전체에서 찾는다.
  테스트 브랜치는 제공하면서 정식 적용은 보류된 공지는 MIXED / REVIEW_REQUIRED로 둔다.
  날짜 추출이나 변경점별 적용 범위 분리는 하지 않는다. 지원하지 않는 예정 표현은 여전히 놓칠 수 있다.
- `We've already patched the issue on clients` 같은 명시적 완료 문장은 CLIENT / PATCH로 인식한다.
  근거 문장을 반환하며 서버까지 완료되었다고 추론하지 않는다. 단순한 미래·부정 표현은 인정하지 않는다.

단위 테스트 **46개 통과**. 실제 공지 **273건** 재실행:

| 표본 | v2 PATCH / NOT_PATCH / 미확정 | v3 |
|---|---|---|
| 기존 93건 | 41 / 27 / 25 | 41 / 27 / 25 |
| 기존 추가 60건 | 18 / 7 / 35 | 18 / 7 / 35 |
| 신규 게임 120건 | 45 / 2 / 73 | 43 / 2 / 75 |

기존 153건은 decision/scope/reason이 유지되었다. 신규 표본은 16건의 판정 또는 범위가 바뀌었다.
그중 10건은 TEST로, 1건은 정식·테스트 혼합 PATCH로 분리되었다.
다른 게임·모바일·정식 적용 보류 3건은 PATCH에서 미확정으로, 클라이언트 보안 수정 1건은 미확정에서 PATCH로 바뀌었다.
나머지 1건은 본문 부족 미확정을 유지하면서 TEST 범위를 표시했다.
신규 표본도 수정에 사용했으므로 이제는 회귀 검증 자료이지 독립 정확도 평가 자료가 아니다.

**DB/HDFS 연결, 테이블 변경, DB 적재는 하지 않았다.** 현재 결과는 로컬 파일에만 저장한다.
범위가 DEFAULT여도 Steam 정식 적용이 확인된 것은 아니다. TEST/MIXED/CLIENT 결과 역시 일반 리뷰 집계에 무조건 투입하면 안 된다.
명백한 비패치 공지를 광범위하게 제외하는 규칙은 추가하지 않았다. 미확정 개수를 줄이려고 근거 없는 판정을 만들지 않는다.

아래 v2 검증 기록은 변경 이력이다. 현재 규칙은 위 v3 설명을 우선한다.

## 2026-09-14 보완: patch-rules-2

- `TERRARIA 1.4.5.7 CHANGELOG`처럼 게임명·버전이 앞에 붙은 소제목을 인정한다.
- 소제목 뒤 50개 비어 있지 않은 줄 안에서 변경 문장 두 개를 찾는다. 소제목 앞의 문장과는 연결하지 않는다.
- `We've added ... in this update`, `This update introduces ...` 같은 소개 뒤 20개 줄 안의
  `Star Map - 설명`, `Hulks - 설명`도 변경 항목으로 인정한다. 직전 문단이나 소개 문장에 개발 중·예정 표현이 있으면 이 근거를 쓰지 않는다.
- 판매·로드맵 등 구역 전환이나 과거·예정 표현을 만나면 해당 구역의 탐색을 멈춘다.
- `Fixes & Improvements` 하위 소제목도 인정해 긴 패치노트의 뒤쪽 수정 목록을 찾는다.
- 버전이 끼어 있는 `Patch 1.2 Preview`와 취소·연기 제목을 제외한다. 태그와 충돌하면 미확정이다.
- 월간 근황·회고 제목에 현재 배포 근거가 없으면 새 패치 사례에서 제외한다.
- 결과 evidence에 소제목/소개와 실제 매칭 항목 2개를 함께 기록한다.

단위 테스트 30개 통과. 실제 공지 153건 재실행 결과:

| 표본 | 변경 전 PATCH / NOT_PATCH / 미확정 | 변경 후 |
| --- | --- | --- |
| 기존 93건 | 41 / 27 / 25 | 41 / 27 / 25 |
| 추가 60건 | 16 / 1 / 43 | 18 / 7 / 35 |

추가 표본에서는 Terraria 1.4.5.7과 No Man's Sky COSMOS가 미확정에서 PATCH로,
Terraria 월간 근황 6건이 미확정에서 NOT_PATCH로 바뀌었다. 기존 93건의 최종 판정은 유지했다.
이는 개발 표본 재실행 결과이지 독립 정확도 측정값이 아니다.
나머지 서술형 업데이트, 이벤트 포함 정책, 판매·투표 공지의 광범위한 제외는 이번 수정 범위가 아니다.
미확정 35건이 남아 있으며 범용 자연어 해석을 구현한 것은 아니다.
배포 표현 자체를 확인하는 기존 경로는 여전히 앞 1,200자 기준이다. 새 변경 구역 탐색은 본문 전체에서 시작점을 찾는다.

## 범위

`PatchClassifier.classify(title, contents, tags)`는 공지 한 건을 규칙으로 판정한다.
LLM 호출, 적용 날짜 추출, 변경점 추출, HDFS 읽기, DB 저장은 수행하지 않는다.
현재는 영어 공지를 중심으로 만든 초기 규칙이며 일부 한국어 표현만 포함한다.

## 반환값

| 필드 | 의미 |
| --- | --- |
| decision | `PATCH`: 패치 근거 있음 / `NOT_PATCH`: 예고·홍보 등 제외 근거 있음 / `REVIEW_REQUIRED`: 정보 부족 또는 근거 충돌 |
| scope | `DEFAULT`: 별도 범위 근거 없음 / `TEST`: 테스트 브랜치 / `MIXED`: 정식·테스트 혼합 / `CLIENT`: 클라이언트 수정 근거 / `NON_STEAM`: 모바일 플랫폼 검토 / `OTHER_GAME`: 변경 대상 게임 확인 필요 |
| stage | 최종 판정의 근거 단계. 입력 누락은 0 |
| reason | 집계·디버깅용 판정 사유 코드 |
| evidence | 실제 매칭한 태그, 제목 또는 본문 표현 |

규칙 버전은 `PatchClassifier.RULE_VERSION`으로 제공한다.
`DEFAULT`는 정식 서버 적용이 확인되었다는 뜻이 아니다.
`PATCH`도 실제 적용 시각이나 모든 플랫폼 적용 완료를 보증하지 않는다.
현재 합의한 집계 대상은 scope와 무관하게 `is_patch=true`인 공지다. scope별 별도 집계 정책은 추가하지 않았다.

## 판정 흐름과 이유

1. **태그**: `patchnotes`를 정확히 일치시킨다. 작성자가 붙인 태그이므로 강한 근거지만,
   보너스 앱 업데이트나 창작마당 모집에도 붙은 실례가 있어 즉시 확정하지 않는다.
2. **제외 표현**: 패치 예고, 미래 배포, 본편 영향 없음, 상품 판매, 커뮤니티 모집 등을 확인한다.
   `patchnotes` 태그와 충돌하면 `REVIEW_REQUIRED`, 태그가 없으면 `NOT_PATCH`로 분리한다.
   `preview`, `will`이라는 단어가 본문 어딘가에 있다는 이유만으로 제외하지 않는다.
   커뮤니티 소식에 실제 핫픽스가 함께 있으면 변경 근거를 우선한다.
3. **출시 표현**: 정식 출시 공지에도 기존 얼리 액세스 게임의 변경 목록이 포함될 수 있다.
   실제 변경 근거가 있으면 패치로 보며, 출시 홍보만으로는 패치 여부를 확정하지 않는다.
4. **제목 키워드**: `Hotfix`, `Patch`, `Changelog` 등을 사용한다.
   `Update`는 개발 근황에도 쓰이므로 배포 표현이나 여러 변경 문장을 추가로 요구한다.
5. **본문 근거**: 모호한 제목이면 배포 표현 또는 변경 소제목과 두 개 이상의 변경 문장을 요구한다.
   `Added`, `Fixed` 등으로 시작하는 문장을 사용하며 제목의 `Changelog`도 구역 근거로 인정한다.

BBCode·HTML 서식은 제거하되 줄 경계를 보존한다. 변경 소제목 앞의 장식·이모지도 무시한다.
배포·제외 표현 일부는 본문 앞 1,200자에서 확인하고 변경 목록은 전체 본문에서 확인한다.
따라서 본문 후반의 중요 설명이나 지원하지 않는 문장 형태를 놓칠 수 있다.

## 실제 공지 검토 결과 (2026-09-14)

Steam `ISteamNews/GetNewsForApp/v2/`로 수집해 보관한 8개 게임 공지 93건에
실제 Java 판정 함수를 실행했다. 수집 조건은 `count=12`, `maxlength=0`,
`feeds=steam_community_announcements,steam_updates`이다. 반환 건수는 게임별로 다르다.

| 결과 | 건수 |
| --- | ---: |
| PATCH | 41 (이 중 TEST 2) |
| NOT_PATCH | 27 |
| REVIEW_REQUIRED | 25 |

대표 사례:

- `Resurgence enters the MW4 Beta this Friday`: 미래 시작 예고이므로 NOT_PATCH / TEST.
- `Patch Notes Version 1.17` (ELDEN RING): 배포된 패치와 향후 DLC 일정을 구분하여 PATCH.
- `Community Update #34`와 Hotfix #32가 함께 있는 BG3 공지: 커뮤니티 제목만으로 제외하지 않고 PATCH.
- `Palworld v1.0 - Official Release Changelog`: 출시와 실제 변경 목록이 함께 있어 PATCH.
- `Devoid of Liberty: 7.0.0`: 이모지 소제목 아래 변경 목록을 확인하여 PATCH.
- 본편에 영향 없는 ELDEN RING 보너스 앱 업데이트: 태그와 본문 충돌로 REVIEW_REQUIRED.
- CS2 창작마당 모집 공지: patchnotes 태그와 모집 목적 충돌로 REVIEW_REQUIRED.

이는 **규칙 개발에 사용한 표본의 판정 분포**이지 정확도 측정값이 아니다.
별도 게임·기간의 공지에 정답 라벨을 붙여 오탐률·누락률을 측정해야 한다.
검토 필요는 자동 확정에서 제외한다는 의미이며, 전 게임 공지를 사람이 모두 확인한다는 운영안이 아니다.
실서비스에서는 사유별 표본 검토로 규칙을 보완하고 미확정 공지를 재판정할 수 있다.

## 테스트와 로컬 실행

HDFS·DB·Spark 클러스터 없이 실행 가능한 순수 Java 단위 테스트 46개가 통과했다.

```bash
./gradlew :spark:test --tests com.ssafy.thispatch.spark.PatchClassifierTest :spark:jar
```

`PatchClassificationPreview`는 로컬 검토용 어댑터이다.
입력 TSV는 헤더 없이 `appid`, `gid`, `Base64(UTF-8 제목)`,
`Base64(UTF-8 본문)`, `Base64(UTF-8 쉼표로 구분한 태그)`, `Base64(UTF-8 수집 게임명)` 6개 필드이다.
기존 5개 필드 입력도 호환하지만, 명시된 변경 대상과 수집 게임을 대조할 수 없어 검토 결과가 늘 수 있다.

```bash
java -cp spark/build/libs/thispatch-spark.jar \
  com.ssafy.thispatch.spark.PatchClassificationPreview input.tsv output.tsv
```

출력 파일은 기존 파일을 덮어쓰지 않는다.
결과 필드는 `appid`, `gid`, `decision`, `scope`, `stage`, `reason`, `evidence_base64`이다.

## 운영 반영 범위

- NewsToParquet의 판정 호출과 Parquet 출력, 집계 입력 연결을 구현했다.
- 운영 HDFS 재변환·배포와 DB 적재는 실행하지 않았다. DB 적재는 담당자(-25)가 수행한다.
- 날짜 정책은 [PATCH_DATE.md](PATCH_DATE.md)를 따른다. 게시 시각과 실제 적용 시각의 일치를 보증하는 것은 아니다.
