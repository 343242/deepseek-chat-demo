package com.smart.rag.mcp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface McpToolConfigMapper extends BaseMapper<McpToolConfig> {

    List<McpToolConfig> selectByServerId(@Param("serverId") String serverId);

    List<McpToolConfig> selectAllEnabled();

    int updateEnabledByServerId(@Param("serverId") String serverId, @Param("enabled") boolean enabled);

    int batchUpdateEnabled(@Param("ids") List<Long> ids, @Param("enabled") boolean enabled);

    int deleteByServerId(@Param("serverId") String serverId);

    /** DatabaseToolFilter 主查：按前缀全名查启用状态（三态：null=未入库） */
    McpToolConfig selectByPrefixedName(@Param("prefixedToolName") String prefixedToolName);
}
