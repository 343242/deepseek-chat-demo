package com.smart.rag.mcp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.rag.mcp.admin.entity.McpServerConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface McpServerConfigMapper extends BaseMapper<McpServerConfig> {

    McpServerConfig selectByServerId(@Param("serverId") String serverId);

    McpServerConfig selectByCreateRequestKey(@Param("createRequestKey") String createRequestKey);

    List<McpServerConfig> selectAutoConnectEnabled();

    /** Recovery scan: enabled rows with due time */
    List<McpServerConfig> selectDueForReconcile(@Param("limit") int limit);

    /** Conditional observed success: only commits if desired hash still matches */
    int updateObservedSuccess(@Param("id") Long id,
                              @Param("capturedHash") String capturedHash,
                              @Param("observedStateHash") String observedStateHash,
                              @Param("remoteServerName") String remoteServerName);

    /** Conditional observed failure: persists safe error + retry scheduling */
    int updateObservedFailure(@Param("id") Long id,
                              @Param("capturedHash") String capturedHash,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage,
                              @Param("consecutiveFailures") int consecutiveFailures,
                              @Param("nextReconcileAt") OffsetDateTime nextReconcileAt);

    int updateBearerToken(@Param("serverId") String serverId,
                          @Param("bearerTokenEncrypted") String bearerTokenEncrypted,
                          @Param("version") Long version);

    int updateEnabled(@Param("serverId") String serverId, @Param("enabled") boolean enabled);
}
