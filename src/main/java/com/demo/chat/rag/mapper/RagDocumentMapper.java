package com.demo.chat.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.chat.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
