package com.loresentry.content.refresh;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A lost/DLQ request must not keep the one-in-progress constraint forever. */
@Component
@ConditionalOnProperty(name = "graph-refresh.enabled", havingValue = "true")
public class GraphRefreshTimeouts {
    private final JdbcClient jdbc;

    public GraphRefreshTimeouts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    @Transactional
    public void expire() {
        jdbc.sql(
                        """
update refresh_runs set status='FAILED',error_message='REFRESH_TIMEOUT',completed_at=now()
where status in ('CAPTURING_BASE','GENERATING') and started_at < now()-interval '1 hour'
""")
                .update();
    }
}
