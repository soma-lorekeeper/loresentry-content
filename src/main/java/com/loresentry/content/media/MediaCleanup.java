package com.loresentry.content.media;

import java.util.Collection;
import java.util.List;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogLayer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 행을 지운 뒤 S3 객체를 지운다.
 *
 * <p>커밋이 끝난 뒤에만 지운다. 먼저 지우고 트랜잭션이 롤백되면 행은 남았는데 이미지가 깨진다.
 * 커밋 뒤의 실패는 응답을 바꾸지 않는다 — 이미 지워진 데이터를 "실패"라고 답하면 클라이언트가
 * 재시도하고, 재시도는 지울 행을 찾지 못해 객체를 다시 지우지도 못한다. 남은 객체는 경고로 남긴다.
 */
@Component
@LogLayer("service")
public class MediaCleanup {


    private final MediaStorageService storage;

    public MediaCleanup(MediaStorageService storage) {
        this.storage = storage;
    }

    public void deleteAfterCommit(Collection<String> keys) {
        // 키에는 프로젝트 id 가 들어 있으므로 개수만 남긴다
        AppLogger.current().info("media cleanup schedule", LogField.param("keys", keys));
        if (keys.isEmpty()) {
            return;
        }
        List<String> pending = List.copyOf(keys);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteNow(pending);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteNow(pending);
            }
        });
    }

    /** 키에는 프로젝트 id 가 들어 있으므로 기록하지 않는다. 개수와 예외 종류만 남긴다. */
    void deleteNow(List<String> keys) {
        AppLogger.current().info("media cleanup delete", LogField.param("keys", keys));
        int failed = 0;
        String lastFailure = null;
        // 마지막 실패가 S3 응답(S3Exception)이었으면 그 응답 코드. 연결 실패처럼 응답이 없으면 null 이라 줄에서 빠진다
        Integer lastStatus = null;
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException failure) {
                failed++;
                lastFailure = failure.getClass().getName();
                lastStatus = failure instanceof S3Exception s3 ? s3.statusCode() : null;
            }
        }
        if (failed > 0) {
            // 지우지 못한 객체는 S3 에 남는다(고아 객체). 개수와 마지막 예외 종류만 남긴다
            AppLogger.current().warn("media objects left orphaned", LogField.result("failed", failed),
                    LogField.result("total", keys.size()), LogField.errorType(lastFailure),
                    LogField.result("s3_status_code", lastStatus));
        }
    }
}
