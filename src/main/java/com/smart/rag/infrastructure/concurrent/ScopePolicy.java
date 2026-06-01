package com.smart.rag.infrastructure.concurrent;

public enum ScopePolicy {
    SHUTDOWN_ON_FAILURE,
    SHUTDOWN_ON_SUCCESS,
    COLLECT_ALL,
    PARTIAL_SUCCESS_OR_THROW,
    QUORUM_SUCCESS
}
