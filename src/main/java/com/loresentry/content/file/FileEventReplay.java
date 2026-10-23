package com.loresentry.content.file;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.event.FileChanged.ChangeType;
import com.loresentry.content.event.FileChanged.Field;
import com.loresentry.content.event.ProjectChangedMessages;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 활성 문서마다 FileChanged 를 outbox 에 다시 쓴다. 이벤트가 생기기 전에 만들어진 문서를 소비자에게 한 번 알리는
 * 관리 작업이다. 프로젝트 하나를 트랜잭션 하나로 묶어, 큰 서비스에서도 잠금과 트랜잭션이 짧게 끝난다.
 */
@Component
public class FileEventReplay {

    private final JdbcClient jdbc;

    private final ProjectChangedMessages messages;

    private final OutboxRepository outbox;

    public FileEventReplay(JdbcClient jdbc, ProjectChangedMessages messages, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.outbox = outbox;
    }

    public List<UUID> projectsAfter(UUID after, int limit) {
        return jdbc.sql("""
                        select id from projects
                        where trashed_at is null and (cast(:after as uuid) is null or id > cast(:after as uuid))
                        order by id limit :limit
                        """)
                .param("after", after)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * 소비자가 이 문서를 어떤 상태로 알고 있는지 모르므로, changed_fields 는 문서 생성처럼 전부다. 스냅샷은
     * {@link OutboxRepository#append} 가 문서마다 lock 을 잡은 뒤에 읽는다.
     */
    @Transactional
    public int replay(UUID projectId) {
        List<UUID> documents = jdbc.sql("""
                        select id from document
                        where project_id = :project and trashed_at is null
                        order by id
                        """)
                .param("project", projectId)
                .query(UUID.class)
                .list();
        // append 는 트랜잭션마다 한 번만 부른다. 문서들의 lock 을 정렬된 순서로 한꺼번에 잡아야 교착이 없다.
        outbox.append(documents.stream()
                .map(id -> messages.fileChanged(projectId, id, ChangeType.UPDATED, EnumSet.allOf(Field.class)))
                .toArray(OutboxRecord[]::new));
        return documents.size();
    }
}
