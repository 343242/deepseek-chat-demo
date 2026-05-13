package com.demo.chat.team.dto;

public record TeamSearchResultVO(
    Long id,
    String teamName,
    String teamDesc,
    int memberCount,
    String creatorName
) {}
