package com.smart.rag.team.dto;

public record TeamSearchResultVO(
    Long id,
    String teamName,
    String teamDesc,
    int memberCount,
    String creatorName
) {}
