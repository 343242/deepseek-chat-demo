package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.DocumentImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * document_image 清单 Mapper（design §6.3/§6.4）。
 * <p>
 * 消费侧行状态迁移一律条件化（{@code WHERE status='PENDING'}）——0 行 = 代际失效
 * （前台 DELETE+INSERT 重建清单），据此中止本批（高-2 条件更新协议）。
 */
@Mapper
public interface DocumentImageMapper extends BaseMapper<DocumentImage> {

    /** 幂等重建第一步：删除该文档全部旧行（与 INSERT 同短事务） */
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /** 批量插入新 manifest（与 DELETE 同短事务） */
    int insertBatch(@Param("rows") List<DocumentImage> rows);

    /** 消费侧待处理行，ORDER BY page_number, seq（保证换页清理按批生效，中-6） */
    List<DocumentImage> findPending(@Param("documentId") Long documentId);

    /**
     * 条件迁移 UPLOADED（高-2）：
     * @return 1=迁移成功；0=代际失效（行已被重建删除/改态）
     */
    int markUploadedConditionally(@Param("id") Long id, @Param("fileSize") Long fileSize);

    /** 条件迁移 SKIPPED（结构性终态）；0 行 = 代际失效 */
    int markSkippedConditionally(@Param("id") Long id, @Param("failReason") String failReason);

    /**
     * 瞬时失败回置 PENDING——仅限非终态行（条件化：终态 UPLOADED/SKIPPED/FAILED
     * 不可回退，无条件按 id 回置会把已终态行拉回重传，破坏状态机，中-1）。
     */
    int resetPendingByIds(@Param("ids") List<Long> ids, @Param("failReason") String failReason);

    long countPending(@Param("documentId") Long documentId);

    /** P3 超龄扫描：超龄 PENDING 终态化 FAILED */
    int failStalePending(@Param("olderThan") java.time.OffsetDateTime olderThan,
                         @Param("limit") int limit);

    /** P3 对账：文档不存在/SUPERSEDED 但行残留 → 删行 */
    int deleteOrphanRows(@Param("docIds") List<Long> docIds);

    /** P2 积压指标：按状态计数 */
    List<StatusCount> countByStatus();

    /** P2 积压指标：最老 PENDING 行年龄起点 */
    java.time.OffsetDateTime oldestPendingCreatedAt();

    record StatusCount(String status, Long cnt) {}
}
