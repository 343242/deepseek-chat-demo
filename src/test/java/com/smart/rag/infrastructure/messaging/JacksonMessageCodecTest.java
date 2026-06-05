package com.smart.rag.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JacksonMessageCodecTest {

    private JacksonMessageCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JacksonMessageCodec(new ObjectMapper());
    }

    @Test
    void roundTripEncodeDecode() {
        TestPayload original = new TestPayload("hello", 42, true);
        byte[] encoded = codec.encode(original);
        TestPayload decoded = codec.decode(encoded, TestPayload.class);
        assertEquals(original, decoded);
    }

    @Test
    void ignoresUnknownProperties() {
        byte[] json = """
            {"name":"test","value":1,"extra":"unknown"}
            """.getBytes();
        TestPayload decoded = codec.decode(json, TestPayload.class);
        assertEquals("test", decoded.name());
        assertEquals(1, decoded.value());
    }

    @Test
    void encodesNullAsJsonNull() {
        byte[] encoded = codec.encode(null);
        assertNotNull(encoded);
        assertArrayEquals("null".getBytes(), encoded);
    }

    record TestPayload(String name, int value, boolean active) {}
}
