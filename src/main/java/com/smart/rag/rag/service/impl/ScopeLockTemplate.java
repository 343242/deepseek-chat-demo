package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityCooccurrenceMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * scope 级 advisory 锁事务统一模板（V30 §3.2.1 防线一）。
 * <p>
 * 封装固定前置序列：运行时断言（自动提交拒绝）→ {@code setLockTimeout} → {@code lockScope} → body。
 * 三张实体索引表（rag_entity / rag_chunk_entity / rag_entity_cooccurrence）的多行临界区
 * <b>必须</b>经本模板执行——advisory 持锁者互斥，锁内行锁无竞争（零死锁构造，§3.2.1）。
 * <p>
 * 使用形态（{@code LockRetryExecutor} 在本模板外层包整事务重试）：
 * <pre>{@code
 * lockRetryExecutor.execute(() ->
 *     transactionTemplate.executeWithoutResult(status ->
 *         scopeLockTemplate.withinScopeLock(userId, teamId, () -> {
 *             // 行锁获取者语句（实体 upsert / 链接插入 / 边增量 …）
 *         })));
 * }</pre>
 * <p>
 * 契约固化（§3.1，代码级防呆而非依赖调用方自觉）：必须在<b>事务内</b>调用——
 * {@code pg_advisory_xact_lock} 在自动提交下立即释放、静默失去串行化，故自动提交下抛
 * {@link IllegalStateException} 拒绝执行。lockScope 之前只允许不取任何锁的语句
 * （setLockTimeout 的 set_config），保证 R1（lockScope 是第一个取锁的数据库操作）。
 */
@Component
public class ScopeLockTemplate {

    private final EntityCooccurrenceMapper cooccurrenceMapper;
    private final RagEntityProperties properties;

    public ScopeLockTemplate(EntityCooccurrenceMapper cooccurrenceMapper,
                             RagEntityProperties properties) {
        this.cooccurrenceMapper = cooccurrenceMapper;
        this.properties = properties;
    }

    /**
     * 在 scope advisory 锁内执行 body（事务由调用方开启，本模板只做前置断言 + 取锁）。
     *
     * @param userId 用户作用域
     * @param teamId 团队作用域（null = 个人文档，key 内归一为 -1）
     * @param body   持锁临界区（三表行锁获取者语句）
     */
    public void withinScopeLock(Long userId, @Nullable Long teamId, Runnable body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ScopeLockTemplate 必须在事务内调用：pg_advisory_xact_lock 在自动提交下立即释放、"
                            + "静默失去串行化（V30 §3.1 契约，scope userId=" + userId + ", teamId=" + teamId + "）");
        }
        // 顺序不可换：set_config 不取任何锁（不破坏 R1）→ advisory 锁 → 行锁获取者 body
        cooccurrenceMapper.setLockTimeout(properties.lockTimeoutMillis());
        cooccurrenceMapper.lockScope(userId, teamId);
        body.run();
    }
}
