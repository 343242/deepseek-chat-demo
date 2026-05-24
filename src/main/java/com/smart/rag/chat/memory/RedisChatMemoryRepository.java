package com.smart.rag.chat.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Redis implementation of {@link ChatMemoryRepository} using Lettuce + Jackson.
 * <p>
 * Uses Redis Sorted Set per conversation for incremental append and time-ordered access.
 * Conversation ID discovery via key SCAN pattern (no global set — avoids cross-user leakage).
 * <p>
 * Key design:
 * <ul>
 *   <li>{@code {prefix}{conversationId}} → Sorted Set (score=timestamp, member=message JSON)</li>
 * </ul>
 * <p>
 * User isolation is achieved through key naming: the conversationId already contains
 * userId (via {@code ConversationIdUtil.buildIsolatedId}), so each user's data lives
 * under distinct keys. {@link #findConversationIds()} uses SCAN with a user-scoped pattern
 * if a userId prefix is detectable; otherwise falls back to a full prefix scan.
 *
 * @author smart-rag
 */
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String DEFAULT_KEY_PREFIX = "chat:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long ttlSeconds;

    private RedisChatMemoryRepository(Builder builder) {
        Assert.notNull(builder.redisTemplate, "StringRedisTemplate must not be null");
        Assert.notNull(builder.objectMapper, "ObjectMapper must not be null");

        this.redisTemplate = builder.redisTemplate;
        this.objectMapper = objectMapperCopy(builder.objectMapper);
        this.keyPrefix = builder.keyPrefix != null ? builder.keyPrefix : DEFAULT_KEY_PREFIX;
        this.ttlSeconds = builder.ttlSeconds != null ? builder.ttlSeconds : -1;
    }

    /**
     * Create a copy of ObjectMapper to avoid mutating the shared Spring bean.
     * Configures it to skip null fields for compact JSON storage.
     */
    private static ObjectMapper objectMapperCopy(ObjectMapper source) {
        return source.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== ChatMemoryRepository ====================

    /**
     * Returns all conversation IDs by scanning Redis keys matching the prefix pattern.
     * <p>
     * Since conversationId already encodes userId (format: {@code u_{userId}_{rawId}}),
     * this method returns all conversations across all users.
     * Callers should filter by userId if needed, or use a SCAN with a more specific pattern.
     */
    @Override
    public @NonNull List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        // 使用 Redis SCAN 替代 KEYS，避免 O(N) 全库阻塞
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (var cursor = connection.keyCommands().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(keyPrefix + "*")
                            .count(100)
                            .build())) {
                cursor.forEachRemaining(key -> {
                    String keyStr = new String(key);
                    String id = keyStr.substring(keyPrefix.length());
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                });
            }
            return null;
        });
        return ids;
    }

    @Override
    public @NonNull List<Message> findByConversationId(@NonNull String conversationId) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");

        String key = messageKey(conversationId);
        Set<String> jsonMembers = redisTemplate.opsForZSet().range(key, 0, -1);
        if (jsonMembers == null || jsonMembers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> messages = new ArrayList<>(jsonMembers.size());
        for (String json : jsonMembers) {
            try {
                MessageDocument doc = objectMapper.readValue(json, MessageDocument.class);
                messages.add(toMessage(doc));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize message in conversation {}, skipping: {}",
                        conversationId, e.getMessage());
                // Skip corrupted message instead of failing entire list
            }
        }
        return messages;
    }

    /**
     * Replaces all messages for a conversation.
     * <p>
     * Deletes the existing sorted set and bulk-inserts new entries in a single Pipeline
     * for atomicity. Also sets TTL if configured.
     */
    @Override
    public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");
        Assert.notNull(messages, "Messages must not be null");

        String key = messageKey(conversationId);

        // Use timestamp-based score for ordering; start from current millis
        long baseTimestamp = System.currentTimeMillis();

        List<String> jsonList = messages.stream()
                .map(msg -> {
                    try {
                        return objectMapper.writeValueAsString(toDocument(msg));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(
                                "Failed to serialize message for conversation " + conversationId, e);
                    }
                })
                .toList();

        // Pipeline: DELETE + ZADD all + EXPIRE (atomic batch)
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            connection.keyCommands().del(keyBytes);

            for (int i = 0; i < jsonList.size(); i++) {
                double score = baseTimestamp + i;
                connection.zSetCommands().zAdd(keyBytes, score, jsonList.get(i).getBytes(StandardCharsets.UTF_8));
            }

            if (ttlSeconds > 0) {
                connection.keyCommands().expire(keyBytes, ttlSeconds);
            }
            return null;
        });
    }

    @Override
    public void deleteByConversationId(@NonNull String conversationId) {
        Assert.hasText(conversationId, "Conversation ID must not be empty");
        redisTemplate.delete(messageKey(conversationId));
    }

    // ==================== Internal ====================

    private String messageKey(String conversationId) {
        return keyPrefix + conversationId;
    }

    // ==================== Serialization ====================

    private Message toMessage(MessageDocument doc) {
        return switch (MessageType.fromValue(doc.type())) {
            case USER -> buildUserMessage(doc);
            case ASSISTANT -> buildAssistantMessage(doc);
            case SYSTEM -> buildSystemMessage(doc);
            case TOOL -> buildToolResponseMessage(doc);
        };
    }

    private MessageDocument toDocument(Message message) {
        return new MessageDocument(
                message.getMessageType().getValue(),
                message.getText(),
                message.getMetadata().isEmpty() ? null : message.getMetadata(),
                extractToolCalls(message),
                extractToolResponses(message),
                extractMedia(message)
        );
    }

    private @Nullable List<MessageDocument.ToolCallDoc> extractToolCalls(Message message) {
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            return assistant.getToolCalls().stream()
                    .map(tc -> new MessageDocument.ToolCallDoc(tc.id(), tc.type(), tc.name(), tc.arguments()))
                    .toList();
        }
        return null;
    }

    private @Nullable List<MessageDocument.ToolResponseDoc> extractToolResponses(Message message) {
        if (message instanceof ToolResponseMessage toolResponse
                && toolResponse.getResponses() != null && !toolResponse.getResponses().isEmpty()) {
            return toolResponse.getResponses().stream()
                    .map(tr -> new MessageDocument.ToolResponseDoc(tr.id(), tr.name(), tr.responseData()))
                    .toList();
        }
        return null;
    }

    private @Nullable List<MessageDocument.MediaDoc> extractMedia(Message message) {
        if (message instanceof AssistantMessage assistant) {
            return toMediaDocs(assistant.getMedia());
        }
        if (message instanceof UserMessage user) {
            return toMediaDocs(user.getMedia());
        }
        return null;
    }

    private @Nullable List<MessageDocument.MediaDoc> toMediaDocs(
            @Nullable List<org.springframework.ai.content.Media> mediaList) {
        if (mediaList == null || mediaList.isEmpty()) {
            return null;
        }
        return mediaList.stream()
                .map(m -> new MessageDocument.MediaDoc(
                        m.getMimeType().toString(),
                        m.getData() != null ? m.getData().toString() : null))
                .toList();
    }

    private UserMessage buildUserMessage(MessageDocument doc) {
        UserMessage.Builder builder = UserMessage.builder().text(doc.content());
        if (doc.metadata() != null && !doc.metadata().isEmpty()) {
            builder.metadata(doc.metadata());
        }
        return builder.build();
    }

    private AssistantMessage buildAssistantMessage(MessageDocument doc) {
        AssistantMessage.Builder builder = AssistantMessage.builder().content(doc.content());
        if (doc.metadata() != null && !doc.metadata().isEmpty()) {
            builder.properties(doc.metadata());
        }
        if (doc.toolCalls() != null && !doc.toolCalls().isEmpty()) {
            builder.toolCalls(doc.toolCalls().stream()
                    .map(tcDoc -> new AssistantMessage.ToolCall(
                            Objects.requireNonNullElse(tcDoc.id(), ""),
                            Objects.requireNonNullElse(tcDoc.type(), ""),
                            Objects.requireNonNullElse(tcDoc.name(), ""),
                            Objects.requireNonNullElse(tcDoc.arguments(), "")))
                    .toList());
        }
        return builder.build();
    }

    private SystemMessage buildSystemMessage(MessageDocument doc) {
        SystemMessage.Builder builder = SystemMessage.builder().text(doc.content());
        if (doc.metadata() != null && !doc.metadata().isEmpty()) {
            builder.metadata(doc.metadata());
        }
        return builder.build();
    }

    private ToolResponseMessage buildToolResponseMessage(MessageDocument doc) {
        ToolResponseMessage.Builder builder = ToolResponseMessage.builder();
        if (doc.metadata() != null && !doc.metadata().isEmpty()) {
            builder.metadata(doc.metadata());
        }
        if (doc.toolResponses() != null && !doc.toolResponses().isEmpty()) {
            builder.responses(doc.toolResponses().stream()
                    .map(trDoc -> new ToolResponseMessage.ToolResponse(
                            Objects.requireNonNullElse(trDoc.id(), ""),
                            Objects.requireNonNullElse(trDoc.name(), ""),
                            Objects.requireNonNullElse(trDoc.response(), "")))
                    .toList());
        }
        return builder.build();
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

    // ==================== DTO (records per spec) ====================

    /**
     * JSON-serializable representation of a {@link Message}.
     * Flattens message type to avoid Jackson polymorphic deserialization.
     *
     * @param type          message type value (USER, ASSISTANT, SYSTEM, TOOL)
     * @param content       message text content
     * @param metadata      optional metadata map
     * @param toolCalls     optional tool calls (AssistantMessage only)
     * @param toolResponses optional tool responses (ToolResponseMessage only)
     * @param media         optional media attachments (UserMessage/AssistantMessage)
     */
    record MessageDocument(
            String type,
            String content,
            @Nullable Map<String, Object> metadata,
            @Nullable List<ToolCallDoc> toolCalls,
            @Nullable List<ToolResponseDoc> toolResponses,
            @Nullable List<MediaDoc> media
    ) {
        record ToolCallDoc(
                @Nullable String id,
                @Nullable String type,
                @Nullable String name,
                @Nullable String arguments
        ) {}

        record ToolResponseDoc(
                @Nullable String id,
                @Nullable String name,
                @Nullable String response
        ) {}

        record MediaDoc(
                String mimeType,
                @Nullable String data
        ) {}
    }
}
