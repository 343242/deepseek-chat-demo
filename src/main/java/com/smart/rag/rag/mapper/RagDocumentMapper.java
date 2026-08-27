package com.smart.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {

    /**
     * 聚合查询指定团队+用户的文档总大小（排除 REJECTED 状态）
     * <p>
     * 注意：排除状态通过参数传入，与 {@link EtlStatus#REJECTED} 的 @EnumValue 保持一致。
     */
    Long selectFileSizeSum(@Param("teamId") Long teamId, @Param("userId") Long userId,
                           @Param("excludedStatus") String excludedStatus);

    /**
     * 为文档分配 documentGroupId
     */
    int updateGroupId(@Param("id") Long id, @Param("groupId") String groupId);

    /**
     * CAS 防护：仅当 document_group_id IS NULL 时才写入，避免并发覆盖
     * @return 影响行数（0 = CAS 失败，其他线程已分配）
     */
    int updateGroupIdCas(@Param("id") Long id, @Param("groupId") String groupId);

    /**
     * 仅设置 superseded_by（不改变 status），用于 linkVersion 事务中标记替换关系。
     * 崩溃安全：superseded_by 写入 DB 后，即使内存 pendingSupersede 丢失，
     * recoverPendingSupersede 也能通过此字段找到未完成的替换。
     */
    int updateSupersededByOnly(@Param("oldDocId") Long oldDocId, @Param("newDocId") Long newDocId);

    /**
     * 设置文档的 groupId 和 version
     */
    int updateGroupIdAndVersion(@Param("id") Long id, @Param("groupId") String groupId, @Param("version") int version);

    /**
     * 图片提取消费前置校验（design §6.4 严重-2a）：文档存在且状态可处理
     * （SUPERSEDED 视同已删除——新版本=新 documentId，旧 id 原文件已被 supersede 清理，
     * existsById 会误放行 → 下载 404 烧预算误报 dead）。
     * @return 1=可处理；0=不存在/SUPERSEDED/已逻辑删除
     */
    int countProcessable(@Param("id") Long id);

    /**
     * 将旧文档标记为 SUPERSEDED
     */
    int updateSuperseded(@Param("oldDocId") Long oldDocId, @Param("newDocId") Long newDocId);

    /**
     * 查找需要补偿清理的旧文档（superseded_by IS NOT NULL 但 status != SUPERSEDED）
     */
    List<RagDocument> findStaleSupersededTargets();

    // ==================== V30 实体索引增量维护支持 ====================

    /**
     * 文档作用域（user_id, teamId 可为 null）。
     */
    record DocumentScope(Long userId, Long teamId) {}

    /**
     * 待重链接文档行（自带 user_id/team_id，恰为发布 EtlVectorizedEvent 所需参数）。
     */
    record PendingDoc(Long documentId, Long userId, Long teamId) {}

    /**
     * 读文档作用域（§5 Step 0）：手写 SQL，【不过滤】@TableLogic 的 deleted 列——
     * 补偿性/乱序到达的清理仍须能读到 scope 并执行。文档行物理不存在时返回 null。
     */
    DocumentScope selectScopeById(@Param("documentId") Long documentId);

    /**
     * 待重链接文档探测（§6.2，全局查询、无 scope 参数——scope 枚举源是 rag_document 自身）：
     * 抽取从未完成（entity_extracted_at IS NULL）+ 在册 + COMPLETED + 6h 宽限期。
     *
     * @param limit 每日重抽上限；null 或 &lt;= 0 = 不限
     */
    List<PendingDoc> selectDocsPendingEntityExtraction(@Param("limit") Integer limit);

    /**
     * 写抽取完成标记（§6.2）：extractAndIndex 所有非异常退出路径调用；异常退出不标记。
     */
    int markEntityExtracted(@Param("documentId") Long documentId);
}
