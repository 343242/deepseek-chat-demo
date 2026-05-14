package com.demo.chat.chat.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis implementation of {@link ChatMemoryRepository} using Lettuce + Jackson.
 * <p>
 * Stores each conversation's messages as a JSON array under a dedicated key,
 * and maintains a Redis Set of all conversation IDs for {@link #findConversationIds()}.
 * <p>
 * Key design:
 * <ul>
 *   <li>{@code {prefix}{conversationId}} → JSON String (message array)</li>
 *   <li>{@code {prefix}conversations} → Redis Set (all conversation IDs)</li>
 * </ul>
 *
 * @author chat-demo
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String DEFAULT_KEY_PREFIX = "chat:memory:";
    private static final String CONVERSATIONS_SUFFIX = "conversations";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final String conversationsKey;
    private final long ttlSeconds;

    private RedisChatMemoryRepository(Builder builder) {
        Assert.notNull(builder.redisTemplate, "StringRedisTemplate must not be null");
        Assert.notNull(builder.objectMapper, "ObjectMapper must not be null");

        this.redisTemplate = builder.redisTemplate;
        this.objectMapper = builder.objectMapper;
        this.keyPrefix = builder.keyPrefix != null ? builder.keyPrefix : DEFAULT_KEY_PREFIX;
        this.conversationsKey = this.keyPrefix + CONVERSATIONS_SUFFIX;
        this.ttlSeconds = builder.ttlSeconds != null ? builder.ttlSeconds : -1;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== ChatMemoryRepository ====================

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = redisTemplate.opsForSet().members(conversationsKey);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ids);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");

        String json = redisTemplate.opsForValue().get(messageKey(conversationId));
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {
            List<MessageDocument> documents = objectMapper.readValue(json, new TypeReference<>() {});
            return documents.stream()
                    .map(this::toMessage)
                    .collect(Collectors.toList());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize messages for conversation {}: {}", conversationId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");
        Assert.notNull(messages, "Messages must not be null");

        List<MessageDocument> documents = messages.stream()
                .map(this::toDocument)
                .collect(Collectors.toList());

        try {
            String json = objectMapper.writeValueAsString(documents);
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(messageKey(conversationId), json, ttlSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(messageKey(conversationId), json);
            }
            redisTemplate.opsForSet().add(conversationsKey, conversationId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize messages for conversation " + conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");

        redisTemplate.delete(messageKey(conversationId));
        redisTemplate.opsForSet().remove(conversationsKey, conversationId);
    }

    // ==================== Internal ====================

    private String messageKey(String conversationId) {
        return keyPrefix + conversationId;
    }

    // ==================== Serialization Helpers ====================

    private MessageDocument toDocument(Message message) {
        MessageDocument doc = new MessageDocument();
        doc.setType(message.getMessageType().getValue());
        doc.setContent(message.getText());
        doc.setMetadata(message.getMetadata());

        if (message instanceof AssistantMessage assistant) {
            if (assistant.hasToolCalls()) {
                doc.setToolCalls(assistant.getToolCalls().stream()
                        .map(tc -> {
                            MessageDocument.ToolCallDoc tcDoc = new MessageDocument.ToolCallDoc();
                            tcDoc.setId(tc.id());
                            tcDoc.setType(tc.type());
                            tcDoc.setName(tc.name());
                            tcDoc.setArguments(tc.arguments());
                            return tcDoc;
                        })
                        .collect(Collectors.toList()));
            }
            if (assistant.getMedia() != null && !assistant.getMedia().isEmpty()) {
                doc.setMedia(assistant.getMedia().stream()
                        .map(this::toMediaDoc)
                        .collect(Collectors.toList()));
            }
        } else if (message instanceof UserMessage user) {
            if (user.getMedia() != null && !user.getMedia().isEmpty()) {
                doc.setMedia(user.getMedia().stream()
                        .map(this::toMediaDoc)
                        .collect(Collectors.toList()));
            }
        } else if (message instanceof ToolResponseMessage toolResponse) {
            if (toolResponse.getResponses() != null && !toolResponse.getResponses().isEmpty()) {
                doc.setToolResponses(toolResponse.getResponses().stream()
                        .map(tr -> {
                            MessageDocument.ToolResponseDoc trDoc = new MessageDocument.ToolResponseDoc();
                            trDoc.setId(tr.id());
                            trDoc.setName(tr.name());
                            trDoc.setResponse(tr.responseData());
                            return trDoc;
                        })
                        .collect(Collectors.toList()));
            }
        }

        return doc;
    }

    private Message toMessage(MessageDocument doc) {
        return switch (MessageType.fromValue(doc.getType())) {
            case USER -> buildUserMessage(doc);
            case ASSISTANT -> buildAssistantMessage(doc);
            case SYSTEM -> buildSystemMessage(doc);
            case TOOL -> buildToolResponseMessage(doc);
        };
    }

    private UserMessage buildUserMessage(MessageDocument doc) {
        UserMessage.Builder builder = UserMessage.builder()
                .text(doc.getContent());
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            builder.metadata(doc.getMetadata());
        }
        return builder.build();
    }

    private AssistantMessage buildAssistantMessage(MessageDocument doc) {
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(doc.getContent());
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            builder.properties(doc.getMetadata());
        }
        if (doc.getToolCalls() != null && !doc.getToolCalls().isEmpty()) {
            builder.toolCalls(doc.getToolCalls().stream()
                    .map(tcDoc -> new AssistantMessage.ToolCall(
                            tcDoc.getId() != null ? tcDoc.getId() : "",
                            tcDoc.getType() != null ? tcDoc.getType() : "",
                            tcDoc.getName() != null ? tcDoc.getName() : "",
                            tcDoc.getArguments() != null ? tcDoc.getArguments() : ""
                    ))
                    .collect(Collectors.toList()));
        }
        return builder.build();
    }

    private SystemMessage buildSystemMessage(MessageDocument doc) {
        SystemMessage.Builder builder = SystemMessage.builder()
                .text(doc.getContent());
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            builder.metadata(doc.getMetadata());
        }
        return builder.build();
    }

    private ToolResponseMessage buildToolResponseMessage(MessageDocument doc) {
        ToolResponseMessage.Builder builder = ToolResponseMessage.builder();
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            builder.metadata(doc.getMetadata());
        }
        if (doc.getToolResponses() != null && !doc.getToolResponses().isEmpty()) {
            builder.responses(doc.getToolResponses().stream()
                    .map(trDoc -> new ToolResponseMessage.ToolResponse(
                            trDoc.getId() != null ? trDoc.getId() : "",
                            trDoc.getName() != null ? trDoc.getName() : "",
                            trDoc.getResponse() != null ? trDoc.getResponse() : ""
                    ))
                    .collect(Collectors.toList()));
        }
        return builder.build();
    }

    private MessageDocument.MediaDoc toMediaDoc(org.springframework.ai.content.Media media) {
        MessageDocument.MediaDoc mediaDoc = new MessageDocument.MediaDoc();
        mediaDoc.setMimeType(media.getMimeType().toString());
        mediaDoc.setData(media.getData() != null ? media.getData().toString() : null);
        return mediaDoc;
    }

    // ==================== Builder ====================

    public static class Builder {
        private StringRedisTemplate redisTemplate;
        private ObjectMapper objectMapper;
        private @Nullable String keyPrefix;
        private @Nullable Long ttlSeconds;

        public Builder redisTemplate(StringRedisTemplate redisTemplate) {
            this.redisTemplate = redisTemplate;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder ttlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
            return this;
        }

        public RedisChatMemoryRepository build() {
            return new RedisChatMemoryRepository(this);
        }
    }

    // ==================== DTO for JSON serialization ====================

    /**
     * JSON-serializable representation of a {@link Message}.
     * Avoids Jackson polymorphic deserialization complexity by flattening message type.
     */
    static class MessageDocument {
        private String type;
        private String content;
        private Map<String, Object> metadata;
        private List<ToolCallDoc> toolCalls;
        private List<ToolResponseDoc> toolResponses;
        private List<MediaDoc> media;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

        public List<ToolCallDoc> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCallDoc> toolCalls) { this.toolCalls = toolCalls; }

        public List<ToolResponseDoc> getToolResponses() { return toolResponses; }
        public void setToolResponses(List<ToolResponseDoc> toolResponses) { this.toolResponses = toolResponses; }

        public List<MediaDoc> getMedia() { return media; }
        public void setMedia(List<MediaDoc> media) { this.media = media; }

        static class ToolCallDoc {
            private String id;
            private String type;
            private String name;
            private String arguments;

            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getArguments() { return arguments; }
            public void setArguments(String arguments) { this.arguments = arguments; }
        }

        static class ToolResponseDoc {
            private String id;
            private String name;
            private String response;

            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getResponse() { return response; }
            public void setResponse(String response) { this.response = response; }
        }

        static class MediaDoc {
            private String mimeType;
            private String data;

            public String getMimeType() { return mimeType; }
            public void setMimeType(String mimeType) { this.mimeType = mimeType; }
            public String getData() { return data; }
            public void setData(String data) { this.data = data; }
        }
    }
}
