# S15P21A202-303 게임 제목 검색 성능 검증

측정일: 2026-09-23. 기능 검증은 통과했으며, 아래 지연 증가의 수용 여부는 사용자 확인 대상이다.
이 결과는 로컬 합성 데이터의 비교이며 운영 API의 지연 보장이나 기존 이슈의 120ms → 300ms 재현 결과가 아니다.

## 측정 환경과 범위

- Windows 호스트, Java 17.0.19, Docker PostgreSQL 17.11(pgvector), DB 기본 로케일 en_US.utf8.
- thispatch_test에만 연결한다. 각 JDBC 연결에 독립적인 임시 game, my_game, news, patch_stat, game_tag를 만든다.
  public 테이블의 구조·인덱스는 LIKE INCLUDING ALL로 복사하며 public 데이터는 쓰지 않는다. 연결 종료 시 임시 테이블은 삭제된다.
- 게임 100,000개, 게임별 패치 1개(총 100,000개), 현재 회원 등록 게임 1,000개(전체의 1%).
  제목은 영문·숫자·공백·하이픈·한글·악센트를 포함한다. patch_stat은 비어 있다.
- 기본 정렬 POSITIVE_RATE_ASC, limit=10, 첫 페이지. 실제 GameListRepository의 건수·목록 SQL을 수집해 사용한다.
  baseline은 제목 조건만 종전 lower(g.name) LIKE로 바꾼다. 검색어는 두 방식에서 같은 게임을 매칭하도록 선택했다.
- game: 전체 100,000개/내 게임 1,000개, rare: 전체·내 게임 각 100개, absentzz: 0개, empty: 제목 조건 없음.
- 각 쿼리 5회 워밍업 후 20회, JDBC 호출 시작부터 결과 소비 완료까지 측정한다. 평균과 nearest-rank p95를 기록한다.
  쿼리마다 EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)을 별도로 1회 저장한다.
- 캐시를 비우지 않았다. 동일 연결·데이터를 재사용하고 일부 검색어는 측정 순서를 반대로 했다.
  임시 테이블은 PostgreSQL local buffer를 사용하며 OS 캐시·계획 선택·JIT의 영향이 남아 있다.
- 동시 실행은 4개 연결, 연결당 워밍업 5회 후 10회(총 40회).
  한 요청의 측정 범위는 검색어 정규화 + count + page다. baseline의 기존 Java 정규화 비용은 포함하지 않았다.
  HTTP·인증·응답 DTO·태그·플레이 모드 조회는 제외한다. 각 연결의 데이터 준비는 측정에서 제외한다.
- DB CPU는 각 PostgreSQL backend의 /proc/self/stat user+system tick 차이 합계다.
  컨테이너의 CLK_TCK=100을 확인했으며 CPU 열은 밀리초다. 서버 전체 CPU 사용률이나 다른 프로세스 CPU가 아니다.

## 단일 연결 결과

단위 ms. 각 칸은 평균 / p95이며, 건수와 목록을 별도로 측정했다.

| 범위 | 검색 | 이전 count | 변경 count | 이전 page | 변경 page |
|---|---|---:|---:|---:|---:|
| 전체 | 많은 결과 | 74.2 / 80.9 | 336.1 / 355.3 | 127.4 / 137.5 | 369.9 / 420.6 |
| 전체 | 적은 결과 | 76.5 / 81.6 | 336.3 / 350.7 | 74.9 / 78.5 | 339.0 / 353.2 |
| 전체 | 결과 없음 | 73.8 / 78.8 | 332.4 / 345.1 | 72.6 / 79.5 | 334.9 / 345.4 |
| 전체 | 빈 조건 | 9.8 / 12.1 | 8.9 / 11.1 | 61.5 / 67.7 | 61.3 / 68.4 |
| 내 게임 | 많은 결과 | 77.2 / 84.8 | 344.0 / 352.8 | 91.0 / 97.1 | 359.5 / 387.2 |
| 내 게임 | 적은 결과 | 73.9 / 78.3 | 332.7 / 344.5 | 73.4 / 82.9 | 330.4 / 344.5 |
| 내 게임 | 결과 없음 | 62.5 / 78.9 | 335.3 / 343.3 | 71.7 / 78.1 | 327.9 / 343.2 |
| 내 게임 | 빈 조건 | 16.7 / 19.1 | 7.8 / 9.1 | 26.8 / 29.8 | 25.6 / 27.7 |

전체의 많은 결과 검색에서 count+page 평균 합계는 201.6 → 706.1ms(약 3.50배, +504.5ms).
별도 쿼리의 p95를 합해 요청 p95로 해석하면 안 된다.
빈 조건은 변경 전후 같은 SQL이며, 시간 차이는 측정 순서·캐시·계획 등에 의한 변동으로 본다.

## 동시 4개 연결

검색어 game. 처리량은 합계 40개 요청을 가장 오래 실행한 연결의 측정 시간으로 나눈 값이다.

| 범위 | 방식 | 평균 ms | p95 ms | 요청/초 | DB CPU 합계 ms |
|---|---|---:|---:|---:|---:|
| 전체 | 이전 | 200.9 | 229.8 | 19.62 | 7,900 |
| 전체 | 변경 | 803.0 | 871.1 | 4.95 | 31,980 |
| 내 게임 | 이전 | 170.5 | 183.2 | 23.33 | 6,710 |
| 내 게임 | 변경 | 740.4 | 792.9 | 5.36 | 29,460 |

전체 기준 DB CPU/요청은 약 197.5 → 799.5ms, 내 게임은 167.8 → 736.5ms다.
동시 요청에서도 정규식 변환 비용이 지연 증가·처리량 감소로 이어졌다.

## 실행 계획과 판단

- 전체 count: 두 방식 모두 game 100,000행 Seq Scan. 대표 계획의 scan 시간이 74.6 → 338.7ms로 증가했다.
- 전체 page: game scan → 정렬/limit 11행 → 최신 패치 인덱스 조회 구조를 유지한다.
  대표 계획의 game scan은 114.3 → 381.5ms, news 조회는 전후 모두 11회다.
- 내 게임 count/page: 이 데이터 분포에서는 Hash Join 전에 game 전체에 제목 조건을 평가했다.
  변경 count의 game scan 약 338.0ms, page 약 340.3ms로, 회원의 게임이 1%여도 정규화 비용이 전체 게임에 발생했다.
- 계획 생성 시간은 대표 쿼리에서 1ms 미만이다. 주요 증가는 실행 중 제목별 regexp_replace 비용이다.
- 빈 검색에는 정규식 조건을 생성하지 않는다. 원문 제목 저장·읽기 응답, 기존 필터·정렬·페이지 계약은 유지한다.
- 추가 최적화 후보는 내 게임 집합을 먼저 확정하는 조회, 정규화 결과의 사전 저장, 부분 검색용 인덱스다.
  정규화 컬럼·인덱스를 도입하려면 원본 적재/갱신 경로와 Unicode/로케일 일치, 쓰기 비용까지 함께 검증해야 한다.
  schema/migration을 바꾸는 방식은 별도 사용자 결정 후 진행하며 이번 구현에는 포함하지 않았다.
- 운영 데이터 규모·회원별 등록 수·실제 제목 분포·다른 정렬·필터·동시성·HTTP 전체 비용은 별도 측정 대상이다.
  합성 데이터만으로 운영 성능이 허용된다고 판정하지 않는다.

## 재실행

backend 디렉터리에서 실행한다. .env의 PostgreSQL 호스트·포트·계정을 읽고 DB 이름은 thispatch_test로 고정한다.
운영 환경의 .env로 실행하지 않는다. 이 도구는 backend의 기본 build.gradle이나 일반 test 작업을 바꾸지 않는다.

~~~powershell
..\gradlew.bat -I tools/game-title-search-benchmark.gradle :backend:benchmarkGameTitleSearch
~~~

선택 환경변수: GAME_BENCH_ROWS(100000), GAME_BENCH_RUNS(20), GAME_BENCH_CLIENTS(4),
GAME_BENCH_CONCURRENT_RUNS(10). 결과는 build/game-title-search-benchmark/results.csv 및 검색별 JSON 실행 계획에 저장한다.
이번 원시 수치는 game-title-search-performance.csv에 보관한다.
종료 후 public.game=0, public.my_game=0, 해당 benchmark 임시 테이블=0으로 원래 상태 유지와 정리를 확인했다.

## 기능 검증

- ..\gradlew.bat :backend:compileJava — 성공.
- ..\gradlew.bat :backend:test — 성공. 총 1,441개, 통과 1,420개, 실패/오류 0개, 조건부 skip 21개.
- 게임 도메인 287개는 전부 실행·통과했다. 공백·특수문자·대소문자·비영문·악센트, 원문 보존,
  정규화 후 빈 검색, 원문 길이 오류, 네 가지 정렬·동률·필터·회원 범위·건수·커서 회귀를 포함한다.
- skip은 기존 REDIS_INTEGRATION_TEST / AI_INTEGRATION_TEST 환경변수로 활성화하는 테스트다.
- 성공/오류 응답 구조, 오류 코드·메시지, URL·인증·파라미터, 입력 길이 제한은 유지했다.

기술 참고: PostgreSQL의 정규식은 Java Unicode property escape를 지원하지 않으므로 JDK 문자 범위에서 고정 패턴을 생성한다.
패턴 범위 비교는 C collation, 양쪽 소문자 변환은 동일한 DB 기본 collation을 사용한다.
[PostgreSQL 정규식 문서](https://www.postgresql.org/docs/17/functions-matching.html),
[문자열 함수 문서](https://www.postgresql.org/docs/17/functions-string.html).
