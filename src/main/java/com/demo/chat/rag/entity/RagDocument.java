package com.demo.chat.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_document")
public class RagDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 文件大小 (bytes) */
    private Long fileSize;

    /** MIME 类型 */
    private String mimeType;

    /** MinIO 存储 key */
    private String storageKey;

    /** MinIO bucket */
    private String bucket;

    /** 解析后分块数 */
    private Integer chunkCount;

    /** 处理状态: UPLOADED, PARSING, CHUNKING, COMPLETED, FAILED */
    @Enumerated(EnumType.STRING)
    private String status;

    /** 错误信息（失败时记录） */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
