package com.smart.rag.evaluation.runner;

/**
 * 评测运行的单条进度事件（per-item 粒度）。
 * <p>
 * 每完成一个数据项（成功或失败）后由 {@code EvaluationExecutionService} 发出，
 * 通过 {@code EvaluationProgressSink} 推送到订阅的 SSE 客户端。
 *
 * @param runId        运行 ID
 * @param processed    已处理项数（含失败）
 * @param total        总项数
 * @param successCount 累计成功数
 * @param failCount    累计失败数
 * @param itemId       本次处理的 item ID
 * @param status       处理结果：{@code "success"} 或 {@code "failed"}
 * @param error        失败时的错误信息（成功时为 null）
 * @param elapsedMs    本次 item 处理耗时（毫秒）
 */
public record EvaluationProgressEvent(
        long runId,
        int processed,
        int total,
        int successCount,
        int failCount,
        long itemId,
        String status,
        String error,
        long elapsedMs
) {
    public static EvaluationProgressEvent success(long runId, int processed, int total,
                                                  int successCount, int failCount,
                                                  long itemId, long elapsedMs) {
        return new EvaluationProgressEvent(runId, processed, total, successCount, failCount,
                itemId, "success", null, elapsedMs);
    }

    public static EvaluationProgressEvent failed(long runId, int processed, int total,
                                                 int successCount, int failCount,
                                                 long itemId, String error, long elapsedMs) {
        return new EvaluationProgressEvent(runId, processed, total, successCount, failCount,
                itemId, "failed", error, elapsedMs);
    }
}
