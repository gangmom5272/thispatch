# 패치 결정일: 공지 게시일

2026-09-15 결정: **패치 결정일은 Steam 공지 게시 시각의 KST 날짜로 통일한다.**
현재 정책 버전은 `patch-date-publication-3`이다.

```java
Instant publishedAt = Instant.ofEpochSecond(steamAnnouncementDate);
LocalDate patchDate = PatchDateResolver.resolve(publishedAt);
```

- Steam 공지의 `date`(Unix 초)를 그대로 사용한다. 수집 시각이나 본문 수정 시각으로 바꾸지 않는다.
- 날짜 경계는 공통 `TimeRule`의 `Asia/Seoul`을 따른다.
  예: `2026-09-14T15:00:00Z` 게시 → 패치 결정일 `2026-09-15`.
- 본문의 적용일·점검 일정·상대 날짜·시간대·플랫폼별 배포일을 해석하지 않는다.
- 기존 날짜 파서와 확정·추정·보류 상태, 근거 선택 로직은 폐기했다.
- 게시 시각이 없으면 입력 오류다. 현재 시각으로 대체하지 않는다.
- 패치 여부와 적용 대상 판정은 `PatchClassifier`가 담당한다. 날짜 함수는 게시 시각만 받는다.

## 리뷰 통계 연결

`PatchStatAggregator`는 내부 입력의 `published_ts LONG`에 Steam 공지 게시 시각(Unix 초)을 받는다.
이전 `patched_ts` 입력은 사용하지 않는다. KST 게시일 D를 중심으로 이전 7일·이후 7일을 집계한다.
기존 출력 필드 `patched_at`에는 공지 게시 시각을 보존한다.
`is_patch=true`인 확정 패치만 집계한다. 실제 적용일 검증은 요구하지 않는다.

이 정책의 날짜는 공지 게시일을 기준으로 정한 서비스상의 패치일이다.
NewsToParquet의 판정 출력과 집계 입력을 연결했다. 운영 HDFS 실행·DB 적재는 별도다.

## 검증과 이전 기록

```bash
bash gradlew :spark:test :spark:jar --offline
```

KST 날짜 경계, 게시 시각 누락, 공지 게시일에 따른 리뷰 집계와 출력 시각을 검증한다.

저장된 390개 게임의 고유 공지 95,913건을 현재 Java 코드로 처리했고, 전부 게시 시각의 KST 날짜로 변환됐다.
누락은 0건이다. 이는 게시일 정책의 변환 검증이며, 실제 적용일 정확도나 패치 판정 정확도가 아니다.
결과는 [policy-conversion-summary.json](tools/patch-date-validation/results-publication-20260915/policy-conversion-summary.json)에 보존했다.

- [게시일과 원문 적용일 비교](tools/patch-date-validation/results-publication-20260915/REPORT.md)
- `PATCH_DATE_VALIDATION*.md`와 v1/v2 결과는 폐기한 추출 방식의 과거 검증 기록이다.
  현재 정책의 날짜 결정이나 정확도 집계에 사용하지 않는다.
