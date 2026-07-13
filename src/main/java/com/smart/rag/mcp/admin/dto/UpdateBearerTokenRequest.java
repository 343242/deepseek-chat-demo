package com.smart.rag.mcp.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Update bearer token request (PRD §R3).
 * Nullable token: null clears the stored token.
 */
public record UpdateBearerTokenRequest(
        @Size(max = 8192, message = "Bearer Token 不能超过 8192 个字符") String bearerToken
) {
    @JsonCreator
    public UpdateBearerTokenRequest(@JsonProperty("bearerToken") String bearerToken) {
        this.bearerToken = bearerToken;
    }
}
