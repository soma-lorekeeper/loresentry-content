package com.loresentry.content.user;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.event.ProjectChangedMessages;
import com.loresentry.content.media.MediaCleanup;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserDeleted 이벤트를 받았을 때 할 일. authentication 서비스가 계정을 지운 뒤 보낸다. 회원 탈퇴가 content 에
 * 들어오는 길은 이것 하나다.
 *
 * <p>그 사용자의 콘텐츠를 모두 지운다(이용약관 제7조). 유예 기간은 없다. 지우는 것은 프로젝트 전부(휴지통 포함)와
 * 그 아래 모든 행, 작업공간 기록, S3 이미지다. feedback 은 내부 기록이라 지우지 않는다. 지운 프로젝트마다
 * ProjectDeleted({@code OWNER_DELETED})를 outbox 에 남겨, graph-rag·검색처럼 같은 프로젝트를 가진 서비스가
 * 지우게 한다.
 *
 * <p>여러 번 처리돼도 결과가 같다. 같은 이벤트가 다시 오면 inbox 의 처리 기록이 거르고, 같은 사용자의 다른 이벤트가
 * 와도 지울 행이 없어 아무것도 쓰지 않는다.
 *
 * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
 * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
 * 없이 처리만 되는 길을 막는다.
 *
 * <p>여기서 하는 DB 쓰기는 같은 thread 에서, 새 트랜잭션을 열지 않고 해야 한다. 다른 thread 로 넘기거나
 * {@code REQUIRES_NEW} 로 새로 열면 그 쓰기는 처리 기록과 따로 확정된다. DB 밖의 일(S3 등)은 commit 뒤로 미룬다 —
 * rollback 되어 이벤트가 다시 오면 처음부터 다시 처리되기 때문이다.
 */
@Service
public class UserDeletedService {

    /** 탈퇴한 사용자의 행을 지우는 곳. */
    private final UserDeletedRepository repository;

    /** S3 이미지를 commit 뒤에 지운다. */
    private final MediaCleanup mediaCleanup;

    /** ProjectDeleted 를 처리 기록과 같은 트랜잭션으로 outbox 에 쓴다. */
    private final OutboxRepository outbox;

    /** content.project.changed.v1 의 메시지를 만든다. */
    private final ProjectChangedMessages messages;

    UserDeletedService(UserDeletedRepository repository, MediaCleanup mediaCleanup, OutboxRepository outbox,
            ProjectChangedMessages messages) {
        this.repository = repository;
        this.mediaCleanup = mediaCleanup;
        this.outbox = outbox;
        this.messages = messages;
    }

    /**
     * 유저 삭제 이벤트 하나를 처리한다. 행을 지운 뒤, 지운 프로젝트마다 ProjectDeleted 를 outbox 에 남긴다.
     *
     * <p>{@link OutboxRepository#append} 는 이 메서드의 마지막에 한 번만 부른다. append 가 잡는 lock 을 쥔 채
     * 다른 행의 lock 을 기다리지 않게 하려는 것이다.
     *
     * @param event 삭제된 유저
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(UserDeletedEvent event) {
        List<UUID> projectIds = purge(event.userId());
        OutboxRecord[] outboxRecords = projectIds.stream()
                .map(projectId -> messages.projectDeleted(projectId, "OWNER_DELETED"))
                .toArray(OutboxRecord[]::new);
        outbox.append(outboxRecords);
    }

    /**
     * 이 사용자의 행을 지우고, S3 이미지는 commit 뒤에 지우도록 걸어 둔다.
     *
     * @param ownerUserId 탈퇴한 사용자
     * @return 지운 프로젝트의 id. 없으면 빈 목록이다
     */
    private List<UUID> purge(UUID ownerUserId) {
        // CASCADE 가 image 행을 지우기 전에 키를 읽어 둔다. 지운 뒤에는 어느 객체를 지울지 다시 찾을 수 없다.
        List<String> imageKeys = repository.imageKeysOf(ownerUserId);
        repository.deleteWorkspaceStates(ownerUserId);
        List<UUID> projectIds = repository.deleteProjects(ownerUserId);
        // 먼저 지우고 rollback 되면 행은 남았는데 이미지가 깨진다. 그래서 commit 뒤에 지운다.
        mediaCleanup.deleteAfterCommit(imageKeys);
        return projectIds;
    }
}
