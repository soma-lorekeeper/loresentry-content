package com.loresentry.content.document;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.loresentry.content.event.FileChanged.ChangeType;
import com.loresentry.content.event.FileChanged.Field;
import com.loresentry.content.event.ProjectChangedMessages;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;
import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DocumentService {

    /** 요구사항 §3.5. 자동 버전은 이 간격보다 자주 남기지 않는다. */
    static final Duration AUTO_VERSION_INTERVAL = Duration.ofMinutes(5);

    /** 요구사항 §3.5. 자동 버전의 보관 기간. 이름 붙인 버전은 영구 보관한다. */
    static final Duration AUTO_VERSION_RETENTION = Duration.ofDays(30);

    static final int BODY_MAX = 1_000_000;

    /** 에디터 스키마의 판. 노드·마크 구성이 바뀌면 올린다. */
    static final int BODY_SCHEMA_VERSION = 1;

    private final JsonMapper json = JsonMapper.builder().build();

    static final int TITLE_MAX = 255;

    private final DocumentRepository repository;

    private final ProjectActivity activity;

    /** 저장이 바꾼 문서들의 이벤트를 메서드 마지막에 한 번 쓴다. 고르는 규칙은 {@link #savedMessages}. */
    private final OutboxRepository outbox;

    private final ProjectChangedMessages messages;

    public DocumentService(DocumentRepository repository, ProjectActivity activity, OutboxRepository outbox,
            ProjectChangedMessages messages) {
        this.repository = repository;
        this.activity = activity;
        this.outbox = outbox;
        this.messages = messages;
    }

    /** 휴지통 문서는 목록에만 보이고 열리지 않는다. 그래서 여기도 {@link #requireHeader}를 지난다. */
    @Transactional(readOnly = true)
    public DocumentResponses.Content get(UUID ownerUserId, UUID fileId) {
        return repository.contentOf(requireHeader(ownerUserId, fileId));
    }

    /**
     * 조건부 저장이다. {@code expectedRevision}이 어긋나면 덮어쓰지 않고 현재 문서와 공통 조상을
     * 돌려준다. 같은 {@code saveId}가 다시 오면 revision 을 또 올리지 않는다 — 응답을 받지 못한
     * 클라이언트의 재시도이므로, 두 번 적용하면 같은 내용의 revision 이 두 개 생긴다.
     */
    @Transactional
    public DocumentResponses.Content save(UUID ownerUserId, UUID fileId, long expectedRevision,
            UUID saveId, DocumentSnapshot incoming) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        if (header.locked()) {
            throw new ContentFailure(ContentFailure.Reason.DOCUMENT_LOCKED);
        }

        if (saveId != null && saveId.equals(header.lastSaveId())) {
            return repository.contentOf(header);
        }

        DocumentSnapshot snapshot = validate(header.projectId(), fileId, incoming);

        Optional<DocumentRepository.Updated> updated;
        try {
            updated = repository.updateIfRevisionMatches(fileId, expectedRevision, snapshot,
                    BodyText.extract(snapshot.body().get("doc")), saveId);
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }

        if (updated.isEmpty()) {
            DocumentRepository.Header current = repository.findHeader(ownerUserId, fileId)
                    .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
            // 응답을 못 받은 클라이언트의 재시도가 원래 요청과 동시에 들어오면, 먼저 커밋한 쪽이 revision 을 올려
            // 이쪽 조건이 어긋난다. 충돌이 아니라 같은 저장이므로 그 결과를 돌려준다. 위의 검사는 원래 요청이
            // 이 요청보다 먼저 끝난 경우만 잡는다.
            if (saveId != null && saveId.equals(current.lastSaveId())) {
                return repository.contentOf(current);
            }
            throw new DocumentConflict(repository.contentOf(current),
                    repository.snapshotAtRevision(fileId, expectedRevision).orElse(null));
        }

        // 조건부 update 가 이 문서 행을 잠갔다. 속성은 이 문서를 저장하는 트랜잭션만 바꾸므로, 지금 읽은 값이 곧
        // 이번 저장 직전의 값이다.
        boolean propertiesChanged = !repository.properties(fileId).equals(snapshot.properties());
        repository.replaceProperties(fileId, snapshot.properties());
        List<DocumentRepository.RelationPair> changedRelations =
                repository.replaceRelations(fileId, snapshot.relations());

        DocumentResponses.Content saved = repository.find(ownerUserId, fileId).orElseThrow();
        recordAutoVersion(fileId, saved);
        // 문서를 쓴 것이 곧 프로젝트를 작업한 것이다. 같은 트랜잭션에서 올린다.
        activity.recordWorkedFile(header.projectId(), fileId);
        outbox.append(savedMessages(header.projectId(), fileId, updated.get(), propertiesChanged,
                changedRelations));
        return saved;
    }

    /** 잠금은 편집 화면의 상태라 이벤트 payload 에 없다. */
    @Transactional
    public DocumentResponses.Content setLocked(UUID ownerUserId, UUID fileId, boolean locked) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        repository.setLocked(fileId, locked);
        activity.touch(header.projectId());
        return repository.find(ownerUserId, fileId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public DocumentResponses.VersionList versions(UUID ownerUserId, UUID fileId) {
        requireHeader(ownerUserId, fileId);
        return new DocumentResponses.VersionList(repository.versions(fileId));
    }

    /** 사용자가 이름을 붙인 버전은 파일이 영구 삭제될 때까지 남는다 — {@code expires_at}이 없다. */
    @Transactional
    public DocumentResponses.Version saveNamed(UUID ownerUserId, UUID fileId, String label) {
        DocumentResponses.Content current = get(ownerUserId, fileId);
        UUID versionId = repository.insertVersion(fileId, current.revisionNo(), "NAMED",
                label == null || label.isBlank() ? null : label.trim(), current.snapshot(), null);
        return repository.findVersion(fileId, versionId).orElseThrow();
    }

    /**
     * 복원은 문서를 되감는 것이 아니라 <b>새 변경으로 저장</b>한다(요구사항 §3.5). 되감으면 복원
     * 자체가 기록에서 사라져 무엇이 언제 바뀌었는지 설명할 수 없다.
     *
     * <p>복원 직전 상태를 먼저 버전으로 남긴다. 복원이 잘못된 선택이었을 때 돌아올 자리가 필요하다.
     */
    @Transactional
    public DocumentResponses.Content restore(UUID ownerUserId, UUID fileId, UUID versionId,
            long expectedRevision) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        if (header.locked()) {
            throw new ContentFailure(ContentFailure.Reason.DOCUMENT_LOCKED);
        }

        DocumentResponses.Version version = repository.findVersion(fileId, versionId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.VERSION_NOT_FOUND));

        DocumentResponses.Content current = repository.contentOf(header);
        repository.insertVersion(fileId, current.revisionNo(), "RESTORE", "복원 전", current.snapshot(), null);

        DocumentSnapshot snapshot = validate(header.projectId(), fileId, version.snapshot());
        Optional<DocumentRepository.Updated> updated;
        try {
            updated = repository.updateIfRevisionMatches(fileId, expectedRevision, snapshot,
                    BodyText.extract(snapshot.body().get("doc")), null);
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }
        if (updated.isEmpty()) {
            throw new DocumentConflict(current,
                    repository.snapshotAtRevision(fileId, expectedRevision).orElse(null));
        }

        // 저장과 같다 — 조건부 update 가 이 문서 행을 잠근 뒤라 지금 읽은 속성이 복원 직전의 값이다.
        boolean propertiesChanged = !repository.properties(fileId).equals(snapshot.properties());
        repository.replaceProperties(fileId, snapshot.properties());
        List<DocumentRepository.RelationPair> changedRelations =
                repository.replaceRelations(fileId, snapshot.relations());
        // 버전 복원도 본문을 바꾼다.
        activity.recordWorkedFile(header.projectId(), fileId);
        DocumentResponses.Content restored = repository.find(ownerUserId, fileId).orElseThrow();
        outbox.append(savedMessages(header.projectId(), fileId, updated.get(), propertiesChanged,
                changedRelations));
        return restored;
    }

    @Transactional
    public void deleteVersion(UUID ownerUserId, UUID fileId, UUID versionId) {
        requireHeader(ownerUserId, fileId);
        DocumentResponses.Version version = repository.findVersion(fileId, versionId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.VERSION_NOT_FOUND));
        repository.deleteVersion(version.id());
    }

    /**
     * 저장(또는 버전 복원)이 실제로 바꾼 것으로 이벤트를 고른다.
     *
     * <p>이 문서의 이벤트는 저장이 실제로 바꾼 것이 있을 때만 나간다. 관계는 low 문서가 가진다(관계
     * 단일 소유). 그래서 바뀐 관계 쌍의 low 가 이 문서면 이 문서의 RELATIONS 가 바뀐 것이고, low 가 다른 문서면 그
     * 문서의 관계가 바뀐 것이다 — 그 문서는 저장되지 않았어도 이벤트를 받아야 한다.
     *
     * @param changedRelations 저장 SQL 이 실제로 지우거나 만들거나 설명을 바꾼 쌍
     */
    private OutboxRecord[] savedMessages(UUID projectId, UUID fileId, DocumentRepository.Updated updated,
            boolean propertiesChanged, List<DocumentRepository.RelationPair> changedRelations) {
        Set<Field> fields = EnumSet.noneOf(Field.class);
        if (updated.titleChanged()) {
            fields.add(Field.TITLE);
        }
        if (updated.bodyChanged()) {
            fields.add(Field.BODY);
        }
        if (propertiesChanged) {
            fields.add(Field.PROPERTIES);
        }

        List<OutboxRecord> owners = new ArrayList<>();
        Set<UUID> lowIds = changedRelations.stream()
                .map(DocumentRepository.RelationPair::low)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (UUID lowId : lowIds) {
            if (lowId.equals(fileId)) {
                fields.add(Field.RELATIONS);
            } else {
                owners.add(messages.fileChanged(projectId, lowId, ChangeType.UPDATED, Set.of(Field.RELATIONS)));
            }
        }

        List<OutboxRecord> result = new ArrayList<>();
        // 저장이 바꾼 것이 없으면(서식만 바뀐 저장 등) 이 문서의 스냅샷은 직전 이벤트와 같다.
        if (!fields.isEmpty()) {
            result.add(messages.fileChanged(projectId, fileId, ChangeType.UPDATED, fields));
        }
        result.addAll(owners);

        return result.toArray(OutboxRecord[]::new);
    }

    private void recordAutoVersion(UUID fileId, DocumentResponses.Content saved) {
        OffsetDateTime last = repository.lastAutoVersionAt(fileId).orElse(null);
        if (last != null && last.isAfter(OffsetDateTime.now().minus(AUTO_VERSION_INTERVAL))) {
            return;
        }
        repository.insertVersion(fileId, saved.revisionNo(), "AUTO", null, saved.snapshot(),
                OffsetDateTime.now().plus(AUTO_VERSION_RETENTION));
    }

    private DocumentRepository.Header requireHeader(UUID ownerUserId, UUID fileId) {
        DocumentRepository.Header header = repository.findHeader(ownerUserId, fileId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
        if (header.trashedAt() != null) {
            throw new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND);
        }
        return header;
    }

    private DocumentSnapshot validate(UUID projectId, UUID fileId, DocumentSnapshot incoming) {
        if (incoming == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        String title = incoming.title() == null ? "" : incoming.title().trim();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_TITLE);
        }

        JsonNode body = validateBody(incoming.body());

        List<DocumentSnapshot.TextProperty> properties =
                incoming.properties() == null ? List.of() : incoming.properties();
        for (DocumentSnapshot.TextProperty property : properties) {
            if (property.key() == null || property.key().isBlank() || property.value() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
        }

        List<DocumentSnapshot.Relation> relations =
                incoming.relations() == null ? List.of() : incoming.relations();
        for (DocumentSnapshot.Relation relation : relations) {
            // relation_key 는 받되 쓰지 않는다 — 키는 대상 문서의 분류에서 나온다(V10). 보내온 키가
            // 틀렸다고 막는 것은 시늉이다. 어차피 버릴 값이고, 막으면 옛 클라이언트만 못 쓰게 된다.
            if (relation.targetDocumentId() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
            // 자기 참조는 그래프에 자기 루프를 만들고 타임라인에서 의미가 없다.
            if (relation.targetDocumentId().equals(fileId)
                    || !repository.isUsableRelationTarget(projectId, relation.targetDocumentId())) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_RELATION_TARGET);
            }
        }

        // legacy_body_md 는 요청에서 무시한다. Markdown 을 해석하는 곳은 프론트다.
        return new DocumentSnapshot(title, body, null, properties, relations);
    }

    /** 에디터가 만드는 노드·마크만 받는다. 확장을 더하면 이 목록과 {@code schema_version} 을 같이 올린다. */
    private static final Set<String> NODES = Set.of(
            "doc", "paragraph", "text", "heading", "bulletList", "orderedList", "listItem",
            "blockquote", "codeBlock", "horizontalRule", "hardBreak");

    private static final Set<String> MARKS =
            Set.of("bold", "italic", "underline", "strike", "code", "link");

    /** 직렬화한 본문 JSON 의 한도. 여기서 막지 않으면 한 문서가 저장소와 응답을 다 차지할 수 있다. */
    private static final int BODY_JSON_BYTES_MAX = 4 * 1024 * 1024;

    /**
     * 본문 모양을 확인한다.
     *
     * <p>모르는 노드·마크를 그대로 저장하면 다른 클라이언트가 열 수 없는 문서가 생긴다. 허용 목록
     * 밖이면 받지 않는다 — 조용히 지우면 사용자는 글이 사라진 것으로 본다.
     */
    private JsonNode validateBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        JsonNode doc = body.get("doc");
        if (doc == null || !"doc".equals(doc.path("type").asString(""))) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        if (body.path("schema_version").asInt(0) != BODY_SCHEMA_VERSION) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        checkNode(doc);
        if (json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length
                > BODY_JSON_BYTES_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        if (BodyText.extract(doc).length() > BODY_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        return body;
    }

    private void checkNode(JsonNode node) {
        if (!NODES.contains(node.path("type").asString(""))) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        JsonNode marks = node.get("marks");
        if (marks != null && marks.isArray()) {
            for (JsonNode mark : marks) {
                if (!MARKS.contains(mark.path("type").asString(""))) {
                    throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
                }
            }
        }
        JsonNode children = node.get("content");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                checkNode(child);
            }
        }
    }

}
