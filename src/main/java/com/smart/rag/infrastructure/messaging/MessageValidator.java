package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.ClientException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

import java.util.regex.Pattern;

/**
 * Message validation and encoding — extracts topic/tag/pattern validation shared by bus implementations.
 */
public class MessageValidator {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]{0,127}$");
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern TOPIC_PREFIX_PATTERN = Pattern.compile("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]*$");

    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;

    public MessageValidator(MessagingProperties properties, MessagePayloadCodec codec) {
        this.properties = properties;
        this.codec = codec;
    }

    public byte[] validateAndEncode(MessageEnvelope<?> messageEnvelope) {
        String fullTopic = properties.topicPrefix() + messageEnvelope.topic();
        if (fullTopic.length() > 128) {
            throw new ClientException(MessagingErrorCode.INVALID_TOPIC,
                "Topic名称过长: '" + messageEnvelope.topic() + "' (含前缀" + fullTopic.length() + "字符，上限128)");
        }
        if (!TOPIC_PATTERN.matcher(messageEnvelope.topic()).matches()) {
            throw new ClientException(MessagingErrorCode.INVALID_TOPIC,
                "非法Topic名称: '" + messageEnvelope.topic() + "'，仅允许字母/数字/下划线/连字符/百分号，长度1-128");
        }
        if (messageEnvelope.tag() != null && !TAG_PATTERN.matcher(messageEnvelope.tag()).matches()) {
            throw new ClientException(MessagingErrorCode.INVALID_TAG,
                "非法标签名称: '" + messageEnvelope.tag() + "'，仅允许字母/数字/下划线/连字符，长度1-64");
        }
        return codec.encode(messageEnvelope.payload());
    }

    public static void validateTopicPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()
            && !TOPIC_PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new ClientException(MessagingErrorCode.INVALID_TOPIC,
                "非法Topic前缀: '" + prefix + "'");
        }
    }
}
