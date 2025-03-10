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

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

import com.amazon.ion.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.JsonWriteContext;
import com.fasterxml.jackson.core.util.JacksonFeatureSet;
import com.fasterxml.jackson.dataformat.ion.polymorphism.IonAnnotationTypeSerializer;

/**
 * Implementation of {@link JsonGenerator} that will use an underlying
 * {@link IonWriter} for actual writing of content.
 */
public class IonGenerator
    extends GeneratorBase
{
    /**
     * Enumeration that defines all toggleable features for Ion generators
     */
    public enum Feature implements FormatFeature // since 2.12
    {
        /**
         * Whether to use Ion native Type Id construct for indicating type (true);
         * or "generic" type property (false) when writing. Former works better for
         * systems that are Ion-centric; latter may be better choice for interoperability,
         * when converting between formats or accepting other formats.
         *<p>
         * Enabled by default for backwards compatibility as that has been the behavior
         * of `jackson-dataformat-ion` since 2.9 (first official public version)
         *
         * @see <a href="https://amzn.github.io/ion-docs/docs/spec.html#annot">The Ion Specification</a>
         *
         * @since 2.12
         */
        USE_NATIVE_TYPE_ID(true),
        ;

        protected final boolean _defaultState;
        protected final int _mask;

        /**
         * Method that calculates bit set (flags) of all features that
         * are enabled by default.
         */
        public static int collectDefaults()
        {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }

        private Feature(boolean defaultState) {
            _defaultState = defaultState;
            _mask = (1 << ordinal());
        }

        @Override
        public boolean enabledByDefault() { return _defaultState; }
        @Override
        public boolean enabledIn(int flags) { return (flags & _mask) != 0; }
        @Override
        public int getMask() { return _mask; }
    }

    /*
    /**********************************************************************
    /* Basic configuration
    /**********************************************************************
     */

    /* This is the textual or binary writer */
    protected final IonWriter _writer;
    /* Indicates whether the IonGenerator is responsible for closing the underlying IonWriter. */
    protected final boolean _ionWriterIsManaged;

    /**
     * @since 2.16
     */
    protected final StreamWriteConstraints _streamWriteConstraints;

    /**
     * Bit flag composed of bits that indicate which
     * {@link IonGenerator.Feature}s
     * are enabled.
     */
    protected int _formatFeatures;

    /**
     * Highest-level output abstraction we can use; either
     * OutputStream or Writer.
     */
    protected final Closeable _destination;

    /*
    /**********************************************************************
    /* Instantiation
    /**********************************************************************
     */

    public IonGenerator(int jsonFeatures, final int ionFeatures, ObjectCodec codec,
            IonWriter ion, boolean ionWriterIsManaged, IOContext ctxt, Closeable dst)
    {
        super(jsonFeatures, codec, ctxt);
        //  Overwrite the writecontext with our own implementation
        _writeContext = IonWriteContext.createRootContext(_writeContext.getDupDetector());
        _formatFeatures = ionFeatures;
        _writer = ion;
        _ionWriterIsManaged = ionWriterIsManaged;
        _streamWriteConstraints = ctxt.streamWriteConstraints();
        _destination = dst;
    }

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    @Override
    public StreamWriteConstraints streamWriteConstraints() {
        return _streamWriteConstraints;
    }

    /*
    /**********************************************************************
    /* JsonGenerator implementation: state handling
    /**********************************************************************
     */

    @Override
    public void close() throws IOException
    {
        if (!isClosed()) {
            if (_ionWriterIsManaged) {
                _writer.close();
            }
            if (_ioContext.isResourceManaged()) {
                _destination.close();
            } else {
                if (isEnabled(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)) {
                    if (_destination instanceof Flushable) {
                        ((Flushable) _destination).flush();
                    }
                }
            }
            super.close();
        }
    }

    @Override
    public void flush() throws IOException
    {
        if (isEnabled(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)) {
            Object dst = _ioContext.contentReference().getRawContent();
            if (dst instanceof Flushable) {
                ((Flushable) dst).flush();
            }
        }
    }

    @Override
    public boolean isClosed() {
        return _closed;
    }

    /*
    /**********************************************************************
    /* Capability introspection
    /**********************************************************************
     */

    @Override
    public boolean canWriteTypeId() {
        // yes, Ion does support Native Type Ids!
        // 29-Nov-2020, jobarr: Except as per [dataformats-binary#225] might not want to...
        return Feature.USE_NATIVE_TYPE_ID.enabledIn(_formatFeatures);
    }

    @Override
    public boolean canWriteBinaryNatively() { return true; }

    @Override // @since 2.12
    public JacksonFeatureSet<StreamWriteCapability> getWriteCapabilities() {
        return DEFAULT_BINARY_WRITE_CAPABILITIES;
    }

    /*
    /**********************************************************************
    /* JsonGenerator implementation: write numeric values
    /**********************************************************************
     */

    @Override
    public void writeNumber(int value) throws IOException {
        _verifyValueWrite("write numeric value");
        _writer.writeInt(value);
    }

    @Override
    public void writeNumber(long value) throws IOException {
        _verifyValueWrite("write numeric value");
        _writer.writeInt(value);
    }

    @Override
    public void writeNumber(BigInteger value) throws IOException {
        if (value == null) {
            writeNull();
        } else {
            _verifyValueWrite("write numeric value");
            _writer.writeInt(value);
        }
    }

    @Override
    public void writeNumber(double value) throws IOException {
        _verifyValueWrite("write numeric value");
        _writer.writeFloat(value);
    }

    @Override
    public void writeNumber(float value) throws IOException {
        _verifyValueWrite("write numeric value");
        _writer.writeFloat(value);
    }

    @Override
    public void writeNumber(BigDecimal value) throws IOException {
        if (value == null) {
            writeNull();
        } else {
            _verifyValueWrite("write numeric value");
            _writer.writeDecimal(value);
        }
    }

    @Override
    public void writeNumber(String value) throws IOException, UnsupportedOperationException {
        // will lose its identity... fine
        writeString(value);
    }

    public void writeSymbol(String value) throws IOException {
        _verifyValueWrite("write symbol value");
        _writer.writeSymbol(value);
    }

    /**
     * Annotates the next structure or value written -- {@link IonWriter#stepIn(IonType) stepIn()} or one of the
     * {@link IonWriter}s {@code write*()} methods.
     *
     * @param annotation a type annotation
     *
     * @see IonAnnotationTypeSerializer
     */
    public void annotateNextValue(String annotation) {
        // We're not calling _verifyValueWrite because this doesn't actually write anything -- writing happens upon
        // the next _writer.write*() or stepIn().
        _writer.addTypeAnnotation(annotation);
    }

    // // // Ion Extensions

    public void writeDate(Calendar value) throws IOException {
        _verifyValueWrite("write date value");
        _writer.writeTimestamp(Timestamp.forCalendar(value));
    }

    /*
    /**********************************************************************
    /* JsonGenerator implementation: write textual values
    /**********************************************************************
      */

    @Override
    public void writeString(String value) throws IOException {
        _verifyValueWrite("write text value");
        _writer.writeString(value);
    }

    @Override
    public void writeString(char[] buffer, int offset, int length) throws IOException {
        // Ion doesn't have matching optimized method, so:
        writeString(new String(buffer, offset, length));
    }

    @Override
    public void writeUTF8String(byte[] buffer, int offset, int length) throws IOException {
        // Ion doesn't have matching optimized method, so:
        writeString(new String(buffer, offset, length, StandardCharsets.UTF_8));
    }

    /*
    /**********************************************************************
    /* JsonGenerator implementation: write raw JSON; N/A for Ion
    /**********************************************************************
     */

    @Override
    public void writeRaw(String value) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRaw(char value) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRaw(String value, int arg1, int arg2) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRaw(char[] value, int arg1, int arg2) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRawValue(String value) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRawValue(String value, int arg1, int arg2) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRawValue(char[] value, int arg1, int arg2) throws IOException {
        _reportNoRaw();
    }

    @Override
    public void writeRawUTF8String(byte[] text, int offset, int length) throws IOException {
        _reportNoRaw();
    }

    /*
    /**********************************************************************
    /* JsonGenerator implementation: write other types of values
    /**********************************************************************
     */

    @Override
    public void writeBinary(Base64Variant b64v, byte[] data, int offset, int length) throws IOException {
        _verifyValueWrite("write binary data");
        // no distinct variants for Ion; should produce plain binary
        _writer.writeBlob(data, offset, length);
    }

    @Override
    public void writeBoolean(boolean value) throws IOException {
        _verifyValueWrite("write boolean");
        _writer.writeBool(value);
    }

    @Override
    public void writeNull() throws IOException {
        _verifyValueWrite("write null");
        _writer.writeNull();
    }

    public void writeNull(IonType ionType) throws IOException {
        _verifyValueWrite("write null");
        _writer.writeNull(ionType);
    }

    @Override
    public void writeObject(Object pojo) throws IOException
    {
        if (pojo == null) {
            // important: call method that does check value write:
            writeNull();
        } else {
            // note: should NOT verify value write is ok; will be done indirectly by codec
            if (_objectCodec == null) {
                throw new IllegalStateException("No ObjectCodec defined for the generator, can not serialize regular Java objects");
            }
            _objectCodec.writeValue(this, pojo);
        }
    }

    public void writeValue(IonValue value) throws IOException {
        _verifyValueWrite("write ion value");
        if (value == null) {
            _writer.writeNull();
        } else {
            value.writeTo(_writer);
        }
    }

    public void writeValue(Timestamp value) throws IOException {
        _verifyValueWrite("write timestamp");
        if (value == null) {
            _writer.writeNull();
        } else {
            _writer.writeTimestamp(value);
        }
    }

    /*
    /**********************************************************************
    /* Methods base impl needs
    /**********************************************************************
     */

    @Override
    protected void _releaseBuffers() {
        // nothing to do here...
    }

    @Override
    protected void _verifyValueWrite(String msg) throws IOException
    {
        int status = _writeContext.writeValue();
        if (status == JsonWriteContext.STATUS_EXPECT_NAME) {
            _reportError("Can not "+msg+", expecting field name");
        }
        // 18-Feb-2021, tatu: as per [dataformats-binary#247], this does not work
        //   (Ion impl must do pretty-printing), so
        /*
        // Only additional work needed if we are pretty-printing
        if (_cfgPrettyPrinter != null) {
            // If we have a pretty printer, it knows what to do:
            switch (status) {
            case JsonWriteContext.STATUS_OK_AFTER_COMMA: // array
                _cfgPrettyPrinter.writeArrayValueSeparator(this);
                break;
            case JsonWriteContext.STATUS_OK_AFTER_COLON:
                _cfgPrettyPrinter.writeObjectFieldValueSeparator(this);
                break;
            case JsonWriteContext.STATUS_OK_AFTER_SPACE:
                _cfgPrettyPrinter.writeRootValueSeparator(this);
                break;
            case IonWriteContext.STATUS_OK_AFTER_SEXP_SEPARATOR:
                // Special handling of sexp value separators can be added later. Root value
                // separator will be whitespace which is sufficient to separate sexp values
                _cfgPrettyPrinter.writeRootValueSeparator(this);
                break;
            case JsonWriteContext.STATUS_OK_AS_IS:
                // First entry, but of which context?
                if (_writeContext.inArray()) {
                    _cfgPrettyPrinter.beforeArrayValues(this);
                } else if (_writeContext.inObject()) {
                    _cfgPrettyPrinter.beforeObjectEntries(this);
                } else if(((IonWriteContext) _writeContext).inSexp()) {
                    // Format sexps like arrays
                    _cfgPrettyPrinter.beforeArrayValues(this);
                }
                break;
            default:
                throw new IllegalStateException("Should never occur; status "+status);
            }
        }
        */
    }

    @Override
    public void writeEndArray() throws IOException {
        _writeContext = _writeContext.getParent();  // <-- copied from UTF8JsonGenerator
        _writer.stepOut();
    }

    @Override
    public void writeEndObject() throws IOException {
        _writeContext = _writeContext.getParent();  // <-- copied from UTF8JsonGenerator
        _writer.stepOut();
    }

    /**
     * @since 2.12.2
     */
    public void writeEndSexp() throws IOException {
        _writeContext = _writeContext.getParent();
        _writer.stepOut();
    }

    @Override
    public void writeFieldName(String value) throws IOException {
        //This call to _writeContext is copied from Jackson's UTF8JsonGenerator.writeFieldName(String)
        int status = _writeContext.writeFieldName(value);
        if (status == JsonWriteContext.STATUS_EXPECT_VALUE) {
            _reportError("Can not write a field name, expecting a value");
        }

        _writeFieldName(value);
    }

    protected void _writeFieldName(String value) throws IOException {
        //Even though this is a one-liner, putting it into a function "_writeFieldName"
        //to keep this code matching the factoring in Jackson's UTF8JsonGenerator.
        _writer.setFieldName(value);
    }

    @Override
    public void writeStartArray() throws IOException {
        _verifyValueWrite("start an array");                      // <-- copied from UTF8JsonGenerator
        _writeContext = _writeContext.createChildArrayContext();  // <-- copied from UTF8JsonGenerator
        streamWriteConstraints().validateNestingDepth(_writeContext.getNestingDepth());
        _writer.stepIn(IonType.LIST);
    }

    @Override
    public void writeStartObject() throws IOException {
        _verifyValueWrite("start an object");                      // <-- copied from UTF8JsonGenerator
        _writeContext = _writeContext.createChildObjectContext();  // <-- copied from UTF8JsonGenerator
        streamWriteConstraints().validateNestingDepth(_writeContext.getNestingDepth());
        _writer.stepIn(IonType.STRUCT);
    }

    /**
     * @since 2.12.2
     */
    public void writeStartSexp() throws IOException {
        _verifyValueWrite("start a sexp");
        _writeContext = ((IonWriteContext) _writeContext).createChildSexpContext();
        streamWriteConstraints().validateNestingDepth(_writeContext.getNestingDepth());
        _writer.stepIn(IonType.SEXP);
    }

    /*
    /**********************************************************************
    /* Support for type ids
    /**********************************************************************
     */

    @Override
    public void writeTypeId(Object rawId) throws IOException {
        if (rawId instanceof String[]) {
            String[] ids = (String[]) rawId;
            for (String id : ids) {
                annotateNextValue(id);
            }
        } else {
            annotateNextValue(String.valueOf(rawId));
        }
    }

    // Default impl should work fine here:
    // public WritableTypeId writeTypePrefix(WritableTypeId typeIdDef) throws IOException

    // Default impl should work fine here:
    // public WritableTypeId writeTypeSuffix(WritableTypeId typeIdDef) throws IOException

    /*
    /**********************************************************************
    /* Standard methods
    /**********************************************************************
     */

    @Override
    public String toString() {
        return "["+getClass().getSimpleName()+", Ion writer: "+_writer+"]";
    }

    /*
    /**********************************************************************
    /* Internal helper methods
    /**********************************************************************
     */

    protected void _reportNoRaw() throws IOException {
        throw new IOException("writeRaw() functionality not available with Ion backend");
    }
}
