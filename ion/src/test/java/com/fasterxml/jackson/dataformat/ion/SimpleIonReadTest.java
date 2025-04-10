/*
 * Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at:
 *
 *     http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.fasterxml.jackson.dataformat.ion;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SimpleIonReadTest {
    private final IonFactory ION_F = new IonFactory();
    // // // Actual tests; low level

    @Test
    public void testSimpleStructRead() throws IOException
    {
        try (JsonParser p = ION_F.createParser("{a:\"value\",b:42, c:null}")) {
            assertEquals(JsonToken.START_OBJECT, p.nextToken());
            assertEquals(JsonToken.FIELD_NAME, p.nextToken());
            assertEquals("a", p.currentName());
            assertEquals("a", p.getText());
            assertEquals("a", p.getValueAsString());
            assertEquals("a", p.getValueAsString("x"));
            assertEquals(JsonToken.VALUE_STRING, p.nextToken());
            assertEquals("value", p.getText());
            assertEquals("value", p.getText());
            assertEquals("value", p.getValueAsString());
            assertEquals("value", p.getValueAsString("x"));
            assertEquals(JsonToken.VALUE_NUMBER_INT, p.nextValue());
            assertEquals("b", p.currentName());
            assertEquals(42, p.getIntValue());
            assertEquals(JsonToken.VALUE_NULL, p.nextValue());
            assertEquals("c", p.currentName());
            assertEquals(JsonToken.END_OBJECT, p.nextToken());
        }
    }

    @Test
    public void testSimpleListRead() throws IOException
    {
        JsonParser jp = ION_F.createParser("[  12, true, null, \"abc\" ]");
        assertEquals(JsonToken.START_ARRAY, jp.nextToken());
        assertEquals(JsonToken.VALUE_NUMBER_INT, jp.nextValue());
        assertEquals(12, jp.getIntValue());
        assertEquals(JsonToken.VALUE_TRUE, jp.nextValue());
        assertEquals(JsonToken.VALUE_NULL, jp.nextValue());
        assertEquals(JsonToken.VALUE_STRING, jp.nextValue());
        assertEquals("abc", jp.getText());
        assertEquals(JsonToken.END_ARRAY, jp.nextToken());
        jp.close();
    }

    @Test
    public void testSimpleStructAndArray() throws IOException
    {
        JsonParser jp = ION_F.createParser("{a:[\"b\",\"c\"], b:null}");
        assertEquals(JsonToken.START_OBJECT, jp.nextToken());
        assertEquals(JsonToken.FIELD_NAME, jp.nextToken());
        assertEquals("a", jp.currentName());
        assertEquals(JsonToken.START_ARRAY, jp.nextToken());
        assertEquals(JsonToken.VALUE_STRING, jp.nextToken());
        assertEquals("b", jp.getText());
        assertEquals(JsonToken.VALUE_STRING, jp.nextToken());
        assertEquals("c", jp.getText());
        assertEquals(JsonToken.END_ARRAY, jp.nextToken());
        assertEquals(JsonToken.FIELD_NAME, jp.nextToken());
        assertEquals("b", jp.currentName());
        assertEquals(JsonToken.VALUE_NULL, jp.nextToken());
        assertEquals("b", jp.currentName());
        assertEquals(JsonToken.END_OBJECT, jp.nextToken());
        jp.close();
    }

    @Test
    public void testMixed() throws IOException
    {
        JsonParser jp = ION_F.createParser("{a:[ 1, { b:  13}, \"xyz\" ], c:null, d:true}");
        assertEquals(JsonToken.START_OBJECT, jp.nextToken());
        assertEquals(JsonToken.START_ARRAY, jp.nextValue());
        //assertEquals("a", jp.currentName());
        assertEquals(JsonToken.VALUE_NUMBER_INT, jp.nextValue());
        assertNull(jp.currentName());
        assertEquals(1, jp.getIntValue());
        assertEquals(JsonToken.START_OBJECT, jp.nextValue());
        assertEquals(JsonToken.VALUE_NUMBER_INT, jp.nextValue());
        assertEquals("b", jp.currentName());
        assertEquals(13, jp.getIntValue());
        assertEquals(JsonToken.END_OBJECT, jp.nextValue());
        assertEquals(JsonToken.VALUE_STRING, jp.nextValue());
        assertEquals("xyz", jp.getText());
        assertNull(jp.currentName());
        assertEquals(JsonToken.END_ARRAY, jp.nextValue());
        assertEquals(JsonToken.VALUE_NULL, jp.nextValue());
        assertEquals("c", jp.currentName());
        assertEquals(JsonToken.VALUE_TRUE, jp.nextValue());
        assertEquals("d", jp.currentName());

        assertEquals(JsonToken.END_OBJECT, jp.nextToken());
        jp.close();
    }

    @Test
    public void testNullIonType() throws IOException {
        JsonParser jp = ION_F.createParser("{a:\"value\",b:42, c:null.int}");
        assertEquals(JsonToken.START_OBJECT, jp.nextToken());
        assertEquals(JsonToken.FIELD_NAME, jp.nextToken());
        assertEquals("a", jp.currentName());
        assertEquals(JsonToken.VALUE_STRING, jp.nextToken());
        assertEquals("value", jp.getText());
        assertEquals(JsonToken.VALUE_NUMBER_INT, jp.nextValue());
        assertEquals("b", jp.currentName());
        assertEquals(42, jp.getIntValue());
        assertEquals(JsonToken.VALUE_NULL, jp.nextValue());
        assertEquals("c", jp.currentName());
        assertEquals(JsonToken.END_OBJECT, jp.nextToken());
        jp.close();
    }
}
