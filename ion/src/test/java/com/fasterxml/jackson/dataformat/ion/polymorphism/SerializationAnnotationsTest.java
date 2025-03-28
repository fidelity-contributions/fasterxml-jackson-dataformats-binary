package com.fasterxml.jackson.dataformat.ion.polymorphism;

import java.io.IOException;

import com.amazon.ion.IonValue;
import com.amazon.ion.system.IonSystemBuilder;
import com.amazon.ion.util.Equivalence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.dataformat.ion.IonGenerator;
import com.fasterxml.jackson.dataformat.ion.IonObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class SerializationAnnotationsTest {

    private static final String SUBCLASS_TYPE_NAME =
            SerializationAnnotationsTest.Subclass.class.getTypeName();

    private static final IonValue SUBCLASS_TYPED_AS_PROPERTY = asIonValue(
            "{" +
                    "  '@class':\"" + SUBCLASS_TYPE_NAME + "\"," +
                    "  someString:\"some value\"," +
                    "  anInt:42" +
                    "}");

    private static final IonValue SUBCLASS_TYPED_BY_ANNOTATION = asIonValue(
            "'" + SUBCLASS_TYPE_NAME + "'::{" +
                    "  someString:\"some value\"," +
                    "  anInt:42" +
                    "}");

    private Subclass subclass;

    @BeforeEach
    public void setup() {
        this.subclass = new Subclass("some value", 42);
    }

    @Test
    public void testNativeTypeIdsEnabledOnWriteByDefault() throws IOException {
        IonObjectMapper mapper = new IonObjectMapper();
        IonValue subclassAsIon = mapper.writeValueAsIonValue(subclass);

        assertEqualIonValues(SUBCLASS_TYPED_BY_ANNOTATION, subclassAsIon);

        BaseClass roundTripInstance = mapper.readValue(subclassAsIon, BaseClass.class);

        assertCorrectlyTypedAndFormed(subclass, roundTripInstance);
    }


    @Test
    public void mapper() throws IOException {
        IonObjectMapper mapper = IonObjectMapper.builderForTextualWriters()
                .disable(IonGenerator.Feature.USE_NATIVE_TYPE_ID)
                .build();

        IonValue subclassAsIon = mapper.writeValueAsIonValue(subclass);
        assertEqualIonValues(SUBCLASS_TYPED_AS_PROPERTY, subclassAsIon);

        BaseClass roundTripInstance = mapper.readValue(subclassAsIon, BaseClass.class);

        assertCorrectlyTypedAndFormed(subclass, roundTripInstance);
    }

    @Test
    public void testNativeTypeIdsDisabledStillReadsNativeTypesSuccessfully() throws IOException {
        IonObjectMapper writer = new IonObjectMapper(); // native type ids enabled by default

        IonValue subclassAsIon = writer.writeValueAsIonValue(subclass);

        assertEqualIonValues(SUBCLASS_TYPED_BY_ANNOTATION, subclassAsIon);

        IonObjectMapper reader = IonObjectMapper.builderForTextualWriters()
                .disable(IonGenerator.Feature.USE_NATIVE_TYPE_ID)
                .build();

        BaseClass roundTripInstance = reader.readValue(subclassAsIon, BaseClass.class);

        assertCorrectlyTypedAndFormed(subclass, roundTripInstance);
    }

    /*
    /**********************************************************
    /* Helper methods etc.
    /**********************************************************
     */


    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    static public abstract class BaseClass { /* empty */ }

    public static class Subclass extends BaseClass {
        public String someString;
        public int anInt;

        public Subclass() {};
        public Subclass(String s, int i) {
            this.someString = s;
            this.anInt = i;
        }
    }

    private static IonValue asIonValue(final String ionStr) {
        return IonSystemBuilder.standard().build().singleValue(ionStr);
    }

    private static void assertCorrectlyTypedAndFormed(final Subclass expectedSubclass, final BaseClass actualBaseclass) {
        assertInstanceOf(Subclass.class, actualBaseclass);
        _assertEquals(expectedSubclass, (Subclass) actualBaseclass);
    }
    private static void _assertEquals(Subclass expected, Subclass actual) {
        assertEquals(expected.someString, actual.someString);
        assertEquals(expected.anInt, actual.anInt);
    }

    private static void assertEqualIonValues(IonValue expected, IonValue actual) {
        if (!Equivalence.ionEquals(expected, actual)) {
            String message = String.format("Expected %s but found %s",
                    expected.toPrettyString(), actual.toPrettyString());
            throw new AssertionError(message);
        }
    }
}
