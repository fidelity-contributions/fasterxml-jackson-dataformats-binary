package com.fasterxml.jackson.dataformat.smile.async;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.smile.*;

import static org.junit.jupiter.api.Assertions.*;

public class StringObjectTest extends AsyncTestBase
{
    private final static String STR0_9 = "0123456789";
    private final static String ASCII_SHORT_NAME = "a"+STR0_9+"z";
    private final static String UNICODE_SHORT_NAME = "Unicode"+UNICODE_3BYTES+"RlzOk";
    private final static String UNICODE_LONG_NAME = String.format(
            "Unicode-"+UNICODE_3BYTES+"-%s-%s-%s-"+UNICODE_2BYTES+"-%s-%s-%s-"+UNICODE_3BYTES+"-%s-%s-%s",
            STR0_9, STR0_9, STR0_9, STR0_9, STR0_9, STR0_9, STR0_9, STR0_9, STR0_9);

    @Test
    public void testBasicFieldsNamesSharedNames() throws IOException {
        _testBasicFieldsNames(true);
    }

    @Test
    public void testBasicFieldsNamesNonShared() throws IOException {
        _testBasicFieldsNames(false);
    }

    private void _testBasicFieldsNames(boolean sharedNames) throws IOException
    {
        final String json = aposToQuotes(String.format("{'%s':'%s','%s':'%s','%s':'%s'}",
            UNICODE_SHORT_NAME, UNICODE_LONG_NAME,
            UNICODE_LONG_NAME, UNICODE_SHORT_NAME,
            ASCII_SHORT_NAME, ASCII_SHORT_NAME));

        final SmileFactory f = new SmileFactory();
        f.enable(SmileParser.Feature.REQUIRE_HEADER);
        f.configure(SmileGenerator.Feature.CHECK_SHARED_NAMES, sharedNames);
        f.configure(SmileGenerator.Feature.CHECK_SHARED_STRING_VALUES, sharedNames);

        byte[] data = _smileDoc(f, json, true);
        _testBasicFieldsNames(f, data, 0, 100);
        _testBasicFieldsNames(f, data, 0, 3);
        _testBasicFieldsNames(f, data, 0, 1);

        _testBasicFieldsNames(f, data, 1, 100);
        _testBasicFieldsNames(f, data, 1, 3);
        _testBasicFieldsNames(f, data, 1, 1);
    }

    private void _testBasicFieldsNames(SmileFactory f,
            byte[] data, int offset, int readSize) throws IOException
    {
        _testBasicFieldsNames2(f, data, offset, readSize, true);
        _testBasicFieldsNames2(f, data, offset, readSize, false);
    }

    private void _testBasicFieldsNames2(SmileFactory f,
            byte[] data, int offset, int readSize, boolean verifyContents) throws IOException
    {
        AsyncReaderWrapper r = asyncForBytes(f, readSize, data, offset);

        // start with "no token"
        assertNull(r.currentToken());
        assertToken(JsonToken.START_OBJECT, r.nextToken());

        assertToken(JsonToken.FIELD_NAME, r.nextToken());
        if (verifyContents) {
            assertEquals(UNICODE_SHORT_NAME, r.currentName());
            assertEquals(UNICODE_SHORT_NAME, r.currentText());
        }
        assertToken(JsonToken.VALUE_STRING, r.nextToken());
        // also, should always be accessible this way:
        if (verifyContents) {
            assertTrue(r.parser().hasTextCharacters());
            assertEquals(UNICODE_LONG_NAME, r.currentText());
        }

        assertToken(JsonToken.FIELD_NAME, r.nextToken());
        if (verifyContents) {
            assertEquals(UNICODE_LONG_NAME, r.currentName());
            assertEquals(UNICODE_LONG_NAME, r.currentText());
        }
        assertToken(JsonToken.VALUE_STRING, r.nextToken());
        if (verifyContents) {
            assertEquals(UNICODE_SHORT_NAME, r.currentText());
        }

        // and ASCII entry
        assertToken(JsonToken.FIELD_NAME, r.nextToken());
        if (verifyContents) {
            assertEquals(ASCII_SHORT_NAME, r.currentName());
            assertEquals(ASCII_SHORT_NAME, r.currentText());
        }
        assertToken(JsonToken.VALUE_STRING, r.nextToken());
        if (verifyContents) {
            assertEquals(ASCII_SHORT_NAME, r.currentText());
        }

        assertToken(JsonToken.END_OBJECT, r.nextToken());
        assertNull(r.nextToken());

        // Second round, try with alternate read method
        if (verifyContents) {
            r = asyncForBytes(f, readSize, data, offset);
            assertToken(JsonToken.START_OBJECT, r.nextToken());
            assertToken(JsonToken.FIELD_NAME, r.nextToken());
            assertEquals(UNICODE_SHORT_NAME, r.currentTextViaWriter());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals(UNICODE_LONG_NAME, r.currentTextViaWriter());

            assertToken(JsonToken.FIELD_NAME, r.nextToken());
            assertEquals(UNICODE_LONG_NAME, r.currentTextViaWriter());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals(UNICODE_SHORT_NAME, r.currentTextViaWriter());

            assertToken(JsonToken.FIELD_NAME, r.nextToken());
            assertEquals(ASCII_SHORT_NAME, r.currentTextViaWriter());
            assertToken(JsonToken.VALUE_STRING, r.nextToken());
            assertEquals(ASCII_SHORT_NAME, r.currentTextViaWriter());

            assertToken(JsonToken.END_OBJECT, r.nextToken());
        }
    }
}
