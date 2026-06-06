package com.smart.rag.infrastructure.messaging;

import java.util.regex.Pattern;

/**
 * Message validation and encoding — extracts topic/tag/pattern validation
 * and payload size checks from RocketMQMessageBus.
 */
class MessageValidator {

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]{0,127}$");
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;

    MessageValidator(MessagingProperties properties, MessagePayloadCodec codec) {
        this.properties = properties;
        this.codec = codec;
    }

    byte[] validateAndEncode(MessageEnvelope<?> messageEnvelope) {
        String fullTopic = properties.topicPrefix() + messageEnvelope.topic();
        if (fullTopic.length() > 128) {
            throw new IllegalArgumentException(
                "Full topic name too long: '" + fullTopic
                + "' (prefix + topic = " + fullTopic.length() + " chars, max 128)");
        }
        if (!TOPIC_PATTERN.matcher(messageEnvelope.topic()).matches()) {
            throw new IllegalArgumentException(
                "Invalid topic name: '" + messageEnvelope.topic()
                + "'. Must be 1-128 chars, alphanumeric/underscore/hyphen/percent only.");
        }
        if (messageEnvelope.tag() != null && !TAG_PATTERN.matcher(messageEnvelope.tag()).matches()) {
            throw new IllegalArgumentException(
                "Invalid tag name: '" + messageEnvelope.tag()
                + "'. Must be 1-64 chars, alphanumeric/underscore/hyphen only.");
        }
        byte[] encoded = codec.encode(messageEnvelope.payload());
        if (encoded.length > properties.rocketmq().maxMessageSize()) {
            throw new IllegalArgumentException(
                "Message payload too large: " + encoded.length + " bytes");
        }
        return encoded;
    }

    static void validateTopicPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()
            && !Pattern.matches("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]*$", prefix)) {
            throw new IllegalArgumentException(
                "Invalid topicPrefix: '" + prefix
                + "'. Must start with alphanumeric/underscore/hyphen, "
                + "followed by alphanumeric/underscore/hyphen/percent characters only.");
        }
    }
}
