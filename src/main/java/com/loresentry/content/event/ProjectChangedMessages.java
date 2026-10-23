package com.loresentry.content.event;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.event.FileChanged.ChangeType;
import com.loresentry.content.event.FileChanged.Field;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;

import org.springframework.stereotype.Component;

/**
 * {@code content.project.changed.v1} 로 갈 메시지를 만든다. payload 의 모양은 {@link FileChanged} 와
 * {@link ProjectDeleted} 가 정하고, 이 클래스는 그것을 어떤 aggregate·key 의 메시지로 낼지 정한다. 비즈니스 코드는
 * 무엇이 바뀌었는지만 넘기고, 받은 메시지를 {@link OutboxRepository#append} 에 준다.
 *
 * <p>FileChanged 의 payload 는 만들 때 DB 를 읽지 않는다. {@link OutboxRepository} 가 그 문서의 lock 을 잡은 뒤에
 * {@link ProjectChangedRepository} 로 읽는다. 동작별로 어떤 메시지가 나가야 하는지는
 * {@code docs/events/content-project-changed-v1.md} 에 있다.
 */
@Component
public class ProjectChangedMessages {

    /** 같은 패키지의 {@link GraphRefreshEventRepository} 도 outbox 에서 이 종류의 최신 이벤트를 찾을 때 쓴다. */
    static final String FILE_CHANGED = "FileChanged";

    private static final String PROJECT_DELETED = "ProjectDeleted";

    private final ProjectChangedRepository repository;

    ProjectChangedMessages(ProjectChangedRepository repository) {
        this.repository = repository;
    }

    /**
     * 문서 하나의 스냅샷 이벤트. 문서가 그대로 있는 모든 변경(생성·수정·휴지통·복원, 관계만 바뀐 owner 문서)에 쓴다.
     *
     * @param changed 이 변경이 실제로 바꾼 부분. 호출자가 쓰기의 결과로 판단해 넘긴다
     */
    public OutboxRecord fileChanged(UUID projectId, UUID fileId, ChangeType type, Set<Field> changed) {
        List<Field> fields = changed.stream().sorted().toList();
        return new OutboxRecord(FILE_CHANGED, fileId, projectId.toString(),
                () -> snapshot(projectId, fileId, type, fields));
    }

    /** 영구 삭제된 문서. 행이 이미 없으므로 읽을 것이 없다. */
    public OutboxRecord fileDeleted(UUID projectId, UUID fileId) {
        return new OutboxRecord(FILE_CHANGED, fileId, projectId.toString(),
                () -> new FileChanged(projectId, fileId, ChangeType.DELETED, List.of(), null, null));
    }

    /** 프로젝트 tombstone. aggregate 와 key 모두 프로젝트다. */
    public OutboxRecord projectDeleted(UUID projectId, String reason) {
        return new OutboxRecord(PROJECT_DELETED, projectId, projectId.toString(),
                () -> new ProjectDeleted(projectId, reason));
    }

    /** lock 안에서 불린다. 문서가 없으면(동시에 영구 삭제됨) null 이고, 그 메시지는 쓰이지 않는다. */
    private FileChanged snapshot(UUID projectId, UUID fileId, ChangeType type, List<Field> fields) {
        return repository.file(fileId)
                .map(file -> new FileChanged(projectId, fileId, type, fields, file,
                        repository.ownedRelations(fileId)))
                .orElse(null);
    }
}
