package com.demo.chat.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {

    /**
     * 聚合查询指定团队+用户的文档总大小（排除 REJECTED 状态）
     * <p>
     * 注意：排除状态通过参数传入，与 {@link EtlStatus#REJECTED} 的 @EnumValue 保持一致。
     */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM rag_document " +
            "WHERE team_id = #{teamId} AND user_id = #{userId} AND status != #{excludedStatus}")
    Long selectFileSizeSum(@Param("teamId") Long teamId, @Param("userId") Long userId,
                           @Param("excludedStatus") String excludedStatus);

    /**
     * 为文档分配 documentGroupId
     */
    @Update("UPDATE rag_document SET document_group_id = #{groupId} WHERE id = #{id}")
    int updateGroupId(@Param("id") Long id, @Param("groupId") String groupId);

    /**
     * CAS 防护：仅当 document_group_id IS NULL 时才写入，避免并发覆盖
     * @return 影响行数（0 = CAS 失败，其他线程已分配）
     */
    @Update("UPDATE rag_document SET document_group_id = #{groupId} WHERE id = #{id} AND document_group_id IS NULL")
    int updateGroupIdCas(@Param("id") Long id, @Param("groupId") String groupId);

    /**
     * 仅设置 superseded_by（不改变 status），用于 linkVersion 事务中标记替换关系。
     * 崩溃安全：superseded_by 写入 DB 后，即使内存 pendingSupersede 丢失，
     * recoverPendingSupersede 也能通过此字段找到未完成的替换。
     */
    @Update("UPDATE rag_document SET superseded_by = #{newDocId} WHERE id = #{oldDocId} AND superseded_by IS NULL")
    int updateSupersededByOnly(@Param("oldDocId") Long oldDocId, @Param("newDocId") Long newDocId);

    /**
     * 设置文档的 groupId 和 version
     */
    @Update("UPDATE rag_document SET document_group_id = #{groupId}, version = #{version} WHERE id = #{id}")
    int updateGroupIdAndVersion(@Param("id") Long id, @Param("groupId") String groupId, @Param("version") int version);

    /**
     * 将旧文档标记为 SUPERSEDED
     */
    @Update("UPDATE rag_document SET status = 'SUPERSEDED', superseded_by = #{newDocId} WHERE id = #{oldDocId} AND superseded_by IS NULL")
    int updateSuperseded(@Param("oldDocId") Long oldDocId, @Param("newDocId") Long newDocId);

    /**
     * 查找需要补偿清理的旧文档（superseded_by IS NOT NULL 但 status != SUPERSEDED）
     */
    @Select("SELECT * FROM rag_document WHERE superseded_by IS NOT NULL AND status != 'SUPERSEDED' AND deleted = 0")
    List<RagDocument> findStaleSupersededTargets();
}
