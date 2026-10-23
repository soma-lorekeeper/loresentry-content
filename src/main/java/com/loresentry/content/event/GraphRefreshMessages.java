package com.loresentry.content.event;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code content.graph-refresh.requested.v1} 로 갈 메시지와 그 S3 입력을 만든다. 이벤트의 모양은
 * {@link GraphRefreshRequested}, 입력의 모양은 {@link RefreshInput} 이 정하고, 이 클래스는 그 둘을 채워 어떤
 * aggregate·key 의 메시지로 낼지 정한다. 비즈니스 코드는 보낼 문서의 id 만 넘기고, 받은 메시지를
 * {@link OutboxRepository#append} 에 준다.
 *
 * <p>순서는 {@link #input} → (비즈니스가 S3 에 올림) → {@link #requested} 다. 입력의 바이트가 정해져야 그 sha256 을
 * 이벤트에 실을 수 있다.
 */
@Component
public class GraphRefreshMessages {

    private static final String GRAPH_REFRESH_REQUESTED = "GraphRefreshRequested";

    private final GraphRefreshEventRepository repository;

    private final JsonMapper json = JsonMapper.builder().build();

    GraphRefreshMessages(GraphRefreshEventRepository repository) {
        this.repository = repository;
    }

    /**
     * S3 에 올릴 입력. 문서를 읽어 {@link RefreshInput} 의 JSON 바이트로 만든다. 이 바이트를 그대로 올려야
     * fingerprint 가 맞는다.
     *
     * @param fileIds 보낼 문서. 그사이 휴지통에 들어가거나 지워진 문서는 입력에서 빠진다
     */
    public PreparedInput input(UUID projectId, List<UUID> fileIds) {
        List<RefreshInput.Document> documents = repository.documents(projectId, fileIds);
        byte[] bytes = json.writeValueAsBytes(new RefreshInput(RefreshInput.SCHEMA_VERSION, documents));
        List<GraphRefreshRequested.File> files = documents.stream()
                .map(document -> new GraphRefreshRequested.File(document.id(), document.revisionNo()))
                .toList();
        return new PreparedInput(bytes, sha256Hex(bytes), files);
    }

    /**
     * 최신화 요청 이벤트. aggregate 는 요청이다 — graph-rag 는 {@code ce_subject} 가 {@code request_id} 인지 본다. key 는
     * 프로젝트라 같은 프로젝트의 요청은 차례로 처리된다.
     *
     * <p>{@code required_events} 는 {@link OutboxRepository} 가 lock 을 잡은 뒤에 읽는다.
     *
     * @param bucket S3 입력을 올린 bucket
     * @param key    S3 입력의 key
     */
    public OutboxRecord requested(UUID projectId, UUID requestId, UUID userId, String bucket, String key,
            PreparedInput input) {
        List<GraphRefreshRequested.File> files = input.files();
        return new OutboxRecord(GRAPH_REFRESH_REQUESTED, requestId, projectId.toString(),
                () -> new GraphRefreshRequested(projectId, requestId, userId, input.fingerprint(),
                        new GraphRefreshRequested.Location(bucket, key), files,
                        repository.latestFileChangedEvents(projectId)));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            // 모든 JVM 이 SHA-256 을 제공해야 한다(MessageDigest 명세).
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * S3 에 올릴 입력.
     *
     * @param json        올릴 바이트
     * @param fingerprint {@code json} 의 sha256, 소문자 16진수
     * @param files       입력에 실린 문서와 그 revision. 최신화 기준선으로 기록한다
     */
    public record PreparedInput(byte[] json, String fingerprint, List<GraphRefreshRequested.File> files) {
    }
}
