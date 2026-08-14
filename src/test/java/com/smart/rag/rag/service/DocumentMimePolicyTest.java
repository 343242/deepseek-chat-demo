package com.smart.rag.rag.service;

import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规范 MIME 单点策略测试（设计 §3.2）：别名归一化、启动期配置校验、
 * 「内容探测 × 扩展名」一致性解析。
 */
@DisplayName("DocumentMimePolicy — 规范 MIME 唯一来源")
class DocumentMimePolicyTest {

    private final DocumentMimePolicy policy =
            new DocumentMimePolicy(new DocumentProperties());

    @Test
    @DisplayName("text/x-markdown 别名归一化为 text/markdown 并计入允许集合")
    void aliasNormalized() {
        assertThat(policy.normalizeAlias("text/x-markdown")).isEqualTo("text/markdown");
        assertThat(policy.normalizeAlias("text/markdown")).isEqualTo("text/markdown");
        assertThat(policy.isAllowed("text/x-markdown")).isTrue();
        assertThat(policy.isAllowed("text/markdown")).isTrue();
        assertThat(policy.isAllowed("application/octet-stream")).isFalse();
        assertThat(policy.isAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("配置含未知规范值 → 启动失败")
    void unknownConfigValue_failsStartup() {
        DocumentProperties props = new DocumentProperties();
        props.setAllowedMimeTypes("application/pdf,application/x-fictional");
        assertThatThrownBy(() -> new DocumentMimePolicy(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application/x-fictional");
    }

    @Test
    @DisplayName("配置空集合 → 启动失败")
    void emptyConfig_failsStartup() {
        DocumentProperties props = new DocumentProperties();
        props.setAllowedMimeTypes(" , ");
        assertThatThrownBy(() -> new DocumentMimePolicy(props))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("配置容忍空格并归一化别名")
    void spacedConfig_normalized() {
        DocumentProperties props = new DocumentProperties();
        props.setAllowedMimeTypes("application/pdf, text/x-markdown");
        Set<String> allowed = new DocumentMimePolicy(props).allowedCanonicalMimes();
        assertThat(allowed).containsExactlyInAnyOrder("application/pdf", "text/markdown");
    }

    @Test
    @DisplayName("canonicalForProbe：内容类别 × 扩展名一致性")
    void canonicalForProbe_matrix() {
        assertThat(policy.canonicalForProbe("application/pdf", "a.pdf")).isEqualTo("application/pdf");
        assertThat(policy.canonicalForProbe("text/plain", "a.txt")).isEqualTo("text/plain");
        assertThat(policy.canonicalForProbe("text/plain", "a.md")).isEqualTo("text/markdown");
        assertThat(policy.canonicalForProbe("text/markdown", "a.markdown")).isEqualTo("text/markdown");
        assertThat(policy.canonicalForProbe("text/html", "a.htm")).isEqualTo("text/html");
        assertThat(policy.canonicalForProbe(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // 不一致：全部拒绝
        assertThat(policy.canonicalForProbe("application/pdf", "a.docx")).isNull();
        assertThat(policy.canonicalForProbe("text/plain", "a.pdf")).isNull();
        assertThat(policy.canonicalForProbe("application/zip", "a.docx")).isNull();
        assertThat(policy.canonicalForProbe("application/octet-stream", "a.pdf")).isNull();
        assertThat(policy.canonicalForProbe("text/plain", "a.docx")).isNull();
        // docx 内容 + pptx 扩展名：探测子类型与扩展名指向不一致 → 拒绝
        assertThat(policy.canonicalForProbe(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.pptx"))
                .isNull();
        // 缺失输入
        assertThat(policy.canonicalForProbe(null, "a.pdf")).isNull();
        assertThat(policy.canonicalForProbe("text/plain", null)).isNull();
        assertThat(policy.canonicalForProbe("text/plain", "noext")).isNull();
        // 带参数的探测结果以分号前为准
        assertThat(policy.canonicalForProbe("text/plain; charset=UTF-8", "a.txt")).isEqualTo("text/plain");
    }
}
