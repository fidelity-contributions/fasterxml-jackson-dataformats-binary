package com.fasterxml.jackson.dataformat.avro.jsr310;

import java.time.*;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.avro.PackageVersion;
import com.fasterxml.jackson.dataformat.avro.jsr310.deser.*;
import com.fasterxml.jackson.dataformat.avro.jsr310.ser.*;

/**
 * A module that installs a collection of serializers and deserializers for java.time classes.
 *
 * AvroJavaTimeModule module is to be used either as:
 *   - replacement of Java 8 date/time module (com.fasterxml.jackson.datatype.jsr310.JavaTimeModule) or
 *   - to override Java 8 date/time module and for that, module must be registered AFTER Java 8 date/time module.
 */
public class AvroJavaTimeModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public AvroJavaTimeModule() {
        super(AvroJavaTimeModule.class.getName(), PackageVersion.VERSION);

        addSerializer(Instant.class, AvroInstantSerializer.INSTANT);
        addSerializer(OffsetDateTime.class, AvroInstantSerializer.OFFSET_DATE_TIME);
        addSerializer(ZonedDateTime.class, AvroInstantSerializer.ZONED_DATE_TIME);
        addSerializer(LocalDateTime.class, AvroLocalDateTimeSerializer.INSTANCE);
        addSerializer(LocalDate.class, AvroLocalDateSerializer.INSTANCE);
        addSerializer(LocalTime.class, AvroLocalTimeSerializer.INSTANCE);

        addDeserializer(Instant.class, AvroInstantDeserializer.INSTANT);
        addDeserializer(OffsetDateTime.class, AvroInstantDeserializer.OFFSET_DATE_TIME);
        addDeserializer(ZonedDateTime.class, AvroInstantDeserializer.ZONED_DATE_TIME);
        addDeserializer(LocalDateTime.class, AvroLocalDateTimeDeserializer.INSTANCE);
        addDeserializer(LocalDate.class, AvroLocalDateDeserializer.INSTANCE);
        addDeserializer(LocalTime.class, AvroLocalTimeDeserializer.INSTANCE);
    }

}
