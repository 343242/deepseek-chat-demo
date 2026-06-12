package com.smart.rag.infrastructure.llm;

/**
 * Chat 候选——支持深度思考和流式输出
 */
public final class ChatCandidate extends AbstractModelCandidate {

    private boolean supportsThinking;
    private boolean supportsStreaming;

    @Override public boolean supportsThinking() { return supportsThinking; }
    @Override public boolean supportsStreaming() { return supportsStreaming; }

    public boolean isSupportsThinking() { return supportsThinking; }
    public void setSupportsThinking(boolean supportsThinking) { this.supportsThinking = supportsThinking; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
}
