package com.loresentry.content.media;

import java.util.Collection;
import java.util.List;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogFlow;
import com.loresentry.content.logging.LogLayer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * 객체를 하나씩 지운다. 커밋 뒤 정리 작업 하나라 시작·종료 줄(media cleanup start·completed, 키 수와 지운·못 지운 수,
     * 걸린 시간)은 LogFlow 가 남긴다. 키에는 프로젝트 id 가 들어 있으므로 기록하지 않는다. 객체마다의 실패 줄(예외 종류, 응답 코드)은
     * S3 호출({@link MediaStorageService#delete})이 남긴다.
     */
    void deleteNow(List<String> keys) {
        LogFlow.task("media cleanup", task -> {
            // 지우지 못한 객체 수. 종료 줄의 결과 값이고, 하나라도 있으면 이 작업은 실패다
            int failed = 0;
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException failure) {
                    failed++;
                }
            }
            task.result(LogField.result("deleted", keys.size() - failed), LogField.result("failed", failed));
            if (failed > 0) {
                // 지우지 못한 객체는 S3 에 남는다(고아 객체). 종료 줄이 media cleanup failed(warn)가 된다
                task.fail("MEDIA_DELETE_FAILED");
            }
        }, LogField.param("keys", keys));
    }
}
