# 패치 적용일 결정

`PatchDateResolver`는 패치 판정 결과와 공지에서 **현재 패치의 적용일**을 결정하는 순수 Java 함수다.
규칙 버전은 `patch-date-rules-1`이다. 시스템 현재 시각을 사용하지 않아 같은 입력은 같은 결과를 반환한다.

**2026-09-15 실데이터 검증:** 98개 Steam 앱 공지 40,577건에서 적용일 확정 **0건**.
현재 규칙의 문장 지원 범위가 실제 공지와 맞지 않아 개선이 필요하다.
확정 결과가 없으므로 정밀도는 계산할 수 없다. [상세 검증 보고서](PATCH_DATE_VALIDATION.md)를 참고한다.

## 입력

```java
var classification = PatchClassifier.classify(gameName, title, contents, tags);
var result = PatchDateResolver.resolve(
        classification, title, contents, publishedAt, announcementZone);
```

- `classification`: **동일한 제목과 본문**으로 얻은 판정 결과.
- `title`, `contents`: 공지 제목과 원문. HTML/BBCode 서식은 기존 정리 함수를 사용한다.
- `publishedAt`: 공지 게시 시각 `Instant`. 없으면 `null`이다. 상대 날짜의 기준과 게시 이후 날짜 충돌 검사에만 쓴다.
- `announcementZone`: 작성자의 날짜 표현이 기준으로 삼는 검증된 `ZoneId`. 모르면 `null`이다.
  영문·한국어 여부, 서버 시간대 또는 Steam의 Unix 시각만으로 작성자의 시간대를 정하면 안 된다.

## 결정 순서

1. `NOT_PATCH`는 `NOT_APPLICABLE`, 미확정 판정은 `REVIEW_REQUIRED`로 반환한다.
2. `TEST`, `MIXED`, `CLIENT`, `NON_STEAM`, `OTHER_GAME`은 대상 확인이 필요해 날짜를 확정하지 않는다.
3. 현재 패치가 예정·연기 상태라는 지원 표현을 본문 전체에서 찾으면 보류한다.
4. 현재 패치의 배포 완료 문장 안에서 날짜를 읽는다. 단순 날짜나 제목의 버전 숫자는 사용하지 않는다.
5. 제목·구역 제목·직전 문장에 과거·계획·테스트·다른 플랫폼 문맥이 있으면 보류한다.
6. 후보 날짜가 서로 다르거나, 같은 KST 날짜라도 정확한 시각 두 개가 다르면 보류한다.
7. 일치하는 날짜와 시각이 함께 있으면 시각 정보를 우선한다.

## 지원하는 표현

현재 패치를 지칭하는 `This/The patch/update/hotfix`와 완료 표현
`was released`, `was deployed`, `went live`, `has been released`를 연결한 문장을 처리한다.
`This patch is now live today`, `The update was deployed yesterday` 형태의 상대 날짜도 처리한다.
문장은 제목, 평문 줄 또는 서식이 있는 본문 구역에 있을 수 있다.

| 예시 | 결과 |
| --- | --- |
| `This patch was deployed at 2026-09-14T16:30:00Z.` | KST 9월 15일, 정확한 시각 보존 |
| `The update went live on 2026-09-14T08:30:00-07:00.` | KST 9월 15일, 정확한 시각 보존 |
| `This patch was released on September 14, 2026.` | 작성자의 날짜 기준이 KST로 검증되면 9월 14일 |
| `This patch was deployed yesterday.` | 게시일의 KST 전날, 작성자 시간대와 게시 시각 필요 |
| `이번 패치는 2026년 9월 14일에 적용되었습니다.` | 작성자의 날짜 기준이 KST로 검증되면 9월 14일 |
| `해당 패치가 어제 적용되었습니다.` | 검증된 KST 게시일의 전날 |
| `This patch is now live!` | 적용 날짜가 없으므로 보류 |
| `Patch Notes - September 14, 2026` | 제목의 날짜만으로는 배포 완료를 확인할 수 없어 보류 |

날짜 형식은 `yyyy-MM-dd`, `September 14, 2026`, `Sep 14, 2026`,
`14 September 2026`, `14 Sep 2026`, `2026년 9월 14일`을 지원한다.
`today/yesterday/오늘/어제`는 게시 시각이 있어야 한다.
연도 생략, `09/10/2026` 같은 숫자 슬래시 날짜, 시간대 없는 시각, PST 같은 약칭은 추정하지 않는다.
윤년·월별 일수는 엄격하게 검증한다.

### 시간대와 정밀도

- UTC 또는 오프셋이 명시된 ISO 시각은 `TimeRule`로 KST 적용일을 구한다.
- 날짜만 있는 경우에는 작성자 시간대의 해당 하루 시작·끝이 KST와 일치할 때만 확정한다.
  검증된 `Asia/Seoul`이나 현재 날짜의 `+09:00`이 여기에 해당한다.
  다른 시간대의 하루는 두 KST 날짜에 걸칠 수 있으므로 자정이나 정오를 만들어 변환하지 않는다.
- 정확한 시각을 모르면 `appliedAt`은 `null`이다. 게시 시각이나 임의의 자정으로 채우지 않는다.
- 완료 문장의 날짜·시각이 게시 시각보다 미래이면 충돌로 보류한다.
  날짜만 있을 때는 KST 날짜 단위로 비교한다. 게시 시각이 없으면 이 검사는 할 수 없다.

## 반환값

| 필드 | 의미 |
| --- | --- |
| `status` | `RESOLVED`, `REVIEW_REQUIRED`, `NOT_APPLICABLE` |
| `patchDate` | 결정된 KST 적용일. 미확정이면 `null` |
| `appliedAt` | 원문에 명시된 정확한 적용 시각. 날짜만 확인되거나 미확정이면 `null` |
| `source` | `EXPLICIT_TIMESTAMP`, `EXPLICIT_DATE`, `RELATIVE_DATE`, `NONE` |
| `reason` | 판정·범위·문맥·날짜·시간대·충돌에 대한 사유 코드 |
| `evidence` | 서식 정리 후 실제 근거 문장. 날짜 충돌 시 모든 후보 문장 |

`RESOLVED`는 지원 규칙으로 날짜를 결정했다는 의미다. 사람이 검증한 정답이나 Steam 정식 서버 적용 보증이 아니다.
`DEFAULT`도 플랫폼 확인 완료를 뜻하지 않으므로, 날짜가 결정되어도 `eligible_for_review_stats`를 자동으로 켜면 안 된다.

## 통계 연결

호출자가 적용 대상까지 검증한 후에만 `PatchStatAggregator`의 입력을 구성한다.
정확한 시각은 `result.appliedAt().getEpochSecond()`를 쓰고,
날짜만 검증된 경우는 기존 집계 계약에 따라 `TimeRule.startOfDay(result.patchDate())`를 쓴다.
이때 자정은 **날짜 단위 집계용 값**이고 실제 배포 시각이 아니다.

이번 구현은 HDFS 입력 어댑터, Spark 일괄 처리기, DB 적재·마이그레이션을 추가하지 않는다.
미확정 저장 정책은 기존 패치 판정과 동일하게 별도 입력·적재 계약이 필요하다.

## 범위와 검증

영어와 일부 한국어의 제한된 완료 문장 규칙이다. 버전명이 주어인 문장, 복수 패치 요약,
여러 플랫폼별 배포 일정, 인용·회고의 복잡한 문맥, 자연어 시간대 해석은 자동 해결하지 않는다.
지원 패턴을 벗어난 문맥은 놓칠 수 있으므로 실데이터 정확도 검증과 별개로 취급해야 한다.
날짜를 결정할 수 없다는 것은 패치가 없었다는 뜻이 아니다.

```bash
bash gradlew :spark:test --tests com.ssafy.thispatch.spark.PatchDateResolverTest --offline
```

단위 테스트는 KST·연도 경계, UTC 오프셋, 날짜 정밀도, 잘못된 날짜, 게시일 대체 방지,
상대 날짜 입력 누락, 시간대 미확인, 날짜·시각 충돌, 예정·과거·플랫폼 문맥, 서식 제거를 검증한다.

2026-09-15 WSL Java 17 검증: 기존 138개와 신규 22개를 포함한 전체 160개 테스트와 JAR 빌드 통과.
이후 고정 `+09:00` 시간대 지원을 보완하고 적용일 테스트 23개 및 JAR 빌드를 다시 통과했다.
이후 실제 Steam 공지 40,577건 실행과 60건 원문 대조를 수행했다. 독립 정답 데이터 기반 정확도 측정과 HDFS·DB 통합 검증은 수행하지 않았다.
