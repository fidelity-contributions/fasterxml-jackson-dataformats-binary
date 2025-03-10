package com.fasterxml.jackson.dataformat.ion.fuzz;

import java.io.InputStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.dataformat.ion.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

// [dataformats-binary#420]
public class Fuzz420_65062_65083IOOBETest
{
    @Test
    public void testFuzz6506265083IOOBE() throws Exception {
       IonFactory f = IonFactory
                          .builderForTextualWriters()
                          .enable(IonParser.Feature.USE_NATIVE_TYPE_ID)
                          .build();
       IonObjectMapper mapper = IonObjectMapper.builder(f).build();
       try (InputStream in = getClass().getResourceAsStream("/data/fuzz-420.ion")) {
           mapper.readTree(in);
           fail("Should not pass (invalid content)");
       } catch (StreamReadException e) {
           assertThat(e.getMessage(), Matchers.containsString("Corrupt content to decode"));
       }
    }
}
