# 실제 Steam 공지 적용일 검증 — 2026-09-15

후속 검증은 [390개 앱·95,913건 확대 결과](PATCH_DATE_VALIDATION_EXPANDED.md)를 참고한다.
이 문서는 첫 실행 40,577건의 기록을 보존한다.

## 결론

**현재 `patch-date-rules-1`은 실사용 가능한 적용일 추출기라고 보기 어렵다.**
98개 Steam 앱의 실제 공지 **40,577건**에 실행했으나, 적용일 확정은 **0건**이다.
패치로 판정된 10,468건, 그중 DEFAULT 9,928건에서도 모두 날짜가 미확정이다.

이 결과는 **자동 확정률 0%**이다. 정확도 100%나 오탐률 0%라는 의미가 아니다.
출력한 날짜가 없어 정밀도(확정한 날짜 중 맞은 비율)는 계산할 수 없다.
전체 공지에 독립 정답을 붙이지 않았으므로 전체 재현율(실제 적용일을 얼마나 찾았는지)도 계산하지 않는다.

전체 원문을 읽은 60건 중, 연도·날짜·KST 시각과 적용 완료가 명확한 **단일 적용시각 누락 2건**을 확인했다.
이 2건도 오류 탐색용으로 골랐으므로 `0/2`를 Steam 전체의 재현율로 일반화하지 않는다.

## 실행 기준

- 브랜치: `feat/patch-analysis`
- 검증한 기능 커밋: `ea17f39` — 패치 적용일 결정 로직 및 테스트 구현
- 패치 판정: `patch-rules-3`
- 적용일 결정: `patch-date-rules-1`
- 실제 Java 클래스와 빌드된 JAR를 호출했다. Python으로 규칙을 다시 구현하지 않았다.
- 검증 중 기능 규칙을 수정하지 않았다. 원문 검토 후 성능을 다시 맞춘 결과가 아니다.
- `announcementZone=null`: 작성자 시간대를 제공하는 검증된 정보가 없으므로 추정하지 않았다.
- 원본 제목·본문·태그·게시 Unix 시각과 appdetails에서 조회한 앱 이름을 전달했다.
- 적용일 코드 SHA-256: `b830eb55eeb12cc1b00de51a7f12d15023cb7e6febf090ba3964430466e8b27a`
- JAR SHA-256: `495524851309182c0552aa437d899905b55ce95f60f8cbb09858ae130f55a8ad`

## 수집 범위와 품질

[Steam 공식 ISteamNews 문서](https://partner.steamgames.com/doc/webapi/ISteamNews)에 따라
`GetNewsForApp/v2/`, `maxlength=0`, `count=1000`,
`feeds=steam_community_announcements,steam_updates`, `enddate` 페이지 이동을 사용했다.

| 항목 | 결과 |
| --- | ---: |
| 조회한 앱 | 100 |
| 공지를 확보한 앱 | 98 |
| 중복 제거 후 `(appid, gid)` | 40,577 |
| 고유 gid | 40,577 |
| 본문 문자열이 서로 다른 수 | 33,924 |
| 페이지 사이에서 제거한 중복 행 | 147 |
| 같은 키의 서로 다른 본문 버전 | 0 |
| `steam_community_announcements` | 38,792 |
| `steam_updates` | 1,785 |
| 본문이 빈 공지 | 72 |
| 이전 3회 개발 검증 표본과 겹치는 공지 | 273 |
| 위 3회 표본에 없던 공지 | 40,304 |

- 게시시각 범위: **2008-10-19 02:27:37 UTC ~ 2026-09-15 02:00:01 UTC**.
- 수집시각: 2026-09-15 11:11~11:14 KST. 공통 종료시각을 고정했다.
- 장르·운영기간을 넓히기 위해 선택한 앱 표본이며 Steam 전체의 무작위 표본이 아니다.
- 대상에는 Wallpaper Engine 100건도 포함된다. 이를 제외한 게임 중심 표본은 **97개 앱·40,477건**이며 확정 0건이라는 결론은 동일하다.
- Path of Exile(238960), Path of Exile 2(2694490)는 이번 appdetails 응답에서 이름을 검증하지 못해 제외했다.
- War Thunder는 5페이지 제한으로 4,995건까지만 수집했다. 나머지도 API가 반환하는 이력 범위이며 전체 역사를 보증하지 않는다.
- gid가 달라도 본문이 같은 공지가 있으므로 40,577건을 모두 서로 독립적인 문장 사례로 세면 안 된다.
- `maxlength=0`은 API가 보유한 본문을 잘라 달라고 요청하지 않았다는 뜻이다. 외부 링크의 상세 패치노트까지 들어 있다는 보장은 없다.
- 이전 273건은 이미 규칙 개발에 사용된 표본이다. 이를 포함한 전체를 독립 테스트셋이라고 부르지 않는다.

## 전체 실행 결과

### 패치 판정

| 판정 | 공지 수 |
| --- | ---: |
| PATCH | 10,468 |
| REVIEW_REQUIRED | 28,695 |
| NOT_PATCH | 1,414 |
| 합계 | 40,577 |

### 적용일 결정

| 결과 / 사유 | 공지 수 |
| --- | ---: |
| RESOLVED | **0** |
| 패치 판정 미확정 | 28,695 |
| 지원하는 명시적 적용일 표현 없음 | 9,002 |
| 예정 또는 충돌 표현 | 923 |
| 적용 범위 검증 필요 | 540 |
| 적용 문맥 검증 필요 | 2 |
| 날짜 또는 시간대 해석 불가 | 1 |
| 비패치라 적용 대상 아님 | 1,414 |
| 합계 | 40,577 |

`REVIEW_REQUIRED`는 39,163건이다. 사유는 첫 반환 조건을 집계한 값이므로,
`NO_EXPLICIT_DEPLOYMENT_DATE`를 **원문에 적용일이 없다는 정답**으로 읽으면 안 된다.
현재 문장 패턴이 근거를 발견하지 못했다는 뜻이다.

## 원인을 분리한 진단

다음은 실제 예측을 대신하는 결과가 아니라 제한 조건의 영향을 알아보기 위한 가정 실험이다.

| 진단 조건 | 적용일 확정 |
| --- | ---: |
| 실제 판정 + 시간대 미지정 | 0 |
| 실제 판정 + 모든 작성자가 KST라고 가정 | 0 |
| 패치 판정·범위를 PATCH/DEFAULT로 강제 + 시간대 미지정 | 0 |
| 패치 판정·범위를 강제 + KST 가정 | 0 |

마지막 조건에서도 39,294건은 지원 배포 문장을 찾지 못했다.
따라서 **시간대 제한이나 앞단 판정만 풀어서는 해결되지 않는다. 배포 문장 인식 자체가 실데이터와 맞지 않는다.**

검증 실행기의 입력 연결도 따로 점검했다. 합성 입력 3건에서 명시적 ISO 배포시각은 정상 확정되고,
상대 날짜는 KST를 줄 때만 확정되며, 날짜 없는 `now live`는 보류됐다.
이 3건은 실제 공지 40,577건에 포함하지 않았다.

## 원문 대조 60건

- 시간대·연도·완료 표현 및 예외 사유에서 고른 공지 20건을 전체 읽었다.
- 고정 시드 `20260915`로 PATCH/DEFAULT 100건, 나머지 100건의 검토 대기열을 만들었다.
  각 그룹에서 본문 1,800자 이하의 첫 20건을 전체 읽었다. **대기열 200건 전부를 검토한 것은 아니다.**
- 따라서 전체 읽기 60건은 길이·오류 탐색 편향이 있는 사례 검토다. Codex가 원문을 대조했으며 독립된 사람의 정답 라벨은 아니다.
- 보조 탐색에서 시간대·날짜·변경 표현을 포함한 후보 1,242건도 만들었다.
  이 수 역시 추출 성공 수나 전부 검토한 수가 아니다.
- 검토한 60건의 gid, 원문 해시, 판단과 이유는 [source-review.json](tools/patch-date-validation/results-20260915/source-review.json)에 있다.

### 명확한 단일 시각 누락

| 공지 | 원문 근거로 확인되는 시각 | 실제 결과 | 원인 |
| --- | --- | --- | --- |
| Limbus Company — Jan. 23rd 2025 Scheduled Update Server Error Fixed | **2025-01-23 13:00 KST**, 서버 오류 수정 완료 | PATCH_NOT_CONFIRMED | `The error has been fixed`를 패치 사건으로 연결하지 못함. 배포 주어를 this/the patch 등으로 제한한 날짜 파서도 지원하지 않음 |
| Limbus Company — Version 1.36.2 Deployment on Mobile Platforms | **2024-02-02 01:30 KST**, Steam 업데이트 완료 | PATCH_NOT_CONFIRMED / NON_STEAM | 제목의 Mobile에 막힘. 본문의 Steam 완료와 모바일 예정 시각을 분리해야 함 |

첫 번째 사례의 본문은 2025-01-23 13:00 KST 수정 완료와 서버 정상화를 함께 명시한다.
[Steam 원문](https://store.steampowered.com/news/posts/?enddate=1737608694&feed=steam_community_announcements)

두 번째는 Steam 완료 시각과 모바일 12:00 KST 예정 시각을 서로 다른 줄에 적고 있다.
[Steam 원문](https://store.steampowered.com/news/posts/?enddate=1706840261&feed=steam_community_announcements)

### 날짜를 더 찾더라도 그대로 확정하면 안 되는 사례

| 사례 | 확인한 문제 |
| --- | --- |
| Limbus — Feb. 6th Scheduled Update | 같은 날 **13:55와 15:15 KST**에 서로 다른 핫픽스가 있다. 단일 적용시각 선택이 아니라 사건 분리가 필요하다. 보상 지급 시각도 섞여 있다. |
| Limbus — Ver. 1.76.0 지원 종료 | Steam `25.5.15 17:00 KST`, iOS·Android는 다른 시각. 두 자리 연도와 플랫폼 구분을 함께 처리해야 한다. |
| Monster Hunter: World — Fix Patch Released(2019-10-29) | 완료 표현은 09:00 UTC인데 게시시각은 08:40 UTC. 연도도 생략됐다. 배포됐다는 표현만으로 확정하기 어렵다. |
| Monster Hunter: World — Fix Patch 15.11.00 | 연도와 UTC 시각이 있지만 공지는 해당 이용 가능 시각 이전에 게시됐다. 완료 로그로 간주하면 안 된다. |
| Monster Hunter: World — Title Update Information | 날짜가 앞에 오는 완료 문장과 UTC 시각을 놓친다. 다만 생략된 연도는 추가 근거가 필요하다. |
| PAYDAY 2 — Only 2 days left on the sale | 감지한 배포일은 Xbox One/PS4 구역의 날짜다. Steam 적용일로 사용하면 안 된다. |
| Total War: WARHAMMER III — Hotfix 7.0.1 | Steam/Epic은 이미 배포됐으나 날짜는 없다. 문장 끝의 12월 9일은 Microsoft 예정일이다. |
| V Rising — Gloomrot Hotfix #2 | 게시 이후 시각으로 안내된 배포 예정이며 실제 완료가 아니다. |
| Team Fortress 2 — Update Released | 배포 완료 뒤 “재시작하면 적용된다”는 사용 안내를 미래 배포로 오해한다. 그래도 정확한 배포일 자체는 없다. |
| Dota 2 — Update 12/5/2024 | 최근 며칠간 변경을 묶은 공지. 제목 하루를 모든 변경의 적용일로 쓰면 안 된다. |

## 개선 우선순위

1. **사건의 주체와 문맥 확대:** `error was fixed`, `Steam version was updated`, 버전명이 주어인 문장,
   날짜가 앞에 오는 완료 문장과 표의 플랫폼별 배포 행을 처리해야 한다.
2. **예정·완료·사용 안내 분리:** 점검 예정, 완료 후 클라이언트 재시작 안내, 과거 버전 설명을 구분한다.
3. **시간 표현 확대:** KST/UTC 명시, 점으로 구분한 날짜, 서수 일자와 시각을 지원한다.
   생략된 연도는 출처와 추론 여부를 따로 남겨야 한다.
4. **공지와 패치 사건 분리:** 한 공지의 여러 패치·플랫폼·정확한 시각을 단일 값으로 합치지 않는다.
5. **개발·평가 데이터 분리:** 이 검토 사례는 이후 개발용 회귀 자료가 된다.
   수정 후에는 다른 게임·기간의 정답 표본을 별도로 만들어 정밀도와 재현율을 평가해야 한다.

이번 검증에서는 기능 규칙을 수정하거나 게시일 대체 정책을 추가하지 않았다.
DB/HDFS 적재도 실행하지 않았다.

## 결과 파일과 재현

- [집계 결과](tools/patch-date-validation/results-20260915/summary.json)
- [60건 원문 검토 기록](tools/patch-date-validation/results-20260915/source-review.json)
- [실행 버전과 해시](tools/patch-date-validation/results-20260915/validation-manifest.json)
- [재현 도구 사용법](tools/patch-date-validation/README.md)

대용량 원문, 전체 40,577건 결과, 요청 이력, 검토 대기열은 로컬의
`steam_patch_audit/runs/date-validation-large-20260915/`에 보관했다.
원문 데이터와 빌드 산출물은 저장소에 넣지 않는다.
