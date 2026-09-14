# 리뷰 수집 워커

마스터가 `AppidPartitioner`로 배정한 `appid` 목록을 `CollectTasklet`이 처리한다.
현재 대상은 최초 전체 리뷰이며 모든 언어를 `filter=updated`와 cursor로 순회한다.
게임 목록은 기존 `COLLECT_APPIDS` 설정에서 마스터가 배정한다. 카탈로그 조회와 일별 증분 수집은 별도 작업이다.

## 수집과 재시작

1. Tasklet 한 번에 Steam 리뷰 한 페이지를 워커에서 요청한다.
2. 비어 있지 않은 페이지는 HDFS에 직접 JSONL.gz로 저장한다.
3. 파일 확정 후 다음 게임 인덱스·cursor·연속 빈 페이지 횟수를 기존 Spring Batch `ExecutionContext`에 저장한다.
4. `CONTINUABLE`이면 다음 페이지를 처리한다. 빈 페이지가 4회 연속이면 다음 게임으로 넘어간다.

리뷰 원본 필드를 유지하고 `appid`, `collected_ts`만 추가한다.
경로는 `HdfsPaths.REVIEW_LANDING/dt=수집일(KST)/reviews-appid-UUID.jsonl.gz`이며
기존 Spark `JsonToParquet`가 Parquet 변환과 중복 제거를 담당한다.

HDFS 저장 실패는 스텝 실패로 전파하며 실패한 페이지의 cursor를 넘기지 않는다.
재개할 때는 기존 배치 실행 경로에서 **같은 JobInstance의 실패/중단 실행을 재시작**해야 한다.
새 식별 파라미터로 새 JobInstance를 만들면 처음부터 수집한다.
워커 프로세스를 다시 켜는 것만으로 실패한 Job이 자동 재시작되지는 않는다.
강제 종료로 배치 상태가 STARTED에 남은 경우의 상태 복구는 기존 배치 운영 절차가 필요하다.

HDFS 파일 확정과 배치 DB 커밋은 하나의 트랜잭션이 아니다.
두 작업 사이에 실패하면 마지막 페이지가 다시 저장될 수 있다.
이는 누락을 막는 at-least-once 저장이며, Spark가 `(recommendationid, updated_ts)`로 중복을 제거한다.
현재는 페이지당 파일 하나를 만든다. 최초 대량 수집의 파일 수와 Spark 변환 주기는 실환경에서 점검해야 한다.

## 호출 간격과 오류

| 상황 | 처리 |
| --- | --- |
| 정상 요청 | 워커 프로세스당 기본 1초 간격 |
| 403 | cursor 유지, 최소 1시간 대기 |
| 429 | cursor 유지, 최소 1분 대기 |
| Retry-After | 초·HTTP 날짜를 해석하고 기본 대기보다 길면 그 값을 준수 |
| 네트워크 오류, 408, 5xx, API 실패 응답 | 동일 cursor에서 2·4·8·16·32초 등 최대 60초 간격, 기본 5회 재시도 |
| 잘못된 JSON/응답 구조, 나머지 HTTP 오류, 진행하지 않는 cursor | 스텝 실패 |
| HDFS 오류 | 스텝 실패, 배치 재시작 시 같은 페이지 재시도 |

대기 시각은 페이지 진행 정보와 함께 저장한다. 실제 대기는 커밋 후 `afterChunk`에서 수행해
긴 백오프 동안 DB 트랜잭션을 유지하지 않는다. 대기 중 인터럽트와 배치 중단 요청도 확인한다.
403/429는 일시 오류 재시도 횟수와 별개로 대기 후 재시도하며, 반복되면 작업 중단이 필요할 수 있다.

호출 간격은 Steam의 공인 일일 한도나 여러 워커의 공유 공인 IP 한도를 보장하지 않는다.
별도 API 키 일일 카운터 및 여러 프로세스 사이의 전역 호출 제한은 구현하지 않았다.

## 설정

기존 배치 DB·MQ 설정에 다음 환경 변수를 사용할 수 있다.

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `HADOOP_CONF_DIR` | `/opt/hadoop/etc/hadoop` | 워커의 `core-site.xml`, `hdfs-site.xml` 경로 |
| `COLLECT_REQUEST_INTERVAL` | `1s` | 요청 간격, 최소 1ms |
| `COLLECT_MAX_RETRIES` | `5` | 일시 오류 재시도 횟수 |
| `COLLECT_MANAGER_TIMEOUT_MS` | `-1` | 마스터 대기 제한(ms), -1은 제한 없음 |

마스터의 종전 1시간 제한은 403 백오프와 전체 수집 시간보다 짧을 수 있어 기본 제한을 해제했다.
작업 완료·실패 상태는 기존 JobRepository 폴링으로 확인한다.

원격 작업 요청은 기존 동기 처리·AUTO ACK를 유지한다. 마스터 RabbitMQ의
`consumer_timeout`이 활성화돼 있다면 **파티션 전체 실행 시간(백오프 포함)**보다 충분히 커야 한다.
시간 초과 시 채널이 닫히고 요청이 재전달될 수 있으므로 운영 전 브로커 설정을 확인해야 한다.
이 변경은 브로커 설정이나 서비스 상태를 바꾸지 않는다.
[RabbitMQ의 ACK 대기 제한 문서](https://www.rabbitmq.com/docs/consumers#acknowledgement-timeout)

## 검증

저장소 루트에서 다음을 실행한다.

```bash
./gradlew :collector:compileJava
./gradlew :collector:test
```

Windows에서는 `gradlew.bat`를 사용한다.
HTTP·HDFS 대역을 사용하는 단위 테스트와 H2의 실제 Spring Batch JobRepository를 사용하는
재시작 테스트가 포함된다. H2는 테스트 전용이며 운영 배치 DB 설정에는 영향을 주지 않는다.
HDFS 및 RabbitMQ를 연결한 원격 수집과 Spark 변환은 별도 실환경 검증이 필요하다.
