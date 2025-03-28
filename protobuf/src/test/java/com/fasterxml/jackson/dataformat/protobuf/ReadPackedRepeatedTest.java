package com.fasterxml.jackson.dataformat.protobuf;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReadPackedRepeatedTest extends ProtobufTestBase
{
    @Test
    public void testPacked() throws Exception
    {
        final ProtobufMapper mapper = newObjectMapper();
        final String SCHEMA_STR =
            "package mypackage;\n"
            + "message t {\n"
            + "        repeated uint32 f = 1 [packed=true];\n"
            + "}";
        final byte[] pb = {0xa, 0x3, 0x64, (byte)0xc8, 0x1}; // f = [100, 200]

        ProtobufSchema schema = mapper.schemaLoader().load(new StringReader(SCHEMA_STR));
        JsonNode t = mapper.readerFor(JsonNode.class).with(schema).readValue(pb);

        assertEquals(2, t.get("f").size());
        assertEquals(100, t.get("f").get(0).asInt());
        assertEquals(200, t.get("f").get(1).asInt());
    }
}
