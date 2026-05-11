package com.demo.chat.rag.embedding;

/**
 * DashScope Embedding 文本类型枚举。
 * <p>
 * 对应百炼 text-embedding-v4 的 text_type 参数。
 * 在检索场景中区分 query 和 document 可以显著提升召回质量。
 * <ul>
 *   <li>QUERY — 用户查询文本，模型生成更具方向性的向量，专为"提问"和"查找"优化</li>
 *   <li>DOCUMENT — 入库文档文本，模型生成包含更全面信息的向量，专为"被匹配"优化</li>
 *   <li>DISABLED — 不传 text_type，使用模型默认行为</li>
 * </ul>
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api">百炼向量模型文档</a>
 */
public enum TextType {

    /** 自动判断：embed(Document) → document, embed(String) → query */
    AUTO(null),

    /** 查询文本 — 强制 query */
    QUERY("query"),

    /** 文档文本 — 强制 document */
    DOCUMENT("document"),

    /** 不传 text_type */
    DISABLED(null);

    private final String value;

    TextType(String value) {
        this.value = value;
    }

    /** 获取 DashScope API 对应的参数值，DISABLED 返回 null */
    public String getValue() {
        return value;
    }
}
