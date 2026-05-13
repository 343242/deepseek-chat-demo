package com.demo.chat.team.dto;

import jakarta.validation.constraints.Size;

public record TeamUpdateRequest(
    @Size(max = 128, message = "团队名称不超过128字符")
    String teamName,

    @Size(max = 512, message = "团队描述不超过512字符")
    String teamDesc
) {}
