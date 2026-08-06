package com.smart.rag.infrastructure.messaging;

/**
 * 共享退避表（design §4.4，评审"通用性"P1）——单一配置点，多处复用：
 * {@link com.smart.rag.infrastructure.messaging.redis.RetrySweeper}（消费端 16 级重试）
 * 与 child 2 OutboxRelay（publisher 侧延迟重试）共用 {@code app.messaging.backoff-ms}，
 * 消除退避表多份独立实现。
 * <p>
 * 默认 16 级：{@code [1s,5s,10s,30s,1m,...,30m]}（消费端重试窗口总和 ~106min）。
 * {@code next(attempt)} 以 1 起始计数，超出档位数封顶最后一档。
 */
public class BackoffSchedule {

    /** 默认 16 级退避表（ms）——设计 §4.4，与 child 2 共享同一配置段。 */
    public static final long[] DEFAULT_BACKOFF_MS = {
        1000, 5000, 10000, 30000, 60000, 120000, 180000, 240000,
        300000, 360000, 420000, 480000, 540000, 600000, 1200000, 1800000
    };

    private final long[] backoffMs;

    public BackoffSchedule(long[] backoffMs) {
        if (backoffMs == null || backoffMs.length == 0) {
            this.backoffMs = DEFAULT_BACKOFF_MS.clone();
        } else {
            this.backoffMs = backoffMs.clone();
        }
    }

    /** 退避表档位数。 */
    public int size() {
        return backoffMs.length;
    }

    /**
     * 第 {@code attempt} 次重试的退避时长（ms）。
     *
     * @param attempt 1 起始的尝试计数（失败 1 次 → 第 1 档）
     * @return 退避毫秒数；超出档位封顶最后一档
     */
    public long next(int attempt) {
        int index = Math.max(0, Math.min(attempt - 1, backoffMs.length - 1));
        return backoffMs[index];
    }
}
