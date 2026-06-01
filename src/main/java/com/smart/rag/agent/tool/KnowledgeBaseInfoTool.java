package com.smart.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.agent.dto.ToolResult;
import com.smart.rag.infrastructure.agent.workspace.ToolWorkspace;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识库信息 Tool -- 查询知识库元信息（文档数、向量数等）
 * <p>
 * 委托 VectorStoreMapper 统计查询，直接返回结果，不写 workspace。
 * 供 LLM 判断知识库规模以决定检索策略。
 */
@Component
public class KnowledgeBaseInfoTool implements RagTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInfoTool.class);

    private final VectorStoreMapper vectorStoreMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseInfoTool(VectorStoreMapper vectorStoreMapper, ObjectMapper objectMapper) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询知识库信息
     *
     * @param ignored  输入参数（忽略，从 workspace 获取隔离参数）
     * @param workspace 闭包捕获的 workspace 局部变量
     * @return JSON 格式的 ToolResult
     */
    public String execute(String ignored, ToolWorkspace workspace) {
        long start = System.currentTimeMillis();
        try {
            // 从 workspace 获取隔离参数
            String isolationField = workspace.getTeamId() != null ? "teamId" : "userId";
            String isolationValue = workspace.getTeamId() != null
                ? String.valueOf(workspace.getTeamId())
                : String.valueOf(workspace.getUserId());

            int docCount = vectorStoreMapper.countDocs(isolationField, isolationValue);

            String summary = "知识库信息：当前" + (workspace.getTeamId() != null ? "团队" : "用户")
                + "共有 " + docCount + " 个向量文档片段";

            long duration = System.currentTimeMillis() - start;
            log.debug("Knowledge base info: {} docs for {}={} in {}ms",
                docCount, isolationField, isolationValue, duration);

            return ToolResult.success("knowledgeBaseInfo",
                summary, null, duration).toJson(objectMapper);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Knowledge base info error", e);
            return ToolResult.failure("knowledgeBaseInfo",
                ToolErrorMessages.knowledgeBaseUnavailable(),
                "DB_ERROR", duration).toJson(objectMapper);
        }
    }
}
