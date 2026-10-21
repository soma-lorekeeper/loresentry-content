package com.loresentry.content.outbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final ContentEvents events;

    public FileEventReplay(JdbcClient jdbc, ContentEvents events) {
        this.jdbc = jdbc;
        this.events = events;
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

    @Transactional
    public int replay(UUID projectId) {
        Optional<UUID> owner = jdbc.sql("select owner_user_id from projects where id = :id")
                .param("id", projectId)
                .query(UUID.class)
                .optional();
        if (owner.isEmpty()) {
            return 0;
        }
        events.lock(owner.get());
        List<UUID> documents = jdbc.sql("""
                        select id from document
                        where project_id = :project and trashed_at is null
                        order by id
                        """)
                .param("project", projectId)
                .query(UUID.class)
                .list();
        documents.forEach(id -> events.fileChanged(id, "UPDATED"));
        return documents.size();
    }
}
