package com.smart.rag.mcp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface McpServerConfigMapper extends BaseMapper<McpServerConfig> {

    McpServerConfig selectByServerId(@Param("serverId") String serverId);

    List<McpServerConfig> selectAutoConnectEnabled();

    int updateInitError(@Param("serverId") String serverId, @Param("initError") String initError);

    int updateBearerToken(@Param("serverId") String serverId,
                          @Param("bearerTokenEncrypted") String bearerTokenEncrypted,
                          @Param("version") Long version);

    int updateEnabled(@Param("serverId") String serverId, @Param("enabled") boolean enabled);

    int markConnected(@Param("serverId") String serverId);
}
