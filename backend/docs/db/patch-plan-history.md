# 패치 기획안 내역 저장 구조

관련 이슈: [S15P21A202-269](https://ssafy.atlassian.net/browse/S15P21A202-269)

## 저장 단위

- 유사 사례 검색을 실행할 때마다 새 `patch_plan_id`로 입력 상태를 저장한다.
- 같은 원문으로 검색하거나 최종 슬롯만 바꿔 재검색해도 별도의 내역이다. 원문·회원·게임 조합에 UNIQUE를 두지 않는다.
- 내역 하나에 원문, 선택 장르, 최초 구조화 엔티티·슬롯·재진술·경고, 해당 검색의 최종 확정 슬롯을 함께 보존한다.
- 기존 내역을 재검색해도 이전 내역은 덮어쓰지 않는다. 최초 결과는 AI로 재생성하지 않고 해당 작업에서 받은 값을 저장한다.
- 구조화만 진행하고 검색하지 않은 기획안은 내역에 저장하지 않는다.
- 내역의 `created_at`은 원문을 타이핑한 시각이나 최초 구조화 시각이 아니라 이 검색 내역을 저장한 시각이다.
- 실제 저장 연동과 목록·상세 API는 후속 이슈의 범위다. 이번 migration만으로 기존 API가 내역을 저장하지는 않는다.
- 검색 실패 시 저장 여부, 요청 재전송의 중복 방지, 최초 구조화 결과를 서버에서 신뢰할 수 있게 전달·연결하는 방법은 API 명세에서 별도로 확정한다.

## 테이블과 컬럼

별도 표기가 없으면 NOT NULL이다. 신규 독립 PK는 BIGSERIAL, 시간은 TIMESTAMPTZ이며 저장 로직이 값을 지정한다.

### patch_plan — 검색 한 번의 기획안 원문과 작성 정보

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_id | BIGSERIAL, PK | 검색 내역 고유 ID |
| member_id | BIGINT, FK | member.member_id, 작성자 |
| appid | BIGINT, FK | game.appid, 기획안 대상 게임 |
| raw_text | TEXT | 입력 원문 전체 |
| created_at | TIMESTAMPTZ | 내역 저장 시각 |

### patch_plan_genre — 선택한 장르

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_id | BIGINT, PK/FK | patch_plan 참조 |
| genre_id | INTEGER, PK/FK | tag.tag_id, 선택한 장르 |

복합 PK는 `(patch_plan_id, genre_id)`다. 검색에 제출한 선택값을 저장하며 게임의 전체 장르로 대체하지 않는다.
기존 검색 계약의 빈 배열(전체 장르)은 연결 행 0개로 표현한다. 장르의 표시 순서는 저장하지 않는다.

### patch_plan_entity — 최초 탐지 대상

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_entity_id | BIGSERIAL, PK | 최초 엔티티 고유 ID |
| patch_plan_id | BIGINT, FK | patch_plan 참조 |
| entity_order | INTEGER | 기획안 내 최초 엔티티 순서, 1 이상 |
| name | TEXT | 탐지 대상 이름 |
| role | VARCHAR(30) | 최초 대상 역할 |
| warning_code | VARCHAR(30), NULL 허용 | 대상별 UNKNOWN_ENTITY 경고 코드 |
| warning_message | TEXT, NULL 허용 | 당시 경고 문구 |

`(patch_plan_id, entity_order)`는 UNIQUE다. 현재 구조화 코드의 `(name, role)` 중복 제거는 저장 로직이 유지한다.
길이 제한 없는 대상 이름을 UNIQUE 인덱스에 넣어 긴 원문 저장을 막지 않는다.
현재 UNKNOWN_ENTITY는 역할 UNKNOWN 또는 빈 대상 이름일 때 발생한다. 방향·범위 미확인과 동일한 의미로 취급하지 않는다.

### patch_plan_slot — 최초 변경 슬롯

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_slot_id | BIGSERIAL, PK | 최초 슬롯 고유 ID |
| patch_plan_entity_id | BIGINT, FK | 최초 변경 대상 엔티티 |
| slot_order | INTEGER | 기획안 전체 최초 슬롯 순서, 1 이상 |
| attribute | TEXT | 변경 속성 |
| change_type | VARCHAR(30) | 변경 유형 |
| direction | VARCHAR(30) | 변경 방향 |
| magnitude | TEXT, NULL 허용 | 변화량 문자열 |
| scope | TEXT, NULL 허용 | 적용 조건·범위 |

대상 이름·역할 및 기획안 ID는 엔티티를 통해 얻는다. `(patch_plan_entity_id, slot_order)`는 UNIQUE다.
이 제약은 같은 엔티티의 중복 순서만 막는다. 기획안 전체의 슬롯 순서 중복 방지와 연속된 순서 부여는 저장 로직이 담당한다.
복원할 때는 엔티티를 묶은 순서가 아니라 `slot_order`로 정렬한다.

### patch_plan_restatement — 최초 재진술과 전체 경고

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_id | BIGINT, PK/FK | patch_plan 참조, 기획안당 최대 한 행 |
| text | TEXT | 최초 restatement.text 전체 |
| warning_code | VARCHAR(30), NULL 허용 | 전체 NO_CHANGES 경고 코드 |
| warning_message | TEXT, NULL 허용 | 당시 전체 경고 문구 |
| created_at | TIMESTAMPTZ | 재진술 저장 시각 |

대상별 경고와 전체 경고는 현재 API의 두 경고 종류를 보존한다. 경고 배열의 새 형태를 정의하지 않는다.

### patch_plan_confirmed_slot — 해당 검색의 최종 확정 슬롯

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| patch_plan_confirmed_slot_id | BIGSERIAL, PK | 최종 슬롯 고유 ID |
| patch_plan_id | BIGINT, FK | patch_plan 참조 |
| slot_order | INTEGER | 최종 제출 목록 순서, 1 이상 |
| target_name | TEXT | 최종 대상 이름 |
| target_role | VARCHAR(30) | 최종 대상 역할 |
| attribute | TEXT | 최종 변경 속성 |
| change_type | VARCHAR(30) | 최종 변경 유형 |
| direction | VARCHAR(30) | 최종 변경 방향 |
| magnitude | TEXT, NULL 허용 | 최종 변화량 |
| scope | TEXT, NULL 허용 | 최종 적용 조건·범위 |
| created_at | TIMESTAMPTZ | 최종 슬롯 저장 시각 |

`(patch_plan_id, slot_order)`는 UNIQUE다. 대상 수정·추가·삭제를 보존하기 위해 최초 엔티티/슬롯 FK는 두지 않는다.
검색을 다시 하면 새 기획안 행 아래에 새 슬롯 묶음을 추가한다.

## 제약과 인덱스

- 역할은 PLAYER, ENEMY, WEAPON, ITEM, SKILL, MAP, SYSTEM, OTHER, UNKNOWN을 허용한다.
- 변경 유형은 ADD, REMOVE, MODIFY, FIX, DEPRECATE를 허용한다.
- 방향은 INCREASE, DECREASE, NONE, NOT_APPLICABLE, UNKNOWN을 허용한다.
- 코드는 기존 구조화·검색 DTO의 대문자 enum 값을 저장하며 CHECK로 검증한다.
- 경고 코드·문구는 모두 NULL이거나 모두 존재해야 한다. 엔티티는 UNKNOWN_ENTITY, 재진술은 NO_CHANGES만 허용한다.
- 기존 API가 허용하는 빈 대상·속성·재진술은 저장할 수 있다. 임의의 문자열 길이·빈 문자열 제한은 추가하지 않는다.
- FK는 기존 스키마처럼 ON DELETE/UPDATE NO ACTION을 사용하며 연쇄 삭제를 추가하지 않는다. 회원탈퇴는 기존 상태 변경 정책을 따른다.
- 사용자별 최신순 조회에 `(member_id, created_at DESC, patch_plan_id DESC)` 인덱스를 둔다.
- 장르·엔티티·슬롯·재진술·최종 슬롯의 하위 조회에는 PK/UNIQUE 인덱스를 재사용한다.
- 하위 행의 최소 개수, 기획안 전체 슬롯 순서, 전체 저장의 원자성은 후속 저장 트랜잭션이 보장한다. 이 migration은 API 저장 서비스를 추가하지 않는다.

## 제외 범위

- 제목·별도 목록 요약: 원문 앞부분을 내역 제목으로 표시한다.
- 응답 전체 JSON, restatement.highlights 별도 저장
- 검색 결과·통계·상세 비교 정보 저장 및 복원
- 게임 이름·이미지와 장르 이름의 당시 값 보존: 기존 테이블 조회값을 사용한다.
- API 경로·요청·응답 변경, JPA 엔티티·저장 서비스·프론트엔드 구현
