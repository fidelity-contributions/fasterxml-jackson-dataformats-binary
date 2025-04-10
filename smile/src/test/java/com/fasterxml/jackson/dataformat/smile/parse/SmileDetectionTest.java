package com.fasterxml.jackson.dataformat.smile.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.format.*;
import com.fasterxml.jackson.dataformat.smile.*;

import static org.junit.jupiter.api.Assertions.*;

public class SmileDetectionTest extends BaseTestForSmile
{

    @Test
    public void testSimpleObjectWithHeader() throws IOException
    {
        SmileFactory f = new SmileFactory();
        DataFormatDetector detector = new DataFormatDetector(f);
        byte[] doc = _smileDoc("{\"a\":3}", true);
        DataFormatMatcher matcher = detector.findFormat(doc);
        // should have match
        assertTrue(matcher.hasMatch());
        assertEquals("Smile", matcher.getMatchedFormatName());
        assertSame(f, matcher.getMatch());
        // with header, should be full match
        assertEquals(MatchStrength.FULL_MATCH, matcher.getMatchStrength());
        // and so:
        JsonParser jp = matcher.createParserWithMatch();
        assertToken(JsonToken.START_OBJECT, jp.nextToken());
        assertToken(JsonToken.FIELD_NAME, jp.nextToken());
        assertEquals("a", jp.currentName());
        assertToken(JsonToken.VALUE_NUMBER_INT, jp.nextToken());
        assertEquals(3, jp.getIntValue());
        assertToken(JsonToken.END_OBJECT, jp.nextToken());
        assertNull(jp.nextToken());
        jp.close();
    }

    @Test
    public void testSimpleObjectWithoutHeader() throws IOException
    {
        SmileFactory f = new SmileFactory();
        DataFormatDetector detector = new DataFormatDetector(f);
        f.disable(SmileParser.Feature.REQUIRE_HEADER);
        byte[] doc = _smileDoc("{\"abc\":false}", false);
        DataFormatMatcher matcher = detector.findFormat(doc);
        assertTrue(matcher.hasMatch());
        assertEquals("Smile", matcher.getMatchedFormatName());
        assertSame(f, matcher.getMatch());
        assertEquals(MatchStrength.SOLID_MATCH, matcher.getMatchStrength());
        JsonParser jp = matcher.createParserWithMatch();
        assertToken(JsonToken.START_OBJECT, jp.nextToken());
        assertToken(JsonToken.FIELD_NAME, jp.nextToken());
        assertEquals("abc", jp.currentName());
        assertToken(JsonToken.VALUE_FALSE, jp.nextToken());
        assertToken(JsonToken.END_OBJECT, jp.nextToken());
        assertNull(jp.nextToken());
        jp.close();
    }

    @Test
    public void testSimpleArrayWithHeader() throws IOException
    {
        SmileFactory f = new SmileFactory();
        DataFormatDetector detector = new DataFormatDetector(f);
        byte[] doc = _smileDoc("[ true, 7 ]", true);
        DataFormatMatcher matcher = detector.findFormat(doc);
        // should have match
        assertTrue(matcher.hasMatch());
        assertEquals("Smile", matcher.getMatchedFormatName());
        assertSame(f, matcher.getMatch());
        // with header, should be full match
        assertEquals(MatchStrength.FULL_MATCH, matcher.getMatchStrength());
        JsonParser jp = matcher.createParserWithMatch();
        assertToken(JsonToken.START_ARRAY, jp.nextToken());
        assertToken(JsonToken.VALUE_TRUE, jp.nextToken());
        assertToken(JsonToken.VALUE_NUMBER_INT, jp.nextToken());
        assertEquals(7, jp.getIntValue());
        assertToken(JsonToken.END_ARRAY, jp.nextToken());
        assertNull(jp.nextToken());
        jp.close();
    }

    @Test
    public void testSimpleArrayWithoutHeader() throws IOException
    {
        SmileFactory f = new SmileFactory();
        f.disable(SmileParser.Feature.REQUIRE_HEADER);
        DataFormatDetector detector = new DataFormatDetector(f);
        byte[] doc = _smileDoc("[ -13 ]", false);
        DataFormatMatcher matcher = detector.findFormat(doc);
        assertTrue(matcher.hasMatch());
        assertEquals("Smile", matcher.getMatchedFormatName());
        assertSame(f, matcher.getMatch());
        assertEquals(MatchStrength.SOLID_MATCH, matcher.getMatchStrength());
        JsonParser jp = matcher.createParserWithMatch();
        assertToken(JsonToken.START_ARRAY, jp.nextToken());
        assertToken(JsonToken.VALUE_NUMBER_INT, jp.nextToken());
        assertToken(JsonToken.END_ARRAY, jp.nextToken());
        assertNull(jp.nextToken());
        jp.close();
    }

    /*
    /**********************************************************
    /* Simple negative tests
    /**********************************************************
     */

    /*
     * Also let's ensure no match is found if data doesn't support it...
     * Let's use 0xFD since it can not be included (except in raw binary;
     * use of which requires header to be present)
     */
    @Test
    public void testSimpleInvalid() throws Exception
    {
        DataFormatDetector detector = new DataFormatDetector(new SmileFactory());
        byte FD = (byte) 0xFD;
        byte[] DOC = new byte[] { FD, FD, FD, FD };
        DataFormatMatcher matcher = detector.findFormat(new ByteArrayInputStream(DOC));
        assertFalse(matcher.hasMatch());
        assertEquals(MatchStrength.INCONCLUSIVE, matcher.getMatchStrength());
        assertNull(matcher.createParserWithMatch());
    }

    /*
    /**********************************************************
    /* Fallback tests to ensure Smile is found even against JSON
    /**********************************************************
     */

    @Test
    public void testSmileVsJson() throws IOException
    {
        SmileFactory f = new SmileFactory();
        f.disable(SmileParser.Feature.REQUIRE_HEADER);
        DataFormatDetector detector = new DataFormatDetector(new JsonFactory(), f);
        // to make it bit trickier, leave out header
        byte[] doc = _smileDoc("[ \"abc\" ]", false);
        DataFormatMatcher matcher = detector.findFormat(doc);
        assertTrue(matcher.hasMatch());
        assertEquals("Smile", matcher.getMatchedFormatName());
        assertSame(f, matcher.getMatch());
        // without header, just solid
        assertEquals(MatchStrength.SOLID_MATCH, matcher.getMatchStrength());
        JsonParser jp = matcher.createParserWithMatch();
        assertToken(JsonToken.START_ARRAY, jp.nextToken());
        assertToken(JsonToken.VALUE_STRING, jp.nextToken());
        assertToken(JsonToken.END_ARRAY, jp.nextToken());
        assertNull(jp.nextToken());
        jp.close();
    }

}
