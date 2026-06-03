package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.io.Serial;

/**
 * 限流异常 (A类)
 */
public class RateLimitExceededException extends ClientException {

    @Serial
    private static final long serialVersionUID = 55234234L;

    public RateLimitExceededException(String message) {
        super(ClientErrorCode.RATE_LIMITED, message);
    }
}
