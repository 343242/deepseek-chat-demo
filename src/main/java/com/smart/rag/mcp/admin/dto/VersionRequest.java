package com.smart.rag.mcp.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Simple version body for enable/disable/delete endpoints (PRD §R4).
 */
public record VersionRequest(Long version) {
    @JsonCreator
    public VersionRequest(@JsonProperty("version") Long version) {
        this.version = version;
    }
}
