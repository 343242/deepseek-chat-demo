package com.smart.rag.mcp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.mcp.admin.entity.McpToolConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface McpToolConfigMapper extends BaseMapper<McpToolConfig> {

    List<McpToolConfig> selectByServerId(@Param("serverId") String serverId);

    /** Visible tools for direct callback discovery/authorization */
    List<McpToolConfig> selectVisibleByServerId(@Param("serverId") String serverId);

    int updateEnabledByServerId(@Param("serverId") String serverId, @Param("enabled") boolean enabled);

    int batchUpdateEnabled(@Param("ids") List<Long> ids, @Param("enabled") boolean enabled);

    /** Mark tools not in the seen set as absent (complete-set reconciliation) */
    int markAbsentExcept(@Param("serverId") String serverId, @Param("seenNames") List<String> seenNames);

    int deleteByServerId(@Param("serverId") String serverId);

    /** Direct indexed authorization lookup */
    McpToolConfig selectByPrefixedName(@Param("prefixedToolName") String prefixedToolName);

    int batchUpsert(@Param("tools") List<McpToolConfig> tools);
}
