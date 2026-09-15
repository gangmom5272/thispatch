# Steam 공지 패치 판정

## 2026-09-14 추가 표본 보완: patch-rules-3 (현재)

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
`TEST` 패치는 보관할 수 있지만 일반 서버 리뷰 전후 비교 대상으로 바로 넣지 않는다.

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

## 아직 연결하지 않은 것

- 실제 공지 HDFS 입력 계약을 확인한 뒤 Spark 처리 단계에서 이 함수를 호출해야 한다.
- 팀의 드라이버 적재 통로에 결과를 넘기는 연결 작업은 별도이다. 익스큐터 JDBC는 추가하지 않았다.
- DB의 boolean `is_patch`만으로는 미확정을 표현할 수 없다.
  `REVIEW_REQUIRED`를 자동으로 false로 바꾸지 말고, 팀과 미확정 저장·재처리 방식을 합의해야 한다.
- 적용 날짜, 플랫폼 범위, 출시 전후 리뷰 집계 기준은 이 판정 함수 밖에서 검증한다.
  적용일 결정 함수 `PatchDateResolver`의 계약은 [PATCH_DATE.md](PATCH_DATE.md)에 정리했다.
