package com.smart.rag.rag.controller;

import com.smart.rag.infrastructure.response.GlobalResponse;
import com.smart.rag.rag.dto.ChunkDTO;
import com.smart.rag.rag.service.DocumentApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档片段（chunk）REST 控制器 —— 按 chunk UUID 直接寻址。
 * <p>
 * chunkId 全局唯一（vector_store.id UUID），用于引用卡片点击查看片段内容。
 * 归属校验复用 {@link DocumentApplicationService#getById} 的文档权限逻辑
 * （个人文档 owner / 团队文档成员 + R1-M1 可见性分层）。
 * <p>
 * 按文档列出片段的端点见 {@link DocumentController#chunks}（{@code GET /api/documents/{id}/chunks}）。
 */
@RestController
@RequestMapping("/api/chunks")
@PreAuthorize("isAuthenticated()")
public class ChunkController {

    private final DocumentApplicationService documentService;

    public ChunkController(DocumentApplicationService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/{chunkId}")
    public GlobalResponse<ChunkDTO> get(@PathVariable String chunkId) {
        return GlobalResponse.ok(documentService.getChunk(chunkId));
    }
}
