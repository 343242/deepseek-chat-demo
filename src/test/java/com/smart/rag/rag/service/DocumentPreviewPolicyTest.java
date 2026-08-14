package com.smart.rag.rag.service;

import com.smart.rag.rag.config.DocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预览输出策略测试（设计 §4）：按规范 MIME 与保守大小产出
 * PassThrough / Transform / Deny，previewable 与端点共用同一决策。
 */
@DisplayName("DocumentPreviewPolicy — 预览策略单点")
class DocumentPreviewPolicyTest {

    private static final long FIVE_MB = 5L * 1024 * 1024;

    private DocumentPreviewPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DocumentPreviewPolicy(new DocumentProperties());
    }

    @Test
    @DisplayName("PDF → PassThrough(application/pdf)，不受大小限制")
    void pdf_passThrough() {
        assertThat(policy.strategyFor("application/pdf", Long.MAX_VALUE))
                .isEqualTo(new PreviewStrategy.PassThrough("application/pdf"));
        assertThat(policy.previewable("application/pdf", 100L * 1024 * 1024)).isTrue();
    }

    @Test
    @DisplayName("TXT/MD/HTML → Transform，上限内 previewable=true")
    void text_transform() {
        assertThat(policy.strategyFor("text/plain", 100))
                .isEqualTo(new PreviewStrategy.Transform("text/plain; charset=UTF-8",
                        TransformKind.DETECT_CHARSET, FIVE_MB));
        assertThat(policy.strategyFor("text/markdown", 100))
                .isEqualTo(new PreviewStrategy.Transform("text/html; charset=UTF-8",
                        TransformKind.RENDER_MARKDOWN, FIVE_MB));
        assertThat(policy.strategyFor("text/html", 100))
                .isEqualTo(new PreviewStrategy.Transform("text/html; charset=UTF-8",
                        TransformKind.SANITIZE_HTML, FIVE_MB));

        assertThat(policy.previewable("text/plain", 100)).isTrue();
        assertThat(policy.previewable("text/markdown", FIVE_MB)).isTrue();
    }

    @Test
    @DisplayName("文本超出上限 → Deny(PREVIEW_TOO_LARGE)，previewable=false")
    void textTooLarge_denied() {
        assertThat(policy.strategyFor("text/plain", FIVE_MB + 1))
                .isEqualTo(new PreviewStrategy.Deny(DenyReason.PREVIEW_TOO_LARGE));
        assertThat(policy.previewable("text/html", FIVE_MB + 1)).isFalse();
    }

    @Test
    @DisplayName("OOXML → Deny(UNSUPPORTED_TYPE)，previewable=false")
    void ooxml_denied() {
        assertThat(policy.strategyFor(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 10))
                .isEqualTo(new PreviewStrategy.Deny(DenyReason.UNSUPPORTED_TYPE));
        assertThat(policy.previewable(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", 10)).isFalse();
        assertThat(policy.previewable("application/zip", 10)).isFalse();
        assertThat(policy.previewable(null, 10)).isFalse();
    }
}
