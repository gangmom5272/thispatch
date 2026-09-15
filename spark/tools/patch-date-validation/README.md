# 패치 적용일 실데이터 검증 재현

순수 Java 17과 Python 3 표준 라이브러리를 사용한다. 저장소 루트에서 실행한다.
새 수집 결과는 실행 시각에 따라 달라진다. 2026-09-15 고정 결과는 `results-20260915/`를 참고한다.

```bash
bash gradlew :spark:jar --offline
python3 spark/tools/patch-date-validation/collect_date_validation.py out/date-validation
javac --release 17 -cp spark/build/libs/thispatch-spark.jar \
  -d out/date-validation/classes spark/tools/patch-date-validation/PatchDateValidation.java
java -Xmx2g -cp out/date-validation/classes:spark/build/libs/thispatch-spark.jar \
  com.ssafy.thispatch.spark.PatchDateValidation out/date-validation/input.tsv out/date-validation/output.tsv
python3 spark/tools/patch-date-validation/summarize_date_validation.py out/date-validation
python3 spark/tools/patch-date-validation/find_completed_date_evidence.py out/date-validation
python3 spark/tools/patch-date-validation/inspect_date_validation.py out/date-validation diagnostics
```

- Windows에서는 Java 17이 있는 WSL에서 위 명령을 실행할 수 있다.
- 수집 폴더와 Java 출력 파일은 새 경로여야 한다. 기존 자료를 덮어쓰지 않는다.
- 수집 앱은 코드의 `APP_IDS`에 명시되어 있다. 최대 2개 동시 요청, 요청 간격 최소 0.55초,
  앱별 최대 5페이지, 페이지당 1,000건 요청으로 제한한다. 실패·페이지 한도·중복은 manifest에 기록한다.
- 정상 실행 결과는 `output.tsv`, 입력은 `input.tsv`, 원문과 요청 기록은 `raw/`, 합친 공지는 `corpus.jsonl`이다.
- 기본 실행은 검증된 작성자 시간대가 없어 `null`을 전달한다. 모든 KST 가정 및 PATCH/DEFAULT 강제 결과는 진단 전용 컬럼이다.
- `timezone-candidates.json`과 `random-review.jsonl`은 **검토 대기열**이다. 생성만으로 원문 검토나 정답 라벨이 완료되지 않는다.
- `results-20260915/source-review.json`은 Codex가 실제 원문을 대조한 60건의 기록이다.
  사람이 독립 검증한 정답 데이터가 아니며, 무작위 모집단 정확도 추정에도 쓰지 않는다.
- 기록된 원문 URL은 API 반환값이다. 일부 옛 중계 URL은 열리지 않을 수 있으므로 로컬 `raw_file`과 `body_sha256`도 함께 확인한다.
- `summary.json`의 `games_with_notices` 등은 API `type=game` 필터 기준이다.
  이 응답에는 Wallpaper Engine도 game으로 포함되어 있으므로 본 검증은 정확히는 **98개 앱 표본**이다.

이 도구는 로컬 데이터만 만들고 운영 DB·HDFS에 쓰지 않는다.

## 규칙 수정 후 같은 입력으로 비교

다음 명령은 최초 수집 폴더를 `out/date-validation`, 추가 폴더를 `out/expanded`로 가정한다.
현재 코드로 빌드한 JAR를 실행 폴더에 복사하고 어댑터도 다시 컴파일한다.

```bash
bash gradlew :spark:test :spark:jar --offline
mkdir -p out/v2
cp spark/build/libs/thispatch-spark.jar out/v2/thispatch-spark.jar
javac --release 17 -cp out/v2/thispatch-spark.jar -d out/v2/classes \
  spark/tools/patch-date-validation/PatchDateValidation.java
java -Xmx2g -cp out/v2/classes:out/v2/thispatch-spark.jar \
  com.ssafy.thispatch.spark.PatchDateValidation out/date-validation/input.tsv out/v2/prior-output.tsv
java -Xmx2g -cp out/v2/classes:out/v2/thispatch-spark.jar \
  com.ssafy.thispatch.spark.PatchDateValidation out/expanded/input.tsv out/v2/expanded-output.tsv
python3 spark/tools/patch-date-validation/compare_date_revision.py out/date-validation out/expanded out/v2
```

- 비교 대상 수집 폴더에 해당 실행의 `source-review.json`이 있어야 기존 검토 결과도 비교할 수 있다.
- `results-v2-20260915/`에 최종 수치와 원문 검토 기록을 보존했다.
- `ESTIMATED`는 `RESOLVED`에 합산하지 않는다. 추정의 정확한 적용시각은 항상 비어 있어야 한다.

## 새 앱과 과거 이력 추가 검증

```bash
python3 spark/tools/patch-date-validation/expand_date_roster.py out/date-validation out/expanded-roster
python3 spark/tools/patch-date-validation/collect_date_validation.py out/expanded \
  --roster out/expanded-roster/roster.json --cutoff 1789438293 --max-pages 20
java -Xmx2g -cp out/date-validation/classes:spark/build/libs/thispatch-spark.jar \
  com.ssafy.thispatch.spark.PatchDateValidation out/expanded/input.tsv out/expanded/output.tsv
python3 spark/tools/patch-date-validation/summarize_expanded_validation.py out/date-validation out/expanded
```

- `--cutoff`은 비교할 첫 실행 manifest의 값과 같아야 한다. 위 값은 2026-09-15 기록의 값이다.
- 공개 Steam 검색 목록 세 종류에서 새 앱 300개를 교차 선택하고, 이전 실행의 페이지 제한 앱은 과거 이력을 이어서 조회한다.
  검색 정렬은 요청값이며 반환 순서가 실제 출시일순이라는 보장은 하지 않는다.
- appdetails가 `success=false`이면 수집 명단의 Steam 공개 목록 이름을 사용하고 앱 종류를 `unverified`로 기록한다.
  합산 도구는 앱 종류로 임의 제외하지 않는다.
- 합산은 `(appid, gid)` 기준이며 겹치는 본문의 변경도 별도 보고한다. 실행 사이 규칙과 JAR가 같을 때만 결과를 합산한다.
- `source-candidates.json`과 `source-random-20.json`은 예측 결과를 사용하지 않고 고른 원문 검토 대기열이다.
  후보 검색식은 정답 판정기가 아니다. 무작위 대기열도 본문 길이 100~1,800자로 제한되어 전체 공지를 대표하지 않는다.
- `results-expanded-20260915/`에는 95,913건 합산 수치와 추가 원문 42건의 검토 기록을 보존했다.
  `artifact-hashes.json`은 로컬 실행 폴더의 원본 파일 바이트 기준이다. Git 체크아웃의 줄바꿈 변환 후 파일과는 달라질 수 있다.
