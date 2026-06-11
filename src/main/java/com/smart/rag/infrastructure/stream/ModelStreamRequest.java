package com.smart.rag.infrastructure.stream;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.provider.ModelRouter;

public record ModelStreamRequest(
        ModelRouter.Route route,
        String prompt,
        String baseUrl,
        String completionsPath,
        String apiKey,
        Double temperature,
        Integer maxTokens
) {

    public ModelStreamRequest {
        if (route == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "模型路由不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "聊天内容不能为空");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "模型 API 地址不能为空");
        }
        if (completionsPath == null || completionsPath.isBlank()) {
            completionsPath = "/chat/completions";
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR, "模型 API Key 不能为空");
        }
    }
}
