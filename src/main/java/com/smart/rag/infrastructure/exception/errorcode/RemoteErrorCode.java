package com.smart.rag.infrastructure.exception.errorcode;

/**
 * 第三方服务错误码 (C类, 300001–399999)
 * <p>
 * 调用外部模型、向量数据库、文件存储等第三方服务失败。
 */
public enum RemoteErrorCode implements IErrorCode {

    PROVIDER_NOT_FOUND(300001, "厂商未配置"),
    MODEL_TIMEOUT(300002, "模型调用超时"),
    VECTOR_DB_UNAVAILABLE(300003, "向量数据库不可用"),
    FILE_STORAGE_UNAVAILABLE(300004, "文件存储暂不可用"),

    // ==================== LLM 弹性层 301001–301999 ====================
    LLM_ALL_MODELS_FAILED(301001, "所有模型均不可用"),
    LLM_CIRCUIT_BREAKER_OPEN(301002, "模型熔断器已打开，请稍后重试"),
    LLM_PROBE_TIMEOUT(301003, "模型首包探测超时"),
    LLM_RATE_LIMITED(301004, "模型调用频率超限"),
    LLM_CONFIG_ERROR(301005, "LLM 配置错误"),
    LLM_PROVIDER_UNAVAILABLE(301006, "模型厂商不可用"),
    LLM_RESPONSE_TRUNCATED(301007, "模型响应被截断"),
    LLM_STREAM_ERROR(301008, "模型流式调用失败"),
    LLM_TRANSIENT_ERROR(301009, "模型调用瞬态失败（重试耗尽）"),
    LLM_RESPONSE_PARSE_ERROR(301010, "模型响应解析失败"),

    // ==================== MCP 弹性层 302001–302999 ====================
    MCP_SERVER_UNREACHABLE(302001, "MCP server 不可达"),
    MCP_TOOL_TIMEOUT(302002, "MCP 工具调用超时"),
    MCP_RATE_LIMITED(302003, "MCP server 限流"),
    MCP_CIRCUIT_BREAKER_OPEN(302004, "MCP server 熔断器已打开，请稍后重试");

    private final int code;
    private final String message;

    RemoteErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
