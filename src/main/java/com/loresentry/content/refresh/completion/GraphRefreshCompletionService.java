package com.loresentry.content.refresh.completion;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.manuscript.Envelope;
import com.loresentry.content.manuscript.Manuscripts;
import com.loresentry.content.manuscript.Purpose;
import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.refresh.RefreshCleanup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

/**
 * 그래프 최신화 완료.
 *
 * <p>graph-rag 가 끝났다고(성공이든 실패든) 보낸 완료 이벤트를 두 단계로 처리한다.
 * <ul>
 *   <li>{@link #fetchResult}: DB 트랜잭션 밖에서 결과 파일을 가져온다.
 *   <li>{@link #complete}: inbox 처리 기록과 같은 트랜잭션에서 run 을 끝내거나 검토 작업본을 연다.
 * </ul>
 */
@Service
public class GraphRefreshCompletionService {

    private final GraphRefreshRepository repository;

    /** graph-rag 결과 파일을 S3 에서 읽는다. */
    private final RefreshResultStorage results;

    /** 결과로 검토 작업본을 연다. */
    private final RefreshDraftBuilder builder;

    /** run 이 끝날 때 작업본·기준 버전·잠금을 치운다. */
    private final RefreshCleanup cleanup;

    /** graph-rag 가 결과를 쓰는 bucket. 설정돼 있으면 완료 이벤트가 다른 bucket 을 가리킬 때 읽지 않는다. */
    private final String completedBucket;

    /** 결과 파일 크기 한도. graph-rag 의 객체 한도와 같다. */
    private final long resultMaxBytes;

    private final Manuscripts manuscripts;

    GraphRefreshCompletionService(GraphRefreshRepository repository, RefreshResultStorage results,
            RefreshDraftBuilder builder, RefreshCleanup cleanup,
            @Value("${graph-refresh.completed-bucket:}") String completedBucket,
            @Value("${graph-refresh.result-max-bytes:16777216}") long resultMaxBytes, Manuscripts manuscripts) {
        this.repository = repository;
        this.results = results;
        this.builder = builder;
        this.cleanup = cleanup;
        this.completedBucket = completedBucket;
        this.resultMaxBytes = resultMaxBytes;
        this.manuscripts = manuscripts;
    }

    /**
     * 완료 이벤트가 가리키는 결과 파일을 가져온다. DB 트랜잭션 밖에서(listener 가 처리 기록을 열기 전에) 부른다 —
     * S3 를 읽는 동안 DB connection 을 잡지 않는다. 상태는 바꾸지 않는다.
     *
     * <p>run 이 없거나(프로젝트 삭제) 이미 끝났으면(중복·늦은 이벤트) S3 를 읽지 않는다. 결과 파일을 쓸 수 없으면 그
     * 이유를 돌려주고, run 은 반영 때 그 이유로 실패한다. S3 일시 장애는 예외로 나가 Kafka 가 다시 보낸다.
     */
    public ResultFetch fetchResult(GraphRefreshCompletedEvent event) {
        AppLogger.current().info("graph refresh result fetch", LogField.projectId(event.projectId()),
                LogField.refreshRequestId(event.requestId()), LogField.param("outcome", event.outcome()));
        var run = repository.peek(event.requestId());
        if (run.isEmpty() || !run.get().projectId().equals(event.projectId())
                || !"GENERATING".equals(run.get().status())) {
            // 없거나 다른 프로젝트이거나 이미 끝난 run 이라 결과 파일을 읽지 않는다
            logSkipped("graph refresh result fetch skipped", event, run);
            return new ResultFetch.Skip();
        }
        GraphRefreshCompletedEvent.Result location = event.result();
        if (location == null) {
            return event.outcome() == GraphRefreshCompletedEvent.Outcome.FAILED
                    ? new ResultFetch.NoResult()
                    : unusable(event, "RESULT_MISSING", "MISSING", "Succeeded without a result location", null);
        }
        if (location.bucket() == null || location.bucket().isBlank() || location.key() == null
                || location.key().isBlank()
                || (!completedBucket.isBlank() && !completedBucket.equals(location.bucket()))) {
            return unusable(event, "RESULT_LOCATION_INVALID", "LOCATION_INVALID", "Result is not in the refresh bucket",
                    null);
        }

        RefreshResultStorage.Stored stored;
        try {
            stored = results.read(location.bucket(), location.key(), resultMaxBytes);
        } catch (RefreshResultStorage.Unreadable unreadable) {
            return unusable(event, unreadable.code(), unreadable.reason(), unreadable.getMessage(), null);
        }
        byte[] plain = stored.bytes();
        if (Envelope.isEnvelope(plain)) {
            try {
                plain = manuscripts.openBytes(event.projectId(), event.requestId(), Purpose.REFRESH_RESULT, plain);
            } catch (Envelope.EnvelopeException unreadable) {
                return unusable(event, "RESULT_INVALID", "DECRYPT_FAILED", "Result cannot be decrypted", unreadable);
            }
        }
        RefreshResult result;
        try {
            result = RefreshResult.parse(plain);
        } catch (JacksonException malformed) {
            return unusable(event, "RESULT_INVALID", "JSON_INVALID", "Result is not valid JSON", malformed);
        }
        if (!RefreshResult.SCHEMA_VERSION.equals(result.schemaVersion())
                || !event.requestId().toString().equals(result.requestId())
                || !event.projectId().toString().equals(result.projectId())
                || !OUTCOMES.contains(result.outcome())) {
            // 로그에 남길 이유. 판이 다르면 SCHEMA_VERSION, 판이 같고 outcome 이 계약 밖이면 OUTCOME_INVALID,
            // 그 밖에는 요청·프로젝트 id 가 다른 것이다
            String reason = !RefreshResult.SCHEMA_VERSION.equals(result.schemaVersion()) ? "SCHEMA_VERSION"
                    : OUTCOMES.contains(result.outcome()) ? "NOT_THIS_REQUEST" : "OUTCOME_INVALID";
            return unusable(event, "RESULT_INVALID", reason, "Result does not belong to this request", null);
        }
        return new ResultFetch.Read(result, new String(plain, StandardCharsets.UTF_8), stored.sha256(),
                location.bucket(), location.key());
    }

    /** 결과 파일의 outcome 으로 올 수 있는 값. */
    private static final Set<String> OUTCOMES = Set.of("PROPOSED", "NO_CHANGE", "FAILED");

    /** 쓸 수 없는 결과의 세부 이유(로그 전용). 결과 위치·S3 읽기({@link RefreshResultStorage})·복호화·JSON·대조 단계다. */
    private static final Set<String> UNUSABLE_REASONS = Set.of("MISSING", "LOCATION_INVALID", "TOO_LARGE",
            "SHA256_MISSING", "SHA256_MISMATCH", "EXPIRED", "DECRYPT_FAILED", "JSON_INVALID", "SCHEMA_VERSION",
            "NOT_THIS_REQUEST", "OUTCOME_INVALID");

    /** run 의 상태 값(ck_refresh_runs_status). 건너뛴 줄에 남긴다. */
    private static final Set<String> RUN_STATUSES = Set.of("CAPTURING_BASE", "GENERATING", "READY", "NO_CHANGE",
            "APPLYING", "APPLIED", "FAILED", "CANCELLED");

    /** 결과를 이미 반영한 run 의 상태. 이 상태라서 건너뛴 것은 같은 결과가 한 번 더 온 흔한 중복이다. */
    private static final Set<String> ALREADY_COMPLETED = Set.of("READY", "NO_CHANGE", "APPLYING", "APPLIED");

    /**
     * 쓸 수 없는 결과. run 은 반영 때 {@code code} 로 실패한다. 그 이유를 여기서 warn 으로 남긴다 — message 는 진단용
     * 문장이라 run 에만 적고 로그에는 코드만 남긴다. 예외를 잡은 경우는 클래스 이름과 프레임만 남긴다.
     */
    private static ResultFetch.Unusable unusable(GraphRefreshCompletedEvent event, String code, String reason,
            String message, Throwable cause) {
        AppLogger.current().warn("graph refresh result unusable", LogField.projectId(event.projectId()),
                LogField.refreshRequestId(event.requestId()), LogField.errorType(code),
                LogField.resultCode("reason", reason, UNUSABLE_REASONS), LogField.safeStackTrace(cause));
        return new ResultFetch.Unusable(code, message);
    }

    /**
     * 결과를 기다리는 run 이 아니라서 건너뛴 줄. run 상태와 프로젝트가 맞는지를 함께 남긴다.
     *
     * <p>run 이 없거나(프로젝트 삭제) 이미 반영한 결과의 중복은 흔해서 info 다. 다른 프로젝트의 run 이거나, 이미 실패
     * (시간 초과 등)·취소한 run 에 늦게 온 결과는 예상 밖이라 warn 이다.
     */
    private static void logSkipped(String message, GraphRefreshCompletedEvent event,
            Optional<GraphRefreshRepository.RunRow> run) {
        boolean projectMismatch = run.isPresent() && !run.get().projectId().equals(event.projectId());
        String status = run.map(GraphRefreshRepository.RunRow::status).orElse(null);
        LogField[] fields = {LogField.refreshRequestId(event.requestId()), LogField.result("run_found", run.isPresent()),
                LogField.resultCode("run_status", status, RUN_STATUSES), LogField.result("project_mismatch", projectMismatch)};
        if (projectMismatch || (status != null && !ALREADY_COMPLETED.contains(status))) {
            AppLogger.current().warn(message, fields);
        } else {
            AppLogger.current().info(message, fields);
        }
    }

    /**
     * 최신화 완료 이벤트 하나를 반영한다. 결과 파일은 {@link #fetchResult} 가 미리 가져왔다.
     *
     * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
     * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
     * 없이 처리만 되는 길을 막는다.
     *
     * <p>run 을 잠근 뒤 아직 GENERATING 일 때만 바꾼다. 시간 제한으로 이미 실패한 run 에 늦게 온 결과, 같은 결과의 중복,
     * 지워진 프로젝트의 결과는 아무것도 하지 않는다.
     *
     * <ul>
     *   <li>실패 이벤트, 쓸 수 없는 결과, 결과의 FAILED → run 실패. 작업본·기준 버전·잠금을 치운다.
     *   <li>NO_CHANGE, 또는 쓸 수 있는 제안이 하나도 없음 → run 은 "변경 없음"으로 끝나고 같은 정리를 한다.
     *   <li>PROPOSED → 검토 작업본을 열고 READY. 잠금은 확정할 때까지 둔다.
     * </ul>
     * 읽은 결과 파일은 어느 경우든 원본 그대로 남긴다.
     *
     * @param event   끝난 최신화의 요청 id, 결과 위치 또는 실패 원인
     * @param fetched {@link #fetchResult} 가 가져온 것
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(GraphRefreshCompletedEvent event, ResultFetch fetched) {
        AppLogger.current().info("graph refresh complete", LogField.projectId(event.projectId()),
                LogField.refreshRequestId(event.requestId()), LogField.param("outcome", event.outcome()));
        var locked = repository.lockRun(event.requestId());
        if (locked.isEmpty() || !locked.get().projectId().equals(event.projectId())
                || !"GENERATING".equals(locked.get().status())) {
            // 결과를 기다리는 run 이 아니다(중복·늦은 이벤트 등). 아무것도 바꾸지 않는다
            logSkipped("graph refresh complete skipped", event, locked);
            return;
        }
        UUID runId = locked.get().id();
        repository.setPromptVersion(runId, event.promptVersion());
        if (fetched instanceof ResultFetch.Read read) {
            repository.storeResult(runId, read.json(), read.sha256(), read.bucket(), read.key());
        }

        if (event.outcome() == GraphRefreshCompletedEvent.Outcome.FAILED) {
            GraphRefreshCompletedEvent.ErrorDetail error = event.error();
            fail(runId, error == null || error.code() == null ? "GRAPH_RAG_FAILED" : error.code(),
                    error == null ? null : error.message());
            return;
        }
        switch (fetched) {
            case ResultFetch.Unusable unusable -> fail(runId, unusable.code(), unusable.message());
            // 성공 이벤트는 결과 위치 없이 오지 않는다(fetchResult 가 Unusable 로 바꾼다). 미리 본 run 상태와 지금이
            // 다를 수 없으므로 Skip 도 올 수 없지만, 오면 아무것도 바꾸지 않는다.
            case ResultFetch.NoResult none -> fail(runId, "RESULT_MISSING", "Succeeded without a result location");
            case ResultFetch.Skip skip -> {
            }
            case ResultFetch.Read read -> {
                RefreshResult result = read.result();
                switch (result.outcome()) {
                    case "FAILED" -> fail(runId,
                            result.error() == null || result.error().code() == null ? "GRAPH_RAG_FAILED"
                                    : result.error().code(),
                            result.error() == null ? null : result.error().message());
                    case "PROPOSED" -> {
                        if (builder.build(runId, event.projectId(), result)) {
                            repository.markReady(runId);
                            AppLogger.current().info("graph refresh run ready", LogField.refreshRequestId(runId));
                        } else {
                            end(runId, "NO_CHANGE");
                        }
                    }
                    default -> end(runId, "NO_CHANGE");
                }
            }
        }
    }

    /** run 을 실패로 끝내고 정리한다. */
    private void fail(UUID runId, String code, String message) {
        // 실패 코드만 남긴다. graph-rag 가 준 메시지에는 문서 내용이 섞일 수 있어 남기지 않는다
        AppLogger.current().warn("graph refresh run failed", LogField.refreshRequestId(runId), LogField.errorType(code));
        cleanup.release(runId);
        repository.markEnded(runId, "FAILED", code, message);
    }

    /** run 을 실패가 아닌 상태로 끝내고 정리한다. */
    private void end(UUID runId, String status) {
        AppLogger.current().info("graph refresh run ended", LogField.refreshRequestId(runId),
                LogField.resultCode("status", status, Set.of("NO_CHANGE")));
        cleanup.release(runId);
        repository.markEnded(runId, status, null, null);
    }
}
