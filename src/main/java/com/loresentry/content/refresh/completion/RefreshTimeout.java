package com.loresentry.content.refresh.completion;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.TraceContext;
import com.loresentry.content.logging.LogLayer;
import com.loresentry.content.logging.SkipEntryLog;
import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.refresh.RefreshCleanup;

/**
 * 결과가 오지 않는 최신화를 실패로 돌린다.
 *
 * <p>graph-rag 는 일부 실패(요청이 DLQ 로 가는 경우)에서 완료 이벤트를 아예 보내지 않는다. 그러면 run 이 GENERATING 에
 * 머물러 프로젝트는 새 최신화를 못 하고, 요청 문서는 계속 잠겨 있다. 그래서 {@code graph-refresh.timeout} 이 지난 run 은
 * 실패({@code TIMED_OUT})로 끝내고 작업본·기준 버전·잠금을 치운다. 그 뒤에 결과가 와도 GENERATING 이 아니므로 무시된다.
 *
 * <p>run 을 {@code skip locked} 로 잠그며 고르므로 pod 가 여럿이어도 같은 run 을 두 번 끝내지 않는다. 한 트랜잭션에서
 * {@value #BATCH} 개씩 처리한다.
 */
@Component
@LogLayer("scheduler")
class RefreshTimeout {


    /** 한 트랜잭션에서 끝내는 run 수. 많아야 이만큼의 문서 잠금·작업본을 한 번에 치운다. */
    static final int BATCH = 50;

    /** 로그의 작업 이름(loresentry.schedule.name). */
    static final String SCHEDULE = "graph-refresh-timeout";

    private final GraphRefreshRepository runs;

    private final RefreshCleanup cleanup;

    private final TransactionTemplate transaction;

    /** 결과를 기다리는 최대 시간. */
    private final Duration timeout;

    /** 스케줄러가 부를 때 실제로 확인할지. 테스트는 끄고 {@link #expireOverdue} 를 직접 부른다. */
    private final boolean enabled;

    RefreshTimeout(GraphRefreshRepository runs, RefreshCleanup cleanup, TransactionTemplate transaction,
            @Value("${graph-refresh.timeout:1h}") Duration timeout,
            @Value("${graph-refresh.timeout-check-enabled:true}") boolean enabled) {
        this.runs = runs;
        this.cleanup = cleanup;
        this.transaction = transaction;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    /**
     * 스케줄러가 정해진 간격으로 부른다. 끝낸 run 이 있을 때만 한 줄 남긴다(1분마다 도는 작업이라 빈 줄은 소음이다).
     *
     * <p>예외를 밖으로 내보내지 않는다. 스케줄러가 찍는 오류에는 예외 메시지와 SQL 이 들어가는데, 이 저장소는 그것을
     * 로그에 남기지 않는다. 다음 간격에 다시 확인한다.
     */
    @Scheduled(fixedDelayString = "${graph-refresh.timeout-check-interval:1m}")
    @SkipEntryLog("Polls every minute. Logs start and end lines only in rounds that have runs to expire")
    void run() {
        // 회차 하나의 시작점. 스케줄 observation 이 연 span 으로 로그 객체를 만들고 작업 이름을 붙여, 이 회차의 모든 줄(부른 repository 줄 포함)에 남게 한다
        try (var bound = AppLogger.bind(TraceContext.newLogger().with(LogField.scheduleName(SCHEDULE)))) {
            if (!enabled) {
                return;
            }
            long startedAt = System.nanoTime();
            try {
                int expired = expireOverdue();
                if (expired > 0) {
                    // 종료 줄. 이 문구로 로그를 찾는다. 로그에는 개수와 예외의 종류만 적는다.
                    AppLogger.current().info("graph refresh timeout: expired=" + expired, LogField.result("expired", expired),
                            LogField.duration(Duration.ofNanos(System.nanoTime() - startedAt)));
                }
            } catch (RuntimeException failure) {
                AppLogger.current().error("graph refresh timeout failed: reason=" + failure.getClass().getName(),
                        LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure));
            }
        }
    }

    /** 시간 제한이 지난 GENERATING run 을 모두 실패로 끝낸다. 끝낸 개수를 돌려준다. */
    @SkipEntryLog("Called by the 1-minute poll (run). Start and end lines are logged only in rounds that have runs to expire")
    int expireOverdue() {
        int expired = 0;
        // 시작 줄을 남겼는가. 끝낼 run 이 처음 나온 배치에서 한 번만 남긴다(1분 폴링이라 빈 회차는 줄을 남기지 않는다)
        AtomicBoolean started = new AtomicBoolean();
        while (true) {
            Integer done = transaction.execute(status -> {
                List<UUID> overdue = runs.lockOverdue(timeout, BATCH);
                if (!overdue.isEmpty() && started.compareAndSet(false, true)) {
                    // 시작 줄. 시작할 때 아는 값(첫 배치에서 고른 run 수와 시간 제한)을 함께 남긴다
                    AppLogger.current().info("graph refresh timeout start", LogField.param("overdue_runs", overdue.size()),
                            LogField.param("timeout_minutes", timeout.toMinutes()));
                }
                for (UUID runId : overdue) {
                    cleanup.release(runId);
                    runs.markEnded(runId, "FAILED", "TIMED_OUT", "No result arrived within the refresh timeout");
                    // 완료 이벤트로 실패한 run 과 같은 문구라, run 하나의 실패를 이 문구 하나로 찾는다
                    AppLogger.current().warn("graph refresh run failed", LogField.refreshRequestId(runId),
                            LogField.errorType("TIMED_OUT"));
                }
                return overdue.size();
            });
            expired += done == null ? 0 : done;
            if (done == null || done < BATCH) {
                return expired;
            }
        }
    }
}
