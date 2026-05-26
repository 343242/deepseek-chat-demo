package com.smart.rag.rag.agent.mode;

import com.smart.rag.chat.mode.ChatMode;
import com.smart.rag.chat.mode.ChatModeStrategy;
import org.springframework.stereotype.Component;

@Component
public class AgentModeStrategy implements ChatModeStrategy {
    @Override
    public ChatMode getMode() { return ChatMode.AGENT; }
    @Override
    public boolean isMemoryEnabled() { return true; }
    @Override
    public boolean isContextEnabled() { return true; }
    @Override
    public boolean isThinkingEnabled() { return false; }
    @Override
    public boolean isAgentMode() { return true; }
}
