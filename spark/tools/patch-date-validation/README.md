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
