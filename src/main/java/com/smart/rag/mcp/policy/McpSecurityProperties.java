package com.smart.rag.mcp.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 安全策略配置（{@code mcp.security}）—— Phase 2 语义层的可调参数。
 * <p>
 * 默认值保守（敏感参数空=不筛查、封顶宽松）；admin 按需收紧。与 {@link McpToolPolicy}（per-tool 规则）
 * 分离：本类只承载跨工具的全局阈值/模式。
 */
@Component
@ConfigurationProperties(prefix = "mcp.security")
public class McpSecurityProperties {

    /** 敏感参数 regex 列表（扫 {@code McpArgs} 值）；默认空 = 不筛查。命中即 DENY（不发包远端）。 */
    private List<String> sensitiveArgPatterns = List.of();

    /** 默认（risk=low 或缺省）输出字符上限。 */
    private int defaultOutputCapChars = 4000;

    /** risk=high 工具的输出字符上限（应 < {@link #defaultOutputCapChars}）。 */
    private int highRiskOutputCapChars = 2000;

    /** 工具描述字符上限（防 prompt-bombing）。 */
    private int descriptionCapChars = 500;

    public List<String> getSensitiveArgPatterns() {
        return sensitiveArgPatterns;
    }

    public void setSensitiveArgPatterns(List<String> sensitiveArgPatterns) {
        this.sensitiveArgPatterns = sensitiveArgPatterns == null ? List.of() : sensitiveArgPatterns;
    }

    public int getDefaultOutputCapChars() {
        return defaultOutputCapChars;
    }

    public void setDefaultOutputCapChars(int defaultOutputCapChars) {
        this.defaultOutputCapChars = defaultOutputCapChars;
    }

    public int getHighRiskOutputCapChars() {
        return highRiskOutputCapChars;
    }

    public void setHighRiskOutputCapChars(int highRiskOutputCapChars) {
        this.highRiskOutputCapChars = highRiskOutputCapChars;
    }

    public int getDescriptionCapChars() {
        return descriptionCapChars;
    }

    public void setDescriptionCapChars(int descriptionCapChars) {
        this.descriptionCapChars = descriptionCapChars;
    }
}
