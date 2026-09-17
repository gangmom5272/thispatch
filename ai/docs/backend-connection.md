# 백엔드 ↔ AI 서버 연결

백엔드(서버1, 컨테이너)가 AI 서버(GPU 노트북)를 호출하는 방법이다.
요청·응답 형식은 [ai/README.md](../README.md)와 실행 중인 서버의 `/docs`(OpenAPI)에 있다.

## 왜 IP 로 직접 못 부르는가

EC2 에서 교육장 노트북 대역(70.12.0.0/16)으로 나가는 **라우팅이 없다**.
2026-09-10 에 서버2A 에서 직접 확인했다 — 포트를 열어도 ping 조차 가지 않는다.
막는 것이 방화벽이 아니라 경로라서 포트 개방으로는 해결되지 않는다.

반대 방향은 열려 있다. AI 노트북에서 서버1 의 22·80·443 에 닿는 것을 확인했다(9/16).
그래서 **AI 노트북이 서버로 붙어 포트를 거꾸로 넘긴다**(SSH 역터널).

```
[AI 노트북]  8100 ──ssh -R──▶ [서버1]  172.17.0.1:8100 ──▶ [백엔드 컨테이너]
   FastAPI                      도커 브리지                    http 호출
```

## 백엔드가 할 일

### 1. 주소를 설정값으로 뺀다

```yaml
# application.yml
ai:
  base-url: ${AI_BASE_URL:http://172.17.0.1:8100}
  connect-timeout: 3s
  read-timeout: 30s        # 실측 최대 13초(사례 비교 Qwen 해석). 여유를 둔다
```

`172.17.0.1` 은 도커 브리지 주소다. 컨테이너 안에서 호스트를 가리킨다.
AI 노트북의 IP 가 바뀌어도(무선↔유선) 이 값은 그대로다.

### 2. 호출 전에 `/health` 를 본다

```
GET /health
{"status":"ready","ready":true,"ollama":true,"embedder_loaded":true,
 "qwen_loaded":true,"embed_model":"...","llm_model":"qwen3.5:9b"}
```

`ready` 가 false 면 모델이 아직 올라오는 중이다(기동 후 약 24초).
연결 자체가 안 되면 노트북이 꺼졌거나 터널이 끊긴 것이다.

- 화면이 죽지 않게 **AI 실패는 기능 축소로 처리**한다. 요약이 없으면 지표와 원문만 보여준다.
- `/health` 결과를 10~30초 캐시하면 매 요청마다 확인하지 않아도 된다.

### 3. 타임아웃과 재시도

| 엔드포인트 | 실측 | 비고 |
|---|---|---|
| `/embed/query` | 밀리초 | LLM 없음 |
| `/cases/cards`, `/plan/restate` | 밀리초 | 문장 틀 |
| `/trends/summarize` (`use_llm=false`) | 4ms(300일) | 문장 틀 |
| `/trends/summarize` (`use_llm=true`) | 3~9초 | Qwen 1회 + 검사 재작성 |
| `/plan/structure` | 5초 | Qwen 1회 |
| `/reviews/summarize` | 10초 | Qwen 1회 |
| `/cases/compare` (`use_llm=true`) | 13초 | 사례 변경점 40개 기준 |

읽기 타임아웃 30초를 권한다. **재시도는 하지 않는 편이 낫다** —
Qwen 호출은 이미 서버 안에서 검사·재작성을 최대 2회 돌린다. 밖에서 또 재시도하면
같은 일을 두 번 시키고 응답이 30초씩 늘어난다.

### 4. 결과는 백엔드가 캐시한다

AI 서버는 아무것도 저장하지 않는다. 같은 요청이 반복되면 그대로 다시 계산한다.

- 리뷰 요약: `(appid, scope_type, scope_key)` 기준으로 `review_summary` 에 저장
- 반응 추세 요약: `(appid, 기간 시작, 기간 끝)` 기준
- 질의 임베딩: 기획안이 바뀌지 않으면 재사용

### 5. 오류 형식

AI 서버 오류는 백엔드 공통 형식과 같다.

```json
{"code":"VALIDATION_FAILED","message":"입력값을 확인해주세요.",
 "responsedAt":"2026-09-17 09:30:00","errors":[{"field":"daily.0","message":"..."}]}
```

| 코드 | 상황 |
|---|---|
| `VALIDATION_FAILED` | 필드 검증 실패. `errors` 에 어느 값인지 |
| `INVALID_REQUEST` | 본문이 JSON 이 아니거나 없음 |
| `AI_UPSTREAM_ERROR` | Qwen 호출 실패(502) |
| `INTERNAL_SERVER_ERROR` | 그 밖 |

`/trends/summarize` 는 Qwen 이 꺼져 있어도 **200 으로 문장 틀 결과**를 준다.
`used_llm:false`, `clean:false` 로 알린다. 화면을 비우지 않기 위해서다.

## 인프라가 할 일 (한 번만)

서버1 `sshd_config` 에 한 줄 추가하고 sshd 를 다시 읽힌다.

```
GatewayPorts clientspecified
```

없으면 역터널이 `127.0.0.1` 에만 묶여 **호스트에서는 보이지만 컨테이너에서는 안 보인다**.
`0.0.0.0` 으로 열지 않는 이유는 AWS 보안그룹이 1024~65535 를 이미 허용하고 있어
바깥에 그대로 노출되기 때문이다. 도커 브리지에만 묶으면 외부에서 닿지 않는다.

그리고 AI 노트북의 공개키를 서버1 `~/.ssh/authorized_keys` 에 등록한다.
공유 개인키(J15A202T.pem)를 복사해 오는 대신 **터널 전용 키**를 새로 만들었다.
노트북이 털려도 그 한 줄만 지우면 끝난다.

터널만 쓰는 키이므로 명령 실행을 막아 두는 것을 권한다.

```
restrict,port-forwarding,command="/bin/false" ssh-ed25519 AAAA... thispatch-ai-tunnel@ai-node
```

## AI 노드가 할 일 (매번)

```powershell
.\api\start.ps1      # AI 서버 기동, /health 가 ready 가 될 때까지 대기
.\api\tunnel.ps1     # 역터널 연결, 끊기면 재연결
```

WSL 이면 `bash api/start.sh`, `bash api/tunnel.sh`.

## 확인 방법

서버1 에 들어가서:

```bash
curl http://172.17.0.1:8100/health
docker exec backend curl -s http://172.17.0.1:8100/health   # 컨테이너 안에서도
```

## 알아둘 제약

**AI 노트북이 켜져 있고 터널이 붙어 있을 때만 AI 기능이 동작한다.**
서버에 GPU 가 없어 임베딩·요약 모델을 돌릴 수 없어서 생기는 구조적 제약이다.
절전으로 들어가면 끊긴다. 시연 전에는 절전을 꺼 두고 `/health` 를 한 번 확인한다.
