package com.fasterxml.jackson.dataformat.avro.annotation;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.dataformat.avro.AvroMapper;
import com.fasterxml.jackson.dataformat.avro.schema.AvroSchemaGenerator;

import static org.assertj.core.api.Assertions.assertThat;

public class AvroNamespaceTest {

    static class ClassWithoutAvroNamespaceAnnotation {
    }

    @AvroNamespace("ClassWithAvroNamespaceAnnotation.namespace")
    static class ClassWithAvroNamespaceAnnotation {
    }

    enum EnumWithoutAvroNamespaceAnnotation {FOO, BAR;}

    @AvroNamespace("EnumWithAvroNamespaceAnnotation.namespace")
    enum EnumWithAvroNamespaceAnnotation {FOO, BAR;}

    static class Foo {
        static class Bar {
            static class ClassWithMultipleNestingLevels {
            }

            enum EnumWithMultipleNestingLevels {FOO, BAR;}
        }
    }

    @Test
    public void class_without_AvroNamespace_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(ClassWithoutAvroNamespaceAnnotation.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("com.fasterxml.jackson.dataformat.avro.annotation.AvroNamespaceTest");
    }

    @Test
    public void class_with_AvroNamespace_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(ClassWithAvroNamespaceAnnotation.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("ClassWithAvroNamespaceAnnotation.namespace");
    }

    @Test
    public void class_with_multiple_nesting_levels_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(Foo.Bar.ClassWithMultipleNestingLevels.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("com.fasterxml.jackson.dataformat.avro.annotation.AvroNamespaceTest.Foo.Bar");
    }

    @Test
    public void enum_without_AvroNamespace_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(EnumWithoutAvroNamespaceAnnotation.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("com.fasterxml.jackson.dataformat.avro.annotation.AvroNamespaceTest");
    }

    @Test
    public void enum_with_AvroNamespace_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(EnumWithAvroNamespaceAnnotation.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("EnumWithAvroNamespaceAnnotation.namespace");
    }

    @Test
    public void enum_with_multiple_nesting_levels_test() throws Exception {
        // GIVEN
        AvroMapper mapper = new AvroMapper();
        AvroSchemaGenerator gen = new AvroSchemaGenerator();

        // WHEN
        mapper.acceptJsonFormatVisitor(Foo.Bar.EnumWithMultipleNestingLevels.class, gen);
        Schema actualSchema = gen.getGeneratedSchema().getAvroSchema();

        // THEN
        assertThat(actualSchema.getNamespace())
                .isEqualTo("com.fasterxml.jackson.dataformat.avro.annotation.AvroNamespaceTest.Foo.Bar");
    }
}
