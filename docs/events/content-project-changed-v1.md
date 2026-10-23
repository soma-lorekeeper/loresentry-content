# content.project.changed.v1 이벤트

content 서버가 문서와 프로젝트의 변경을 알리는 topic이다. 이 문서는 payload의 모양과 **어떤 동작에서 어떤 이벤트가 몇 개 나가는지**를 정한다. 공용 설계 문서(`docs` 저장소의 `KAFKA_DESIGN.md`)의 FileChanged·ProjectDeleted 절보다 이 문서가 우선한다.

발행 코드는 이렇게 나뉜다.
- `event/FileChanged`, `event/ProjectDeleted`: payload의 모양
- `event/ProjectChangedMessages`: 동작마다 어떤 메시지(aggregate·key·payload)를 낼지 정하는 곳
- `event/ProjectChangedRepository`: FileChanged 스냅샷을 채울 문서 상태를 읽는 곳
- `outbox/OutboxRepository.append`: 이벤트 내용을 모르고 lock → 조립 → 저장만 하는 곳

## 1. 메시지

| 항목 | 값 |
|---|---|
| topic | `content.project.changed.v1` |
| key | `project_id`. 한 프로젝트의 이벤트는 한 partition으로 간다 |
| `ce_type` | `FileChanged` 또는 `ProjectDeleted` |
| `ce_subject` | FileChanged는 `file_id`, ProjectDeleted는 `project_id` |
| `ce_sequence` | outbox의 `seq`. 비교 규칙은 2.3 |
| `ce_id` | outbox 행 id(uuidv7). 같은 메시지가 두 번 오면 이것으로 거른다 |
| 그 밖의 header | `ce_specversion=1.0`, `ce_source=/content`, `ce_time`, `content-type=application/json` |
| value | 아래 payload(JSON) |

## 2. 원칙

### 2.1 스냅샷
FileChanged는 바뀐 부분이 아니라 **그 문서의 발행 시점 상태 전체**를 싣는다. 소비자는 그 문서의 마지막 이벤트 하나만으로 상태를 만들 수 있어야 한다.

`changed_fields`는 건너뛰기용 힌트다. 무시하고 전부 반영해도 결과가 같아야 한다.

### 2.2 관계 단일 소유
- 관계 한 쌍은 `document_relations`의 한 행이고, `low_document_id < high_document_id`다. uuid 비교는 PostgreSQL 바이트 순서이며, 소문자 문자열 순서와 같다.
- **관계는 low 문서(owner)의 이벤트에만 싣는다.** 그래서 `target_document_id`는 항상 high 문서다.
- high 문서의 이벤트에는 그 관계가 없다. 소비자는 high 문서의 이벤트를 근거로 owner의 관계를 지우면 안 된다.
- 관계가 추가·삭제되거나 설명이 바뀌면 **owner의 `UPDATED [RELATIONS]`**가 나간다. 바꾼 쪽이 high 문서의 저장이어도 마찬가지다.
- 관계 키(`related_character` 등)는 싣지 않는다. 키는 상대 문서의 `folder_code`에서 나오므로 상대 문서의 이벤트로 알 수 있다. 실으면 문서를 옮길 때마다 이웃 문서 이벤트가 연쇄로 나가야 한다.
- 상대 문서가 휴지통에 있어도 행이 남아 있으면 싣는다. 보여 줄지는 소비자가 상대 문서의 `state`로 정한다. 휴지통 이동과 복원이 이웃 이벤트를 만들지 않는 이유다.

### 2.3 순서
- **`ce_sequence`는 같은 `ce_subject` 안에서만 비교한다.** 서로 다른 문서 사이의 크기 비교는 의미가 없다.
- 같은 문서의 이벤트는 seq 순서 = 커밋 순서 = 상태 순서다. content가 이벤트를 쓸 때 **문서 id 단위로** advisory lock을 잡고(`OutboxRepository.append`), 그 lock 안에서 스냅샷을 읽고 seq를 받기 때문이다. 사용자나 프로젝트 단위의 lock은 없다.
- 소비자는 같은 문서에서 이미 반영한 seq 이하의 이벤트를 버린다.
- relay는 실패한 행을 건너뛰고 뒤 행을 계속 보낸다. 그래서 같은 문서의 이벤트가 뒤바뀌어 도착할 수 있다(위 규칙으로 버린다). 서로 다른 문서 사이의 순서도 바뀔 수 있다. 예를 들어 owner의 관계 이벤트가 대상 문서의 CREATED보다 먼저 올 수 있다. 소비자는 아직 모르는 대상 문서로 가는 관계도 지우지 말고 유지해야 한다.

## 3. FileChanged

| 필드 | 타입 | 설명 |
|---|---|---|
| `project_id` | uuid | |
| `file_id` | uuid | `ce_subject`와 같다 |
| `change_type` | `CREATED` \| `UPDATED` \| `TRASHED` \| `RESTORED` \| `DELETED` | 이 이벤트를 낸 동작 |
| `changed_fields` | 배열 | 직전 상태와 달라진 부분. 아래 순서로 나열한다 |
| `file` | object \| null | DELETED일 때만 null |
| `file.title` | string | |
| `file.folder_code` | string | `WORLDVIEW` `CHARACTER` `LOCATION` `MANUSCRIPT` `ORGANIZATION` `ITEM` `EVENT` |
| `file.episode_id` | uuid \| null | `MANUSCRIPT`일 때만 값이 있을 수 있다 |
| `file.state` | `ACTIVE` \| `TRASHED` | |
| `file.body_text` | string | 서식을 뺀 본문. 빈 문서는 `""` |
| `file.properties` | `[{key, value}]` | 문서 속성 전체, 화면 순서. 설명(`description`)도 여기 있다 |
| `relations` | array \| null | **이 문서가 low인 관계만**. DELETED일 때만 null |
| `relations[].relation_id` | uuid | |
| `relations[].target_document_id` | uuid | 상대(high) 문서 |
| `relations[].description` | string | 비어 있을 수 있지만 null은 아니다 |

`changed_fields` 값의 뜻:

| 값 | 달라진 것 |
|---|---|
| `TITLE` | `file.title` |
| `BODY` | `file.body_text`. 서식만 바뀐 저장은 BODY가 아니다 |
| `PROPERTIES` | `file.properties` |
| `FOLDER` | `file.folder_code` 또는 `file.episode_id` |
| `STATE` | `file.state` |
| `RELATIONS` | `relations` |

예시(문서 저장으로 제목과 본문이 바뀜):
```json
{
  "project_id": "0199f1c2-0000-7000-8000-000000000001",
  "file_id": "0199f1c2-0000-7000-8000-00000000000a",
  "change_type": "UPDATED",
  "changed_fields": ["TITLE", "BODY"],
  "file": {
    "title": "유중혁",
    "folder_code": "CHARACTER",
    "episode_id": null,
    "state": "ACTIVE",
    "body_text": "회귀를 반복하는 인물이다.",
    "properties": [{"key": "description", "value": "세 번째 등장인물"}]
  },
  "relations": [
    {"relation_id": "0199f1c2-0000-7000-8000-0000000000c1",
     "target_document_id": "0199f1c2-0000-7000-8000-00000000000b",
     "description": "첫 등장"}
  ]
}
```
DELETED는 `file`, `relations`가 null이고 `changed_fields`는 `[]`다.

## 4. 동작별 발행

"X"는 요청 대상 문서, "owner"는 바뀐 관계의 low 문서다. 이벤트는 그 요청의 트랜잭션 안에서 outbox에 쓰인다. 롤백되면 이벤트도 없다.

| 동작 (API) | 이벤트 | change_type | changed_fields | 비고 |
|---|---|---|---|---|
| 문서 생성 `POST /projects/{p}/files` (kind=document) | X | CREATED | 전부 | 관계 없음, 본문 `""` |
| 에피소드 생성 (kind=episode), 에피소드 이름 변경 `PATCH /episodes/{id}` | 없음 | | | 에피소드는 문서 스냅샷이 아니다. 문서는 `episode_id`만 가진다 |
| 이름 변경 `PATCH /files/{id}` | X | UPDATED | `TITLE` | 같은 이름이어도 나간다 |
| 이동 `PATCH /files/{id}/position` | 폴더나 에피소드가 바뀌면 X | UPDATED | `FOLDER` | 순서(rank)만 바뀌면 이벤트 없음. 이웃 이벤트 없음(2.2) |
| 휴지통 `POST /files/{id}/trash` | X | TRASHED | `STATE` | `file`·`relations`를 그대로 싣는다(`state=TRASHED`). 이미 휴지통이면 없음 |
| 복원 `POST /files/{id}/restore` | X | RESTORED | `STATE` (+`FOLDER`) | 원래 에피소드가 사라져 `episode_id`가 null이 되면 FOLDER도. 이미 활성이면 없음 |
| 영구 삭제 `DELETE /files/{id}` | X + 각 owner | X: DELETED / owner: UPDATED | X: `[]` / owner: `RELATIONS` | X가 high인 관계의 owner만 나간다. X가 owner인 관계는 X의 DELETED로 함께 사라진다 |
| 에피소드 삭제 `DELETE /episodes/{id}` | 그 에피소드에 있던 문서마다(휴지통 포함) | UPDATED | `FOLDER` | `episode_id`가 null이 된다 |
| 문서 저장 `PUT /files/{id}/content` | 바뀐 게 있으면 X + 각 owner | UPDATED | X: 아래 / owner: `RELATIONS` | 아무것도 안 바뀌었거나 같은 `X-Save-Id` 재시도면 없음 |
| 버전 복원 `POST /files/{id}/versions/{v}/restore` | 문서 저장과 같다 | | | |
| 잠금 `PUT /files/{id}/lock`, 버전 저장·삭제 | 없음 | | | payload에 없는 값만 바뀐다 |
| 프로젝트 휴지통 이동·복원 | 없음 | | | 프로젝트 상태는 아직 싣지 않는다 |
| 프로젝트 영구 삭제 `DELETE /projects/{p}` | ProjectDeleted 1개 | | | 5절 |
| 회원 탈퇴 `auth.user.deleted.v1` 수신 | 프로젝트마다 ProjectDeleted | | | 5절 |
| 샘플 프로젝트 생성 `POST /projects/sample` | 위 생성·저장 규칙대로 | | | 내부에서 문서 생성과 저장을 차례로 부른다 |
| 재발행 `POST /internal/admin/file-events/replay` (운영자) | 활성 프로젝트의 활성 문서마다 | UPDATED | 전부 | 프로젝트 하나가 트랜잭션 하나다. 휴지통 문서는 없다 |

**문서 저장의 changed_fields**는 저장 SQL이 실제로 바꾼 것으로 정한다(저장 전후를 따로 읽어 비교하지 않는다).

| 바뀐 것 | X의 changed_fields | owner 이벤트 |
|---|---|---|
| 제목 | `TITLE` | |
| 본문 평문 | `BODY` | |
| 속성 목록(키·값·순서) | `PROPERTIES` | |
| X가 low인 관계의 추가·삭제·설명 변경 | `RELATIONS` | |
| X가 high인 관계의 추가·삭제·설명 변경 | (없음) | 그 관계의 low 문서마다 `UPDATED [RELATIONS]` |
| 아무것도 안 바뀜(서식만 등) | X 이벤트 없음 | 없음 |

예: 캐릭터 C(low)와 원고 M(high) 사이에, M의 화면에서 관계를 걸고 저장하는 경우
- C의 `UPDATED [RELATIONS]`: `relations`에 M과의 관계가 있다
- M은 관계 말고 바뀐 것이 없으면 이벤트가 없다. 본문도 함께 바뀌었다면 M의 `UPDATED [BODY]`가 나가고, 그 `relations`에는 C와의 관계가 없다

## 5. ProjectDeleted

```json
{"project_id": "0199f1c2-0000-7000-8000-000000000001", "reason": "PROJECT_DELETED"}
```

| reason | 발행처 |
|---|---|
| `PROJECT_DELETED` | 휴지통 프로젝트의 영구 삭제(`ProjectService.deletePermanently`) |
| `OWNER_DELETED` | 회원 탈퇴. `auth.user.deleted.v1` 수신. 그 사용자의 프로젝트마다(휴지통 포함) 하나씩(`UserDeletedService.handle`) |

- 소비자에게는 tombstone이다. 그 프로젝트의 모든 문서를 지우고, 이후 오는 그 프로젝트의 이벤트를 무시한다.
- 문서별 DELETED는 따로 나가지 않는다.
- `reason`은 기록용이다. 처리를 다르게 하지 않는다.

## 6. 이전 형식(4aeec2e)과의 차이

| 항목 | 이전 | 지금 |
|---|---|---|
| 관계 | 양쪽 문서 이벤트에 모두 실림 | owner(low) 문서에만 |
| 관계 변경 시 | 저장 전후 이웃 전부 UPDATED | 실제로 바뀐 관계의 owner만 |
| 영구 삭제 시 | 이웃 전부 UPDATED | X가 high인 관계의 owner만 |
| `file.description` | 첫 `description` 속성 | 없음. `file.properties` 전체 |
| `file.episode_id`, `file.state` | 없음 | 있음 |
| `revision_no` | 있음 | 없음 |
| TRASHED | `file`·`relations` null | 전체 스냅샷, `state=TRASHED` |
| UPDATED인데 휴지통 문서 | `change_type`이 TRASHED로 바뀜 | `change_type`은 동작 그대로, 상태는 `file.state` |
| `changed_fields` | 항상 `[TITLE,BODY,FOLDER,RELATIONS]` | 실제로 바뀐 것 |
| 에피소드 삭제 | 이벤트 없음 | 문서마다 `UPDATED [FOLDER]` |
| 이동이 순서만 바꿈 | UPDATED | 없음 |
| 순서 보장 단위 | 사용자(owner advisory lock) | 문서(aggregate advisory lock) |

## 7. graph-rag가 바꿔야 할 것

**배포는 graph-rag가 먼저다.** content가 먼저 나가면, 지금의 graph-rag는 high 문서의 이벤트(관계 없음)를 받을 때 그 문서에 닿은 edge를 모두 지운다(`bothE`). 그러면 owner의 관계가 사라진다.

- `app/adapters/ordered_projection.py`
  - 스냅샷이 지우는 edge를 그 문서가 owner인 것으로 좁힌다. edge는 low→high로 저장되므로 `bothE('REFERENCES')`를 `outE('REFERENCES')`로 바꾼다. 이때 edge의 `sequence` 조건도 뺀다.
  - edge를 만들 때의 `stale` 검사에서 **상대 문서의 sequence 비교와 edge sequence 비교를 뺀다**. 문서 사이의 seq 비교는 의미가 없다(2.3).
  - 상대 문서가 영구 삭제(`deleted`)됐는지 보는 검사는 남긴다.
  - 아직 도착하지 않은 상대 문서의 placeholder를 만드는 동작도 남긴다(2.3).
  - 휴지통 전용 분기(`trashed_file_ids`, 노드에 `trashed=true`만 표시)는 없애고 아래 스냅샷 경로로 합친다.
- `app/kafka/handlers.py`
  - **DELETED가 아닌 모든 이벤트는 전체 스냅샷으로 반영한다.** 제목·폴더·속성·소유 관계를 모두 적용하고, 반영 범위를 `change_type`으로 나누지 않는다. TRASHED도 마찬가지다.
  - 노드의 `trashed`는 `file.state`로 정한다(`TRASHED`면 true). 휴지통 문서의 `UPDATED`(예: 상대 문서의 영구 삭제로 관계가 바뀜, 에피소드 삭제로 위치가 바뀜)도 변화를 반영하되 휴지통 상태를 유지한다.
  - DELETED만 `file`·`relations`가 null이고, 지금처럼 tombstone으로 처리한다.
  - subject가 low가 아닌 관계는 **거부**한다(`InvalidEvent`).
  - 설명은 `file.properties`에서 key가 `description`인 첫 값을 쓴다.
- `docs/implementation/KAFKA_TRANSPORT.md`의 "관계 양쪽 문서", "사용자별 advisory lock", "휴지통은 file/relations=null" 문장을 고친다.
- 테스트 `tests/integration/test_ordered_projection.py::test_latest_file_snapshot_and_relation_fences`를 다시 쓴다(high 쪽이 관계를 보내고, 문서 사이 fence를 기대함).
