package com.loresentry.content.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.event.FileChanged.File;
import com.loresentry.content.event.FileChanged.Property;
import com.loresentry.content.event.FileChanged.Relation;
import com.loresentry.content.event.FileChanged.State;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FileChanged 스냅샷을 채울 문서 상태를 읽는다. 편집 화면용 조회({@code DocumentRepository})와 규칙이 다르다 —
 * 관계는 이 문서가 low 인 것만, 상대가 휴지통에 있어도 싣고, 관계 키는 싣지 않는다.
 *
 * <p>두 메서드 모두 {@code MANDATORY} 다. {@code OutboxRepository.append} 가 이 문서의 lock 을 잡은 뒤, 같은
 * 트랜잭션 안에서만 읽어야 스냅샷이 커밋 순서와 맞는다. 트랜잭션 없이 불리면 읽기 전에 예외를 던진다.
 */
@Repository
class ProjectChangedRepository {

    private final JdbcClient jdbcClient;

    ProjectChangedRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 문서의 내용·위치·상태와 속성. 문서가 없으면(동시에 영구 삭제됨) 비어 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<File> file(UUID fileId) {
        // 편집 화면(DocumentRepository.properties)과 같은 순서다.
        List<Property> properties = jdbcClient
                .sql("select property_key, text_value from document_properties "
                        + "where document_id = :id order by position, property_key")
                .param("id", fileId)
                .query((rows, index) -> new Property(rows.getString("property_key"), rows.getString("text_value")))
                .list();
        return jdbcClient
                .sql("""
                        select d.title, f.code as folder_code, d.episode_id,
                               d.trashed_at is not null as trashed, d.revision_no, d.body_text
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.id = :id
                        """)
                .param("id", fileId)
                .query((rows, index) -> new File(
                        rows.getString("title"),
                        rows.getString("folder_code"),
                        rows.getObject("episode_id", UUID.class),
                        rows.getBoolean("trashed") ? State.TRASHED : State.ACTIVE,
                        rows.getLong("revision_no"),
                        rows.getString("body_text"),
                        properties))
                .optional();
    }

    /**
     * 이 문서가 low 인 관계. high 쪽 행은 상대(owner) 문서의 이벤트에 실린다. 상대가 휴지통에 있어도 행이 있으면
     * 싣는다 — 보일지는 소비자가 상대의 state 로 정한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Relation> ownedRelations(UUID fileId) {
        return jdbcClient
                .sql("select id, high_document_id, description from document_relations "
                        + "where low_document_id = :id order by id")
                .param("id", fileId)
                .query((rows, index) -> new Relation(
                        rows.getObject("id", UUID.class),
                        rows.getObject("high_document_id", UUID.class),
                        rows.getString("description")))
                .list();
    }
}
