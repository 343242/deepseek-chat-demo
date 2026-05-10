package com.demo.chat.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDTO {
    private Long id;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private Integer chunkCount;
    private String status;
    private String errorMessage;
    private LocalDateTime createTime;
}
