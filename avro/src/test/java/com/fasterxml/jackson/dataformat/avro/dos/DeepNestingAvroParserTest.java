package com.fasterxml.jackson.dataformat.avro.dos;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.avro.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for deeply nested Documents
 */
public class DeepNestingAvroParserTest extends AvroTestBase
{
    protected final String NODE_SCHEMA_JSON = "{\n"
            +"\"type\": \"record\",\n"
            +"\"name\": \"Node\",\n"
            +"\"fields\": [\n"
            +" {\"name\": \"id\", \"type\": \"int\"},\n"
            +" {\"name\": \"next\", \"type\": [\"Node\",\"null\"]}\n"
            +"]}";

    static class Node {
        public int id;
        public Node next;

        protected Node() { }
        public Node(int id, Node next) {
            this.id = id;
            this.next = next;
        }
    }

    private final AvroMapper DEFAULT_MAPPER = newMapper();

    // Unlike default depth of 1000 for other formats, use lower (400) here
    // because we cannot actually generate 1000 levels due to Avro codec's
    // limitations
    private final AvroMapper MAPPER_400;
    {
        AvroFactory f = AvroFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(400).build())
                .build();
        MAPPER_400 = new AvroMapper(f);
    }

    private final AvroSchema NODE_SCHEMA;
    {
        try {
            NODE_SCHEMA = DEFAULT_MAPPER.schemaFrom(NODE_SCHEMA_JSON);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testDeeplyNestedObjectsHighLimits() throws Exception
    {
        byte[] doc = genDeepDoc(410);
        try (JsonParser jp = avroParser(DEFAULT_MAPPER, doc)) {
            while (jp.nextToken() != null) { }
        }
    }

    @Test
    public void testDeeplyNestedObjectsLowLimits() throws Exception
    {
        byte[] doc = genDeepDoc(410);
        try (JsonParser jp = avroParser(MAPPER_400, doc)) {
            while (jp.nextToken() != null) { }
            fail("expected StreamConstraintsException");
        } catch (StreamConstraintsException e) {
            assertTrue(e.getMessage().startsWith("Document nesting depth (401) exceeds the maximum allowed"),
                    "unexpected message: " + e.getMessage());
        }
    }

    private byte[] genDeepDoc(int depth) throws Exception {
        Node node = null;

        while (--depth > 0) {
            node = new Node(depth, node);
        }
        return DEFAULT_MAPPER.writer(NODE_SCHEMA)
                .writeValueAsBytes(node);
    }

    private JsonParser avroParser(ObjectMapper mapper, byte[] doc) throws Exception {
        return mapper.readerFor(Node.class)
                .with(NODE_SCHEMA)
                .createParser(doc);
    }
}
