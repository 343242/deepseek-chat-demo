package com.smart.rag.chat.service;

import com.smart.rag.rag.retrieval.RetrievedDocument;
import com.smart.rag.chat.dto.Reference;
import com.smart.rag.infrastructure.trace.TracedStep;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat 检索引用收集器 — 把检索 {@link Document} 编号 + 拼 {@code <<REF>>} 块 + 产 references。
 * <p>
 * 编号在请求内稳定（[n] 从 1 起，逐条递增）。refBlock 由 {@code RagContextAdvisor} 以
 * {@code SystemMessage} 注入对话历史之后、当前问题之前（动态尾，见 design §0.1）。
 * <p>
 * 文件名降级由 {@link RetrievedDocument#from} 保证（fileName 缺失 → documentId / "未知"），此处不再判空。
 */
@Component
public class ChatReferenceCollector {

    /** 单文档内容在 <<REF>> 块中的截断长度（控制注入 token） */
    private static final int CONTENT_TRUNCATE = 800;

    /**
     * 收集检索文档：编号 + 拼 refBlock + 产 references。
     *
     * @param docs 检索文档（可能为 null/空）
     * @return refBlock（null 表示无检索）+ references（null 表示无检索）
     */
    @TracedStep("CONTEXT_ASSEMBLY")
    public ChatRefResult collect(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return new ChatRefResult(null, null);
        }
        StringBuilder block = new StringBuilder();
        List<Reference> refs = new ArrayList<>(docs.size());
        int n = 1;
        for (Document d : docs) {
            RetrievedDocument rd = RetrievedDocument.from(d).withRefNumber(n);
            refs.add(new Reference(n, rd.chunkId(), rd.documentId(), rd.fileName(), rd.page()));
            block.append("<<REF>>[").append(n).append("] ")
                .append(rd.fileName()).append("(").append(rd.documentId());
            if (rd.page() != null) {
                block.append(", p.").append(rd.page());
            }
            block.append(")\n").append(truncate(rd.content())).append("\n<<END>>\n");
            n++;
        }
        String refBlock = "## 检索参考信息（引用时用「来源#n：文件名」）\n" + block;
        return new ChatRefResult(refBlock, refs);
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > CONTENT_TRUNCATE
            ? content.substring(0, CONTENT_TRUNCATE) + "..." : content;
    }

    /** 收集结果：refBlock（注入动态尾）+ references（响应 DTO 用） */
    public record ChatRefResult(String refBlock, List<Reference> references) {}
}
