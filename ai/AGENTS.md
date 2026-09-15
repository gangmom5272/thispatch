# AI 노드 작업 규칙

적용 범위는 `ai/`. 아래 경로와 명령의 기준 디렉터리도 `ai/`다. 백엔드의 [AGENTS.md](../backend/AGENTS.md)와 같은 취지로,
AI 도구든 사람이든 이 폴더를 고칠 때 먼저 읽는다.

## 깃 규칙 (팀 Notion '깃 컨벤션 & 플로우' + 2026-09-14 확정)

- 브랜치 `{타입}/#{지라키}/{간략-영문}` 예 `feat/#S15P21A202-47/ai-embed-batch`. 타입 소문자: feat·chore·docs·fix·test·refactor·build·hotfix
- 커밋 `{타입}: [{지라키}] {메시지}` 예 `feat: [S15P21A202-47] 패치 청크 임베딩 배치`. **타입은 소문자**, 역할 칸 없음(팀 이력과 동일, 역할은 브랜치의 `ai-` 접두어로 구분). 문서만 바꾸면 `docs:` 브랜치·커밋
- MR 제목은 **대표 커밋 메시지 그대로**(예 `feat: [S15P21A202-47] 패치 청크 임베딩 배치`, 팀 이력과 동일). **본문은 저장소 템플릿**(`.gitlab/merge_request_templates/Default.md`, GitLab 자동 적용) 그대로 쓰고 첫 줄에 `관련 이슈: S15P21A202-47`, 영향 범위 "AI Server" 체크
- 작업 브랜치 → push → MR 로 **develop** 병합 → 브랜치 삭제
- 이슈는 Jira 스토리로 관리(Notion 의 Issue 템플릿은 사용하지 않음). 디렉토리 구조·문자열 규칙은 Python 폴더라 해당 없음

## 작업 시작 전

- 현재 브랜치를 확인한다. `master`·`develop`이면 변경하지 말고 작업 브랜치(`feat/#S15P21A202-NN/…`)로 전환한다.
- [README.md](README.md)(구성·실행·실측), [CONTRACT.md](CONTRACT.md)(HDFS 파일 계약)를 읽는다.
- 계약과 맞닿은 정본을 확인한다: `common/src/main/java/com/ssafy/thispatch/common/HdfsPaths.java`(경로),
  `ReviewSchema.java`(리뷰 컬럼), `backend/src/main/resources/db/migration/V1·V2`(테이블·코드값).

## 바꾸지 않는 것

- **계약 컬럼명·타입·경로**(CONTRACT.md 2·3절)는 임의로 바꾸지 않는다. 바꿔야 하면 CONTRACT.md 를 먼저 고치고 Loader(백엔드)·Spark 담당에게 알린다.
- 코드값(`change_type`·`direction`·`target_type`)은 V2 시드 문자열 그대로 쓴다. 새 값을 만들지 않는다.
- 임베딩 모델·차원·입력 문자열 형식(`chunking.build_input_text`)은 저장된 벡터와 질의 벡터가 같아야 하므로 바꾸면 전체 재임베딩이다. 바꿀 때 `EMBED_MODEL_TAG` 를 올린다.
- Qwen 프롬프트·스키마(`qwen_prompt.py`)를 바꾸면 `PROMPT_VERSION` 과 `MODEL_TAG` 를 올린다. 기존 결과와 섞이지 않게.
- 절대 경로·IP·비밀값을 코드에 넣지 않는다. 경로는 `AI_WORK_DIR`, 서버는 `OLLAMA_URL`, 모델은 `EMBED_MODEL_ID`·`CLASSIFIER_PATH`.
- `models/`, `work/`, `*.parquet`, `.env` 는 커밋하지 않는다(`.gitignore`).

## 사용자 결정이 필요한 것

- 처리 대상 범위(코퍼스 게임 목록, 초기 리뷰 분류 범위), 계약 4절의 미결 항목, 새 출력 폴더·컬럼 추가.
- 규칙 슬롯 정확도를 바꾸는 사전·정규식 수정은 3게임 검증(`0910/experiments/rule_check_3games`)을 다시 돌려 수치를 같이 보고한다.

## 검증

코드를 고치면 개발 입력(README 실행 절)으로 최소 다음을 돌리고 결과 수치를 보고한다.

```bash
$PY batch/embed_chunks.py     --dt <dt> --no-embed     # 청크·변경점 수, 스키마
$PY batch/classify_reviews.py --dt <dt> --limit 500
$PY batch/qwen_backfill.py    --dt <dt> --limit 12     # Ollama 필요
```

- 출력 Parquet 을 `pyarrow` 로 읽어 CONTRACT.md 스키마와 컬럼·타입이 일치하는지 확인한다.
- API 는 `uvicorn main:app --port 8100` 후 `/health`, `/plan/structure`, `/embed/query` 를 호출해 본다. 오류 응답은 백엔드 공통 형식(`code`, `message`, `responsedAt`)이어야 한다.
- 실패·미실행을 성공으로 보고하지 않는다. GPU 가 없는 환경이면 CPU 로 돌아가되 속도 수치는 보고에서 제외한다.

## 완료 보고

변경 파일, 실행한 명령과 수치(청크·변경점·리뷰 건수, 처리 속도), 계약 변경 여부, 남은 결정 사항. 없는 항목은 `none`.
