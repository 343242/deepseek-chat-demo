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

    /** Mark catalog as synced for the currently observed desired state. */
    int markCatalogSynced(@Param("id") Long id, @Param("capturedHash") String capturedHash);

    /** Clear observation + schedule reconcile (reconnect / enable). */
    int clearObservation(@Param("serverId") String serverId, @Param("version") Long version);

    /** Clear catalog_synced only + schedule reconcile (refresh-tools). */
    int clearCatalogSynced(@Param("serverId") String serverId, @Param("version") Long version);

    /** Update URL + recompute desired hash + clear observation (URL change). */
    int updateDesiredUrl(@Param("serverId") String serverId,
                         @Param("url") String url,
                         @Param("desiredStateHash") String desiredStateHash,
                         @Param("version") Long version);

    /** Update bearer token + recompute desired hash + clear observation (token rotation). */
    int updateDesiredToken(@Param("serverId") String serverId,
                           @Param("bearerTokenEncrypted") String bearerTokenEncrypted,
                           @Param("desiredStateHash") String desiredStateHash,
                           @Param("version") Long version);

    /** Enable + clear observation + schedule reconcile. */
    int enableAndScheduleReconcile(@Param("serverId") String serverId, @Param("version") Long version);

    /** Disable + clear observation + clear reconcile schedule. */
    int disableAndClearReconcile(@Param("serverId") String serverId, @Param("version") Long version);
}
