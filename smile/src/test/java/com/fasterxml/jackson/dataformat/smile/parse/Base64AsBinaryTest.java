package com.fasterxml.jackson.dataformat.smile.parse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.BaseTestForSmile;

import static org.junit.jupiter.api.Assertions.*;

public class Base64AsBinaryTest extends BaseTestForSmile
{
    private final static String ENCODED_BASE64 = "VGVzdCE=";
    private final static byte[] DECODED_BASE64 = "Test!".getBytes(StandardCharsets.US_ASCII);

    private final byte[] CBOR_DOC;

    private final ObjectMapper MAPPER = smileMapper();

    public Base64AsBinaryTest() throws Exception {
        CBOR_DOC = _smileDoc("{\"value\":\""+ENCODED_BASE64+"\"}");
    }

    // [dataformats-binary#284]: binary from Base64 encoded
    @Test
    public void testGetBase64AsBinary() throws Exception
    {
        // First, verify regularly
        try (JsonParser p = MAPPER.createParser(CBOR_DOC)) {
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.FIELD_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            assertEquals(ENCODED_BASE64, p.getText());
            assertToken(JsonToken.END_OBJECT, p.nextToken());
            assertNull(p.nextToken());
        }

        // then with getBinaryValue()
        try (JsonParser p = MAPPER.createParser(CBOR_DOC)) {
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.FIELD_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            byte[] binary = p.getBinaryValue();
            assertArrayEquals(DECODED_BASE64, binary);
            assertToken(JsonToken.END_OBJECT, p.nextToken());
            assertNull(p.nextToken());
        }
    }

    // [dataformats-binary#284]: binary from Base64 encoded
    @Test
    public void testReadBase64AsBinary() throws Exception
    {
        // And further via read
        try (JsonParser p = MAPPER.createParser(CBOR_DOC)) {
            assertToken(JsonToken.START_OBJECT, p.nextToken());
            assertToken(JsonToken.FIELD_NAME, p.nextToken());
            assertToken(JsonToken.VALUE_STRING, p.nextToken());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int count = p.readBinaryValue(bytes);
            assertEquals(5, count);
            assertArrayEquals(DECODED_BASE64, bytes.toByteArray());
            assertToken(JsonToken.END_OBJECT, p.nextToken());
            assertNull(p.nextToken());
        }
    }
}
