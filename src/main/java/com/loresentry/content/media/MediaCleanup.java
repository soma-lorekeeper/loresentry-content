package com.loresentry.content.media;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class MediaCleanup {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanup.class);

    private final MediaStorageService storage;

    public MediaCleanup(MediaStorageService storage) {
        this.storage = storage;
    }

    public void deleteAfterCommit(Collection<String> keys) {
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
        int failed = 0;
        String lastFailure = null;
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException failure) {
                failed++;
                lastFailure = failure.getClass().getName();
            }
        }
        if (failed > 0) {
            log.warn("Could not delete {} of {} media objects ({}); they are left orphaned",
                    failed, keys.size(), lastFailure);
        }
    }
}
