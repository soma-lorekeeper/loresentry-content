package com.loresentry.content.manuscript;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ProjectKeys {

    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private static final int MAX_ENTRIES = 1000;

    private final JdbcClient jdbcClient;

    private final KeyWrapper wrapper;

    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(byte[] key, long expiresAt) {
    }

    ProjectKeys(JdbcClient jdbcClient, KeyWrapper wrapper) {
        this.jdbcClient = jdbcClient;
        this.wrapper = wrapper;
    }

    public byte[] key(UUID projectId) {
        Cached cached = cache.get(projectId);
        if (cached != null && System.nanoTime() - cached.expiresAt() < 0) {
            return cached.key();
        }
        Map<UUID, byte[]> pending = pending();
        if (pending != null && pending.containsKey(projectId)) {
            return pending.get(projectId);
        }
        // 캐시에 없어 키를 읽어 푼다(없으면 만든다). 운영에서는 KMS 를 부르는 길이라, 이 줄 뒤에 kms 줄이 이어진다. 키는 남기지 않는다
        AppLogger.current().info("project key load", LogField.projectId(projectId));
        Optional<byte[]> wrapped = wrappedKey(projectId);
        if (wrapped.isPresent()) {
            byte[] key = wrapper.unwrap(projectId, wrapped.get());
            remember(projectId, key);
            return key;
        }
        return create(projectId, pending);
    }

    public Optional<byte[]> wrappedKey(UUID projectId) {
        return jdbcClient.sql("select wrapped_key from project_keys where project_id = :id")
                .param("id", projectId)
                .query((rows, index) -> rows.getBytes("wrapped_key"))
                .optional();
    }

    public byte[] requireWrappedKey(UUID projectId) {
        key(projectId);
        return wrappedKey(projectId).orElseThrow();
    }

    private byte[] create(UUID projectId, Map<UUID, byte[]> pending) {
        KeyWrapper.Generated generated = wrapper.generate(projectId);
        int inserted = jdbcClient
                .sql("insert into project_keys (project_id, wrapped_key) values (:id, :key) "
                        + "on conflict (project_id) do nothing")
                .param("id", projectId)
                .param("key", generated.wrapped())
                .update();
        if (inserted == 0) {
            byte[] key = wrapper.unwrap(projectId, wrappedKey(projectId).orElseThrow());
            remember(projectId, key);
            return key;
        }
        if (pending != null) {
            pending.put(projectId, generated.plaintext());
        } else {
            remember(projectId, generated.plaintext());
        }
        return generated.plaintext();
    }

    private void remember(UUID projectId, byte[] key) {
        if (cache.size() >= MAX_ENTRIES) {
            // 한도에 닿으면 통째로 비운다. 비운 뒤에는 프로젝트마다 키를 다시 풀어야(운영은 KMS 호출) 하므로, 자주 보이면 한도를 늘릴 때다
            int entries = cache.size();
            cache.clear();
            AppLogger.current().info("project key cache cleared", LogField.result("entries", entries));
        }
        cache.put(projectId, new Cached(key, System.nanoTime() + TTL_NANOS));
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, byte[]> pending() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Map<UUID, byte[]> pending = (Map<UUID, byte[]>) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            Map<UUID, byte[]> created = new HashMap<>();
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    created.forEach(ProjectKeys.this::remember);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(ProjectKeys.this);
                }
            });
            pending = created;
        }
        return pending;
    }
}
