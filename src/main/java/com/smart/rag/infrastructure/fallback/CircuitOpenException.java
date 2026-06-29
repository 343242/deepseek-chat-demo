package com.smart.rag.infrastructure.fallback;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

import java.io.Serial;

/**
 * 熔断器打开异常（通用，C类）。
 * <p>
 * 当熔断器处于 OPEN 状态时抛出，表示该 key（LLM 的 candidateId / MCP 的 ServerId）
 * 近期连续失败过多。
 * <p>
 * <b>错误码由装配方注入</b>：LLM 用 {@code RemoteErrorCode.LLM_CIRCUIT_BREAKER_OPEN}，
 * MCP 用 {@code RemoteErrorCode.MCP_CIRCUIT_BREAKER_OPEN}，复用同一异常类型——
 * 避免为每个域复制一个仅 message 不同的异常类。继承 {@link RemoteException}（C类）。
 */
public class CircuitOpenException extends RemoteException {

    @Serial
    private static final long serialVersionUID = 67234560L;

    private final String candidateId;

    public CircuitOpenException(IErrorCode errorCode, String candidateId) {
        super(errorCode, "熔断器已打开: " + candidateId);
        this.candidateId = candidateId;
    }

    public String candidateId() {
        return candidateId;
    }
}
