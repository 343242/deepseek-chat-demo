package com.smart.rag.infrastructure.audit;

import com.smart.rag.infrastructure.audit.entity.AdminAuditLog;
import com.smart.rag.infrastructure.audit.mapper.AdminAuditLogMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步写入 admin_audit_log。
 * <p>
 * 单线程 + 有界队列 + {@link ThreadPoolExecutor.CallerRunsPolicy}：队列满时让业务线程同步执行写入，
 * <b>不丢数据</b>（v4 C3：替代 v3 的 {@code DiscardOldestPolicy}，合规审计不丢历史）。
 * 代价：审计写入慢时短暂阻塞业务线程几毫秒——可接受，因审计行 INSERT 通常 < 5ms。
 */
@Component
public class AdminAuditAsyncWriter {

    private static final int QUEUE_CAPACITY = 2000;

    private final AdminAuditLogMapper mapper;
    private final ExecutorService executor;

    public AdminAuditAsyncWriter(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
        this.executor = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "admin-audit-writer");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void writeAsync(AdminAuditLog logEntry) {
        executor.submit(() -> {
            try {
                mapper.insert(logEntry);
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass())
                        .error("admin audit log insert failed: action={} resource={}/{} operator={}/{}",
                                logEntry.getAction(), logEntry.getResourceType(), logEntry.getResourceId(),
                                logEntry.getOperatorId(), logEntry.getOperatorName(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
