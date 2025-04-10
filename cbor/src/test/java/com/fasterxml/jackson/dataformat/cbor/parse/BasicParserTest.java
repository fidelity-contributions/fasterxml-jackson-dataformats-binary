package com.fasterxml.jackson.dataformat.cbor.parse;

import java.io.*;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.dataformat.cbor.*;
import com.fasterxml.jackson.dataformat.cbor.testutil.ThrottledInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for simple value types.
 */
public class BasicParserTest extends CBORTestBase
{
    /**
     * Test for verifying handling of 'true', 'false' and 'null' literals
     */
    @Test
    public void testSimpleLiterals() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator gen = cborGenerator(out);
        gen.writeBoolean(true);
        assertEquals("/", gen.getOutputContext().toString());
        gen.close();
        JsonParser p = cborParser(out);
        assertEquals(JsonToken.VALUE_TRUE, p.nextToken());
        assertNull(p.nextToken());
        p.close();

        out = new ByteArrayOutputStream();
        gen = cborGenerator(out);
        gen.writeBoolean(false);
        gen.close();
        p = cborParser(out);
        assertEquals(JsonToken.VALUE_FALSE, p.nextToken());
        assertEquals("/", p.getParsingContext().toString());

        assertNull(p.nextToken());
        p.close();

        out = new ByteArrayOutputStream();
        gen = cborGenerator(out);
        gen.writeNull();
        gen.close();
        p = cborParser(out);
        assertEquals(JsonToken.VALUE_NULL, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testMediumText() throws Exception
    {
        _testMedium(1100);
        _testMedium(1300);
        _testMedium(1900);
        _testMedium(2300);
        _testMedium(3900);
    }

    @Test
    public void testMediumText2() throws Exception
    {
        for (int prefix : Arrays.asList(197, 198, 199, 200, 201, 497, 499, 500, 501)) {
            _testMedium(prefix, 1300);
            _testMedium(prefix, 1900);
            _testMedium(prefix, 2300);
            _testMedium(prefix, 3900);
        }
    }

    private void _testMedium(int len) throws Exception {
        _testMedium(0, len);
    }

    private void _testMedium(int asciiPrefixLen, int len) throws Exception
    {
        // First, use size that should fit in output buffer, but
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        final String MEDIUM = asciiPrefixLen == 0 ?
                generateUnicodeString(len) : generateUnicodeStringWithAsciiPrefix(asciiPrefixLen, len);
        gen.writeString(MEDIUM);
        gen.close();

        final byte[] b = out.toByteArray();

        // verify that it is indeed non-chunked still...
        assertEquals((byte) (CBORConstants.PREFIX_TYPE_TEXT + 25), b[0]);

        JsonParser p = cborParser(b);
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(MEDIUM, p.getText());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testCurrentLocationByteOffset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        gen.writeString("1234567890");
        gen.writeString("1234567890");
        gen.close();

        final byte[] b = out.toByteArray();

        JsonParser p = cborParser(b);

        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(1, p.currentLocation().getByteOffset());
        p.getText(); // fully read token.
        assertEquals(11, p.currentLocation().getByteOffset());

        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertEquals(12, p.currentLocation().getByteOffset());
        p.getText();
        assertEquals(22, p.currentLocation().getByteOffset());

        assertNull(p.nextToken());
        assertEquals(22, p.currentLocation().getByteOffset());

        p.close();
        assertEquals(22, p.currentLocation().getByteOffset());
    }

    @Test
    public void testLongNonChunkedText() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        final String LONG = generateUnicodeString(37000);
        final byte[] LONG_B = LONG.getBytes("UTF-8");
        final int BYTE_LEN = LONG_B.length;
        out.write(CBORConstants.BYTE_ARRAY_INDEFINITE);
        out.write((byte) (CBORConstants.PREFIX_TYPE_TEXT + 25));
        out.write((byte) (BYTE_LEN >> 8));
        out.write((byte) BYTE_LEN);
        out.write(LONG.getBytes("UTF-8"));
        out.write(CBORConstants.BYTE_BREAK);

        final byte[] b = out.toByteArray();
        assertEquals(BYTE_LEN + 5, b.length);

        // Important! Need to construct a stream, to force boundary conditions
        JsonParser p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.START_ARRAY, p.nextToken());
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        String actual = p.getText();

        final int end = Math.min(LONG.length(), actual.length());
        for (int i = 0; i < end; ++i) {
            if (LONG.charAt(i) != actual.charAt(i)) {
                fail("Character #"+i+" (of "+end+") differs; expected 0x"+Integer.toHexString(LONG.charAt(i))
                        +" found 0x"+Integer.toHexString(actual.charAt(i)));
            }
        }

        assertEquals(LONG.length(), actual.length());

        assertEquals(LONG, p.getText());
        assertToken(JsonToken.END_ARRAY, p.nextToken());
        assertNull(p.nextToken());
        p.close();
    }

    @Test
    public void testLongChunkedText() throws Exception
    {
        // First, try with ASCII content
        StringBuilder sb = new StringBuilder(21000);
        for (int i = 0; i < 21000; ++i) {
            sb.append('Z');
        }
        _testLongChunkedText(sb.toString());
        // Second, with actual variable byte-length Unicode
        _testLongChunkedText(generateUnicodeString(21000));
    }

    @Test
    public void testLongChunkedText2() throws Exception
    {
        // The text buffer starting size is 200 bytes, let's cycle around that
        // amount to verify the tight ascii loop.
        for (int prefix = 194; prefix < 202; ++prefix) {
            _testLongChunkedText(generateUnicodeStringWithAsciiPrefix(prefix, 21000));
        }
    }

    @SuppressWarnings("resource")
    public void _testLongChunkedText(String input) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator gen = cborGenerator(out);
        gen.writeString(input);
        gen.close();

        final int textByteCount = input.getBytes("UTF-8").length;
        final byte[] b = out.toByteArray();
        assertEquals((byte) (CBORConstants.PREFIX_TYPE_TEXT + 0x1F), b[0]);
        assertEquals(CBORConstants.BYTE_BREAK, b[b.length-1]);

        // First, verify validity by scanning
        int i = 1;
        int total = 0;

        for (int end = b.length-1; i < end; ) {
            int type = b[i++] & 0xFF;
            int len = type - CBORConstants.PREFIX_TYPE_TEXT;

            if (len < 24) { // tiny, fine
                ;
            } else if (len == 24) { // 1-byte
                len = (b[i++] & 0xFF);
            } else if (len == 25) { // 2-byte
                len = ((b[i++] & 0xFF) << 8) + (b[i++] & 0xFF);
            }
            i += len;
            total += len;
        }
        assertEquals(b.length-1, i);
        assertEquals(textByteCount, total);

        JsonParser p;

        // then skipping
        p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        assertNull(p.nextToken());
        p.close();

        // and then with actual full parsing/access
        p = cborParser(new ThrottledInputStream(new ByteArrayInputStream(b), 3));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        String actual = p.getText();
        assertNull(p.nextToken());
        assertEquals(input.length(), actual.length());
        if (!input.equals(actual)) {
            _debugDiff(input, actual);
        }
        assertEquals(input, actual);
        p.close();

        // one more thing: with 2.8 we have new `getText()` variant
        p = cborParser(new ByteArrayInputStream(b));
        assertToken(JsonToken.VALUE_STRING, p.nextToken());
        StringWriter w = new StringWriter();
        int len = p.getText(w);
        actual = w.toString();
        assertEquals(len, actual.length());
        assertEquals(input.length(), actual.length());
        if (!input.equals(actual)) {
            _debugDiff(input, actual);
        }
        p.close();
    }

    private void _debugDiff(String expected, String actual)
    {
        int i = 0;
        while (i < expected.length() && expected.charAt(i) == actual.charAt(i)) { ++i; }
        fail("Strings differ at #"+i+" (length "+expected.length()+"); expected 0x"
                +Integer.toHexString(expected.charAt(i))+", got 0x"
                +Integer.toHexString(actual.charAt(i)));
    }

    @Test
    public void testStringField() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeStringField("a", "b");
        generator.writeEndObject();
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("a", parser.currentName());
        assertEquals("a", parser.getText());
        assertEquals("a", parser.getValueAsString());
        assertEquals("a", parser.getValueAsString("x"));
        assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
        assertEquals("a", parser.currentName());
        assertEquals("b", parser.getText());
        assertEquals("b", parser.getValueAsString());
        assertEquals("b", parser.getValueAsString("x"));
        assertEquals(1, parser.getTextLength());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        // For fun, release
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        assertEquals(0, parser.releaseBuffered(extra));

        parser.close();
    }

    @Test
    public void testNestedObject() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeFieldName("ob");
        generator.writeStartObject();
        generator.writeNumberField("num", 3);
        generator.writeEndObject();
        generator.writeFieldName("arr");
        generator.writeStartArray();
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());

        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("ob", parser.currentName());
        assertEquals("ob", parser.getText());
        assertEquals("ob", parser.getValueAsString());
        assertEquals("ob", parser.getValueAsString("x"));
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("num", parser.currentName());
        assertEquals("num", parser.getText());
        assertEquals("num", parser.getValueAsString());
        assertEquals("num", parser.getValueAsString("y"));
        assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals("arr", parser.currentName());
        assertEquals("arr", parser.getText());
        assertEquals("arr", parser.getValueAsString());
        assertEquals("arr", parser.getValueAsString("z"));
        assertEquals(JsonToken.START_ARRAY, parser.nextToken());
        assertEquals(JsonToken.END_ARRAY, parser.nextToken());

        assertEquals(JsonToken.END_OBJECT, parser.nextToken());
        parser.close();
    }

    @Test
    public void testBufferRelease() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORGenerator generator = cborGenerator(out);
        generator.writeStartObject();
        generator.writeStringField("a", "1");
        generator.writeEndObject();
        generator.flush();
        // add stuff that is NOT part of the Object
        out.write(new byte[] { 1, 2, 3 });
        generator.close();

        CBORParser parser = cborParser(out.toByteArray());
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
        assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
        assertEquals(JsonToken.END_OBJECT, parser.nextToken());

        // Fine; but now should be able to retrieve 3 bytes that are (likely)
        // to have been  buffered

        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        assertEquals(3, parser.releaseBuffered(extra));
        byte[] extraBytes = extra.toByteArray();
        assertEquals((byte) 1, extraBytes[0]);
        assertEquals((byte) 2, extraBytes[1]);
        assertEquals((byte) 3, extraBytes[2]);

        parser.close();
    }
}
