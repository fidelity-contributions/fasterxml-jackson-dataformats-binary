package com.fasterxml.jackson.dataformat.cbor;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.DupDetector;
import com.fasterxml.jackson.core.util.JacksonFeatureSet;

import static com.fasterxml.jackson.dataformat.cbor.CBORConstants.*;

/**
 * {@link JsonGenerator} implementation that writes CBOR encoded content.
 *
 * @author Tatu Saloranta
 */
public class CBORGenerator extends GeneratorBase
{
    private final static int[] NO_INTS = new int[0];

    /**
     * Let's ensure that we have big enough output buffer because of safety
     * margins we need for UTF-8 encoding.
     */
    protected final static int BYTE_BUFFER_FOR_OUTPUT = 16000;

    /**
     * The replacement character to use to fix invalid Unicode sequences
     * (mismatched surrogate pair).
     *
     * @since 2.12
     */
    protected final static int REPLACEMENT_CHAR = 0xfffd;

    /**
     * Longest char chunk we will output is chosen so that it is guaranteed to
     * fit in an empty buffer even if everything encoded in 3-byte sequences;
     * but also fit two full chunks in case of single-byte (ascii) output.
     */
    private final static int MAX_LONG_STRING_CHARS = (BYTE_BUFFER_FOR_OUTPUT / 4) - 4;

    /**
     * This is the worst case length (in bytes) of maximum chunk we ever write.
     */
    private final static int MAX_LONG_STRING_BYTES = (MAX_LONG_STRING_CHARS * 3) + 3;

    /**
     * Enumeration that defines all togglable features for CBOR generator.
     */
    public enum Feature implements FormatFeature {
        /**
         * Feature that determines whether generator should try to use smallest
         * (size-wise) integer representation: if true, will use smallest
         * representation that is enough to retain value; if false, will use
         * length indicated by argument type (4-byte for <code>int</code>,
         * 8-byte for <code>long</code> and so on).
         */
        WRITE_MINIMAL_INTS(true),

        /**
         * Feature that determines whether CBOR "Self-Describe Tag" (value
         * 55799, encoded as 3-byte sequence of <code>0xD9, 0xD9, 0xF7</code>)
         * should be written at the beginning of document or not.
         * <p>
         * Default value is {@code false} meaning that type tag will not be
         * written at the beginning of a new document.
         *
         * @since 2.5
         */
        WRITE_TYPE_HEADER(false),

        /**
         * Feature that determines if an invalid surrogate encoding found in the
         * incoming String should fail with an exception or silently be output
         * as the Unicode 'REPLACEMENT CHARACTER' (U+FFFD) or not; if not,
         * an exception will be thrown to indicate invalid content.
         * <p>
         * Default value is {@code false} (for backwards compatibility) meaning that
         * an invalid surrogate will result in exception ({@link IllegalArgumentException}
         *
         * @since 2.12
         */
        LENIENT_UTF_ENCODING(false),

        /**
         * Feature that determines if string references are generated based on the
         * <a href="http://cbor.schmorp.de/stringref">stringref</a>) extension. This can save
         * storage space, parsing time, and pool string memory when parsing. Readers of the output
         * must also support the stringref extension to properly decode the data. Extra overhead may
         * be added to generation time and memory usage to compute the shared binary and text
         * strings.
         * <p>
         * Default value is {@code false} meaning that the stringref extension will not be used.
         *
         * @since 2.15
         */
        STRINGREF(false),

        /**
         * Feature that determines whether generator should try to write doubles
         * as floats: if {@code true}, will write a {@code double} as a 4-byte float if no
         * precision loss will occur; if {@code false}, will always write a {@code double}
         * as an 8-byte double.
         * <p>
         * Default value is {@code false} meaning that doubles will always be written as
         * 8-byte values.
         *
         * @since 2.15
         */
        WRITE_MINIMAL_DOUBLES(false),
        ;

        protected final boolean _defaultState;
        protected final int _mask;

        /**
         * Method that calculates bit set (flags) of all features that are
         * enabled by default.
         */
        public static int collectDefaults() {
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
        public boolean enabledByDefault() {
            return _defaultState;
        }

        @Override
        public boolean enabledIn(int flags) {
            return (flags & getMask()) != 0;
        }

        @Override
        public int getMask() {
            return _mask;
        }
    }

    /**
     * To simplify certain operations, we require output buffer length to allow
     * outputting of contiguous 256 character UTF-8 encoded String value. Length
     * of the longest UTF-8 code point (from Java char) is 3 bytes, and we need
     * both initial token byte and single-byte end marker so we get following
     * value.
     * <p>
     * Note: actually we could live with shorter one; absolute minimum would be
     * for encoding 64-character Strings.
     */
    private final static int MIN_BUFFER_LENGTH = (3 * 256) + 2;

    /**
     * Special value that is use to keep tracks of arrays and maps opened with infinite length
     */
    private final static int INDEFINITE_LENGTH = -2; // just to allow -1 as marker for "one too many"

    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */

    /**
     * @since 2.16
     */
    protected final StreamWriteConstraints _streamWriteConstraints;
    
    protected final  OutputStream _out;

    /**
     * Bit flag composed of bits that indicate which
     * {@link CBORGenerator.Feature}s are enabled.
     */
    protected int _formatFeatures;

    protected boolean _cfgMinimalInts;

    // @since 2.15
    protected boolean _cfgMinimalDoubles;

    /*
    /**********************************************************
    /* Output state
    /**********************************************************
     */

    // @since 2.10 (named _cborContext before 2.13)
    protected CBORWriteContext _streamWriteContext;

    /*
    /**********************************************************
    /* Output buffering
    /**********************************************************
     */

    /**
     * Intermediate buffer in which contents are buffered before being written
     * using {@link #_out}.
     */
    protected byte[] _outputBuffer;

    /**
     * Pointer to the next available byte in {@link #_outputBuffer}
     */
    protected int _outputTail = 0;

    /**
     * Offset to index after the last valid index in {@link #_outputBuffer}.
     * Typically same as length of the buffer.
     */
    protected final int _outputEnd;

    /**
     * Intermediate buffer in which characters of a String are copied before
     * being encoded.
     */
    protected char[] _charBuffer;

    protected final int _charBufferLength;

    /**
     * Let's keep track of how many bytes have been output, may prove useful
     * when debugging. This does <b>not</b> include bytes buffered in the output
     * buffer, just bytes that have been written using underlying stream writer.
     */
    protected int _bytesWritten;

    /*
    /**********************************************************
    /* Tracking of remaining elements to write
    /**********************************************************
     */

    protected int[] _elementCounts = NO_INTS;

    protected int _elementCountsPtr;

    /**
     * Number of elements remaining in the current complex structure (if any),
     * when writing defined-length Arrays, Objects; marker {code INDEFINITE_LENGTH}
     * otherwise.
     */
    protected int _currentRemainingElements = INDEFINITE_LENGTH;

    /*
    /**********************************************************
    /* Shared String detection
    /**********************************************************
     */

    /**
     * Flag that indicates whether the output buffer is recycable (and needs to
     * be returned to recycler once we are done) or not.
     */
    protected boolean _bufferRecyclable;

    /**
     * Table of previously referenced text and binary strings when the STRINGREF feature is used.
     * @since 2.15
     */
    protected HashMap<Object, Integer> _stringRefs;

    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */

    public CBORGenerator(IOContext ioCtxt, int stdFeatures, int formatFeatures,
            ObjectCodec codec, OutputStream out) {
        super(stdFeatures, codec, ioCtxt, /* Write Context */ null);
        DupDetector dups = JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION.enabledIn(stdFeatures)
                ? DupDetector.rootDetector(this)
                : null;
        // NOTE: we passed `null` for default write context
        _streamWriteContext = CBORWriteContext.createRootContext(dups);
        _formatFeatures = formatFeatures;
        _cfgMinimalInts = Feature.WRITE_MINIMAL_INTS.enabledIn(formatFeatures);
        _cfgMinimalDoubles = Feature.WRITE_MINIMAL_DOUBLES.enabledIn(formatFeatures);
        _streamWriteConstraints = ioCtxt.streamWriteConstraints();
        _out = out;
        _bufferRecyclable = true;
        _stringRefs = Feature.STRINGREF.enabledIn(formatFeatures) ? new HashMap<>() : null;
        _outputBuffer = ioCtxt.allocWriteEncodingBuffer(BYTE_BUFFER_FOR_OUTPUT);
        _outputEnd = _outputBuffer.length;
        _charBuffer = ioCtxt.allocConcatBuffer();
        _charBufferLength = _charBuffer.length;
        // let's just sanity check to prevent nasty odd errors
        if (_outputEnd < MIN_BUFFER_LENGTH) {
            throw new IllegalStateException("Internal encoding buffer length ("
                    + _outputEnd + ") too short, must be at least "
                    + MIN_BUFFER_LENGTH);
        }
    }

    /**
     * Alternative constructor that may be used to feed partially initialized content.
     *
     * @param outputBuffer
     *            Buffer to use for output before flushing to the underlying stream
     * @param offset
     *            Offset pointing past already buffered content; that is, number
     *            of bytes of valid content to output, within buffer.
     */
    public CBORGenerator(IOContext ioCtxt, int stdFeatures, int formatFeatures,
            ObjectCodec codec, OutputStream out, byte[] outputBuffer,
            int offset, boolean bufferRecyclable) {
        super(stdFeatures, codec, ioCtxt, /* Write Context */ null);
        DupDetector dups = JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION.enabledIn(stdFeatures)
                ? DupDetector.rootDetector(this)
                : null;
        // NOTE: we passed `null` for default write context
        _streamWriteContext = CBORWriteContext.createRootContext(dups);
        _formatFeatures = formatFeatures;
        _cfgMinimalInts = Feature.WRITE_MINIMAL_INTS.enabledIn(formatFeatures);
        _cfgMinimalDoubles = Feature.WRITE_MINIMAL_DOUBLES.enabledIn(formatFeatures);
        _streamWriteConstraints = ioCtxt.streamWriteConstraints();
        _out = out;
        _bufferRecyclable = bufferRecyclable;
        _outputTail = offset;
        _outputBuffer = outputBuffer;
        _stringRefs = Feature.STRINGREF.enabledIn(formatFeatures) ? new HashMap<>() : null;
        _outputEnd = _outputBuffer.length;
        _charBuffer = ioCtxt.allocConcatBuffer();
        _charBufferLength = _charBuffer.length;
        // let's just sanity check to prevent nasty odd errors
        if (_outputEnd < MIN_BUFFER_LENGTH) {
            throw new IllegalStateException("Internal encoding buffer length ("
                    + _outputEnd + ") too short, must be at least "
                    + MIN_BUFFER_LENGTH);
        }
    }

    /*
    /**********************************************************
    /* Versioned
    /**********************************************************
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************
    /* Capability introspection
    /**********************************************************
     */

    @Override
    public boolean canWriteBinaryNatively() {
        return true;
    }

    @Override // @since 2.12
    public JacksonFeatureSet<StreamWriteCapability> getWriteCapabilities() {
        return DEFAULT_BINARY_WRITE_CAPABILITIES;
    }

    /*
    /**********************************************************
    /* Overridden methods, configuration
    /**********************************************************
     */

    @Override
    public StreamWriteConstraints streamWriteConstraints() {
        return _streamWriteConstraints;
    }

    /**
     * No way (or need) to indent anything, so let's block any attempts. (should
     * we throw an exception instead?)
     */
    @Override
    public JsonGenerator useDefaultPrettyPrinter() {
        return this;
    }

    /**
     * No way (or need) to indent anything, so let's block any attempts. (should
     * we throw an exception instead?)
     */
    @Override
    public JsonGenerator setPrettyPrinter(PrettyPrinter pp) {
        return this;
    }

    @Override
    public Object getOutputTarget() {
        return _out;
    }

    @Override
    public int getOutputBuffered() {
        return _outputTail;
    }

    // public JsonParser overrideStdFeatures(int values, int mask)

    @Override
    public int getFormatFeatures() {
        return _formatFeatures;
    }

    @Override
    public JsonGenerator overrideStdFeatures(int values, int mask) {
        int oldState = _features;
        int newState = (oldState & ~mask) | (values & mask);
        if (oldState != newState) {
            _features = newState;
        }
        return this;
    }

    @Override
    public JsonGenerator overrideFormatFeatures(int values, int mask) {
        int oldState = _formatFeatures;
        int newState = (_formatFeatures & ~mask) | (values & mask);
        if (oldState != newState) {
            _formatFeatures = newState;
            _cfgMinimalInts = Feature.WRITE_MINIMAL_INTS.enabledIn(newState);
            _cfgMinimalDoubles = Feature.WRITE_MINIMAL_DOUBLES.enabledIn(newState);
        }
        return this;
    }

    /*
    /**********************************************************
    /* Overridden methods, output context (and related)
    /**********************************************************
     */

    @Override
    public JsonStreamContext getOutputContext() {
        return _streamWriteContext;
    }

    @Override // since 2.13
    public Object currentValue() {
        return _streamWriteContext.getCurrentValue();
    }

    @Override
    public void assignCurrentValue(Object v) {
        _streamWriteContext.setCurrentValue(v);
    }

    @Deprecated // since 2.17
    @Override
    public Object getCurrentValue() { return currentValue(); }

    @Deprecated // since 2.17
    @Override
    public void setCurrentValue(Object v) { assignCurrentValue(v); }

    /*
    /**********************************************************
    /* Extended API, configuration
    /**********************************************************
     */

    public CBORGenerator enable(Feature f) {
        _formatFeatures |= f.getMask();
        if (f == Feature.WRITE_MINIMAL_INTS) {
            _cfgMinimalInts = true;
        } else if (f == Feature.WRITE_MINIMAL_DOUBLES) {
            _cfgMinimalDoubles = true;
        }
        return this;
    }

    public CBORGenerator disable(Feature f) {
        _formatFeatures &= ~f.getMask();
        if (f == Feature.WRITE_MINIMAL_INTS) {
            _cfgMinimalInts = false;
        } else if (f == Feature.WRITE_MINIMAL_DOUBLES) {
            _cfgMinimalDoubles = false;
        }
        return this;
    }

    public final boolean isEnabled(Feature f) {
        return (_formatFeatures & f.getMask()) != 0;
    }

    public CBORGenerator configure(Feature f, boolean state) {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
        return this;
    }

    /*
    /**********************************************************
    /* Overridden methods, write methods
    /**********************************************************
     */

    /*
     * And then methods overridden to make final, streamline some aspects...
     */

    @Override
    public final void writeFieldName(String name) throws IOException {
        if (!_streamWriteContext.writeFieldName(name)) {
            _reportError("Can not write a field name, expecting a value");
        }
        _writeString(name);
    }

    @Override
    public final void writeFieldName(SerializableString name)
            throws IOException {
        // Object is a value, need to verify it's allowed
        if (!_streamWriteContext.writeFieldName(name.getValue())) {
            _reportError("Can not write a field name, expecting a value");
        }
        byte[] raw = name.asUnquotedUTF8();
        final int len = raw.length;
        if (len == 0) {
            _writeByte(BYTE_EMPTY_STRING);
            return;
        } else if (_stringRefs != null) {
            // Check for a string reference.
            String str = name.getValue();
            Integer index = _stringRefs.get(str);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            } else if (shouldReferenceString(_stringRefs.size(), len)) {
                _stringRefs.put(str, _stringRefs.size());
            }
        }
        _writeLengthMarker(PREFIX_TYPE_TEXT, len);
        _writeBytes(raw, 0, len);
    }

    @Override // since 2.8
    public final void writeFieldId(long id) throws IOException {
        if (!_streamWriteContext.writeFieldId(id)) {
            _reportError("Can not write a field id, expecting a value");
        }
        _writeLongNoCheck(id);
    }

    /*
    /**********************************************************
    /* Output method implementations, structural
    /**********************************************************
     */

    @Override
    public final void writeStartArray() throws IOException {
        _verifyValueWrite("start an array");
        _streamWriteContext = _streamWriteContext.createChildArrayContext(null);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        if (_elementCountsPtr > 0) {
            _pushRemainingElements();
        }
        _currentRemainingElements = INDEFINITE_LENGTH;
        _writeByte(BYTE_ARRAY_INDEFINITE);
    }

    @Override // since 2.12
    public void writeStartArray(Object forValue) throws IOException {
        _verifyValueWrite("start an array");
        _streamWriteContext = _streamWriteContext.createChildArrayContext(forValue);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        if (_elementCountsPtr > 0) {
            _pushRemainingElements();
        }
        _currentRemainingElements = INDEFINITE_LENGTH;
        _writeByte(BYTE_ARRAY_INDEFINITE);
    }

    /*
     * Unlike with JSON, this method is using slightly optimized version since
     * CBOR has a variant that allows embedding length in array start marker.
     */
    @Override // since 2.12
    public void writeStartArray(Object forValue, int elementsToWrite) throws IOException {
        _verifyValueWrite("start an array");
        _streamWriteContext = _streamWriteContext.createChildArrayContext(forValue);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        _pushRemainingElements();
        _currentRemainingElements = elementsToWrite;
        _writeLengthMarker(PREFIX_TYPE_ARRAY, elementsToWrite);
    }

    @Deprecated // since 2.12
    @Override
    public void writeStartArray(int elementsToWrite) throws IOException {
        _verifyValueWrite("start an array");
        _streamWriteContext = _streamWriteContext.createChildArrayContext(null);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        _pushRemainingElements();
        _currentRemainingElements = elementsToWrite;
        _writeLengthMarker(PREFIX_TYPE_ARRAY, elementsToWrite);
    }

    @Override
    public final void writeEndArray() throws IOException {
        if (!_streamWriteContext.inArray()) {
            _reportError("Current context not Array but "+_streamWriteContext.typeDesc());
        }
        closeComplexElement();
        _streamWriteContext = _streamWriteContext.getParent();
    }

    @Override
    public final void writeStartObject() throws IOException {
        _verifyValueWrite("start an object");
        _streamWriteContext = _streamWriteContext.createChildObjectContext(null);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        if (_elementCountsPtr > 0) {
            _pushRemainingElements();
        }
        _currentRemainingElements = INDEFINITE_LENGTH;
        _writeByte(BYTE_OBJECT_INDEFINITE);
    }

    @Override
    // since 2.8
    public final void writeStartObject(Object forValue) throws IOException {
        _verifyValueWrite("start an object");
        CBORWriteContext ctxt = _streamWriteContext.createChildObjectContext(forValue);
        streamWriteConstraints().validateNestingDepth(ctxt.getNestingDepth());
        _streamWriteContext = ctxt;
        if (_elementCountsPtr > 0) {
            _pushRemainingElements();
        }
        _currentRemainingElements = INDEFINITE_LENGTH;
        _writeByte(BYTE_OBJECT_INDEFINITE);
    }

    public final void writeStartObject(int elementsToWrite) throws IOException {
        writeStartObject(null, elementsToWrite);
    }

    @Override
    public void writeStartObject(Object forValue, int elementsToWrite) throws IOException {
        _verifyValueWrite("start an object");
        _streamWriteContext = _streamWriteContext.createChildObjectContext(forValue);
        streamWriteConstraints().validateNestingDepth(_streamWriteContext.getNestingDepth());
        _pushRemainingElements();
        _currentRemainingElements = elementsToWrite;
        _writeLengthMarker(PREFIX_TYPE_OBJECT, elementsToWrite);
    }

    @Override
    public final void writeEndObject() throws IOException {
        if (!_streamWriteContext.inObject()) {
            _reportError("Current context not Object but "+ _streamWriteContext.typeDesc());
        }
        closeComplexElement();
        _streamWriteContext = _streamWriteContext.getParent();
    }

    @Override // since 2.8
    public void writeArray(int[] array, int offset, int length) throws IOException
    {
        _verifyOffsets(array.length, offset, length);
        // short-cut, do not create child array context etc
        _verifyValueWrite("write int array");
        _writeLengthMarker(PREFIX_TYPE_ARRAY, length);

        if (_cfgMinimalInts) {
            for (int i = offset, end = offset+length; i < end; ++i) {
                final int value = array[i];
                if (value < 0) {
                    _writeIntMinimal(PREFIX_TYPE_INT_NEG, -value - 1);
                } else {
                    _writeIntMinimal(PREFIX_TYPE_INT_POS, value);
                }
            }
        } else {
            for (int i = offset, end = offset+length; i < end; ++i) {
                final int value = array[i];
                if (value < 0) {
                    _writeIntFull(PREFIX_TYPE_INT_NEG, -value - 1);
                } else {
                    _writeIntFull(PREFIX_TYPE_INT_POS, value);
                }
            }
        }
    }

    @Override // since 2.8
    public void writeArray(long[] array, int offset, int length) throws IOException
    {
        _verifyOffsets(array.length, offset, length);
        // short-cut, do not create child array context etc
        _verifyValueWrite("write int array");
        _writeLengthMarker(PREFIX_TYPE_ARRAY, length);
        for (int i = offset, end = offset+length; i < end; ++i) {
            _writeLongNoCheck(array[i]);
        }
    }

    @Override // since 2.8
    public void writeArray(double[] array, int offset, int length) throws IOException
    {
        _verifyOffsets(array.length, offset, length);
        // short-cut, do not create child array context etc
        _verifyValueWrite("write int array");
        _writeLengthMarker(PREFIX_TYPE_ARRAY, length);
        if (_cfgMinimalDoubles) {
            for (int i = offset, end = offset+length; i < end; ++i) {
                _writeDoubleMinimal(array[i]);
            }
        } else {
            for (int i = offset, end = offset+length; i < end; ++i) {
                _writeDoubleNoCheck(array[i]);
            }
        }
    }

    // @since 2.8.8
    private final void _pushRemainingElements() {
        if (_elementCounts.length == _elementCountsPtr) { // initially, as well as if full
            _elementCounts = Arrays.copyOf(_elementCounts, _elementCounts.length+10);
        }
        _elementCounts[_elementCountsPtr++] = _currentRemainingElements;
    }

    private final void _writeIntMinimal(int markerBase, int i) throws IOException
    {
        _ensureRoomForOutput(5);
        byte b0;
        if (i >= 0) {
            if (i < 24) {
                _outputBuffer[_outputTail++] = (byte) (markerBase + i);
                return;
            }
            if (i <= 0xFF) {
                _outputBuffer[_outputTail++] = (byte) (markerBase + SUFFIX_UINT8_ELEMENTS);
                _outputBuffer[_outputTail++] = (byte) i;
                return;
            }
            b0 = (byte) i;
            i >>= 8;
            if (i <= 0xFF) {
                _outputBuffer[_outputTail++] = (byte) (markerBase + SUFFIX_UINT16_ELEMENTS);
                _outputBuffer[_outputTail++] = (byte) i;
                _outputBuffer[_outputTail++] = b0;
                return;
            }
        } else {
            b0 = (byte) i;
            i >>= 8;
        }
        _outputBuffer[_outputTail++] = (byte) (markerBase + SUFFIX_UINT32_ELEMENTS);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        _outputBuffer[_outputTail++] = b0;
    }

    private final void _writeIntFull(int markerBase, int i) throws IOException
    {
        // if ((_outputTail + needed) >= _outputEnd) { _flushBuffer(); }
        _ensureRoomForOutput(5);

        _outputBuffer[_outputTail++] = (byte) (markerBase + SUFFIX_UINT32_ELEMENTS);
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    // Helper method that works like `writeNumber(long)` but DOES NOT
    // check internal output state. It does, however, check need for minimization
    private final void _writeLongNoCheck(long l) throws IOException
    {
        if (_cfgMinimalInts) {
            if (l >= 0) {
                // 31-Mar-2021, tatu: [dataformats-cbor#269] Incorrect boundary check,
                //     was off by one, resulting in truncation to 0
                if (l < 0x100000000L) {
                    _writeIntMinimal(PREFIX_TYPE_INT_POS, (int) l);
                    return;
                }
            } else if (l >= -0x100000000L) {
                _writeIntMinimal(PREFIX_TYPE_INT_NEG, (int) (-l - 1));
                return;
            }
        }
        _ensureRoomForOutput(9);
        if (l < 0L) {
            l += 1;
            l = -l;
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_NEG + SUFFIX_UINT64_ELEMENTS);
        } else {
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_POS + SUFFIX_UINT64_ELEMENTS);
        }
        int i = (int) (l >> 32);
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        i = (int) l;
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    private final void _writeFloatNoCheck(float f) throws IOException {
        _ensureRoomForOutput(5);
        /*
         * 17-Apr-2010, tatu: could also use 'floatToIntBits', but it seems more
         * accurate to use exact representation; and possibly faster. However,
         * if there are cases where collapsing of NaN was needed (for non-Java
         * clients), this can be changed
         */
        int i = Float.floatToRawIntBits(f);
        _outputBuffer[_outputTail++] = BYTE_FLOAT32;
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    private final void _writeDoubleNoCheck(double d) throws IOException {
        _ensureRoomForOutput(9);
        // 17-Apr-2010, tatu: could also use 'doubleToIntBits', but it seems
        // more accurate to use exact representation; and possibly faster.
        // However, if there are cases where collapsing of NaN was needed (for
        // non-Java clients), this can be changed
        long l = Double.doubleToRawLongBits(d);
        _outputBuffer[_outputTail++] = BYTE_FLOAT64;

        int i = (int) (l >> 32);
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        i = (int) l;
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    private final void _writeDoubleMinimal(double d) throws IOException {
        float f = (float)d;
        if (f == d) {
            _writeFloatNoCheck(f);
        } else {
            _writeDoubleNoCheck(d);
        }
    }

    /*
    /***********************************************************
    /* Output method implementations, textual
    /***********************************************************
     */

    @Override
    public void writeString(String text) throws IOException {
        if (text == null) {
            writeNull();
            return;
        }
        _verifyValueWrite("write String value");
        _writeString(text);
    }

    @Override
    public final void writeString(SerializableString sstr) throws IOException {
        _verifyValueWrite("write String value");
        byte[] raw = sstr.asUnquotedUTF8();
        final int len = raw.length;
        if (len == 0) {
            _writeByte(BYTE_EMPTY_STRING);
            return;
        } else if (_stringRefs != null) {
            // Check for a string reference.
            String str = sstr.getValue();
            Integer index = _stringRefs.get(str);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            } else if (shouldReferenceString(_stringRefs.size(), len)) {
                _stringRefs.put(str, _stringRefs.size());
            }
        }
        _writeLengthMarker(PREFIX_TYPE_TEXT, len);
        _writeBytes(raw, 0, len);
    }

    @Override
    public void writeString(char[] text, int offset, int len)
            throws IOException {
        _verifyValueWrite("write String value");
        String str = null;
        if (len == 0) {
            _writeByte(BYTE_EMPTY_STRING);
            return;
        } else if (_stringRefs != null && len <= MAX_LONG_STRING_CHARS) {
            // Check for a string reference.
            str = new String(text, offset, len);
            Integer index = _stringRefs.get(str);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            }
        }
        int actual = _writeString(text, offset, len);
        if (str != null && shouldReferenceString(_stringRefs.size(), actual)) {
            _stringRefs.put(str, _stringRefs.size());
        }
    }

    @Override
    public void writeRawUTF8String(byte[] raw, int offset, int len)
            throws IOException
    {
        _verifyValueWrite("write String value");
        if (len == 0) {
            _writeByte(BYTE_EMPTY_STRING);
            return;
        } else if (_stringRefs != null) {
            // Check for a string reference.
            String str = new String(raw, offset, len, StandardCharsets.UTF_8);
            Integer index = _stringRefs.get(str);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            } else if (shouldReferenceString(_stringRefs.size(), len)) {
                _stringRefs.put(str, _stringRefs.size());
            }
        }
        _writeLengthMarker(PREFIX_TYPE_TEXT, len);
        _writeBytes(raw, offset, len);
    }

    @Override
    public final void writeUTF8String(byte[] text, int offset, int len)
            throws IOException {
        // Since no escaping is needed, same as 'writeRawUTF8String'
        writeRawUTF8String(text, offset, len);
    }

    /*
    /**********************************************************
    /* Output method implementations, unprocessed ("raw")
    /**********************************************************
     */

    @Override
    public void writeRaw(String text) throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRaw(String text, int offset, int len) throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRaw(char[] text, int offset, int len) throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRaw(char c) throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRawValue(String text) throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRawValue(String text, int offset, int len)
            throws IOException {
        throw _notSupported();
    }

    @Override
    public void writeRawValue(char[] text, int offset, int len)
            throws IOException {
        throw _notSupported();
    }

    /*
     * /********************************************************** /* Output
     * method implementations, base64-encoded binary
     * /**********************************************************
     */

    @Override
    public void writeBinary(Base64Variant b64variant, byte[] data, int offset,
            int len) throws IOException {
        if (data == null) {
            writeNull();
            return;
        }
        _verifyValueWrite("write Binary value");
        ByteBuffer bytesRef = null;
        if (_stringRefs != null) {
            bytesRef = ByteBuffer.wrap(data, offset, len);
            Integer index = _stringRefs.get(bytesRef);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            }
        }

        _writeLengthMarker(PREFIX_TYPE_BYTES, len);
        _writeBytes(data, offset, len);

        if (bytesRef != null && shouldReferenceString(_stringRefs.size(), len)) {
            // Store a copy of the data to ensure that modifications don't corrupt the lookup table.
            _stringRefs.put(ByteBuffer.wrap(Arrays.copyOfRange(data, offset, len)),
                    _stringRefs.size());
        }
    }

    @Override
    public int writeBinary(InputStream data, int dataLength) throws IOException {
        /*
         * 28-Mar-2014, tatu: Theoretically we could implement encoder that uses
         * chunking to output binary content of unknown (a priori) length. But
         * for no let's require knowledge of length, for simplicity: may be
         * revisited in future.
         */
        if (dataLength < 0) {
            throw new UnsupportedOperationException(
                    "Must pass actual length for CBOR encoded data");
        }
        _verifyValueWrite("write Binary value");
        int missing;

        if (_stringRefs == null) {
            _writeLengthMarker(PREFIX_TYPE_BYTES, dataLength);
            missing = _writeBytes(data, dataLength);
        } else {
            // When computing string references must have the data available ahead of time.
            byte[] bytes = new byte[dataLength];
            missing = dataLength - data.read(bytes);
            if (missing == 0) {
                ByteBuffer bytesRef = ByteBuffer.wrap(bytes);
                Integer index = _stringRefs.get(bytesRef);
                if (index != null) {
                    writeTag(TAG_ID_STRINGREF);
                    _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                } else {
                    _writeLengthMarker(PREFIX_TYPE_BYTES, dataLength);
                    _writeBytes(bytes, 0, dataLength);
                    if (shouldReferenceString(_stringRefs.size(), dataLength)) {
                        _stringRefs.put(bytesRef, _stringRefs.size());
                    }
                }
            }
        }

        if (missing > 0) {
            _reportError("Too few bytes available: missing " + missing
                    + " bytes (out of " + dataLength + ")");
        }
        return dataLength;
    }

    @Override
    public int writeBinary(Base64Variant b64variant, InputStream data,
            int dataLength) throws IOException {
        return writeBinary(data, dataLength);
    }

    /*
    /**********************************************************
    /* Output method implementations, primitive
    /**********************************************************
     */

    @Override
    public void writeBoolean(boolean state) throws IOException {
        _verifyValueWrite("write boolean value");
        if (state) {
            _writeByte(BYTE_TRUE);
        } else {
            _writeByte(BYTE_FALSE);
        }
    }

    @Override
    public void writeNull() throws IOException {
        _verifyValueWrite("write null value");
        _writeByte(BYTE_NULL);
    }

    @Override
    public void writeNumber(int i) throws IOException {
        _verifyValueWrite("write number");
        int marker;
        if (i < 0) {
            i = -i - 1;
            marker = PREFIX_TYPE_INT_NEG;
        } else {
            marker = PREFIX_TYPE_INT_POS;
        }
        _ensureRoomForOutput(5);
        byte b0;
        if (_cfgMinimalInts) {
            if (i < 24) {
                _outputBuffer[_outputTail++] = (byte) (marker + i);
                return;
            }
            if (i <= 0xFF) {
                _outputBuffer[_outputTail++] = (byte) (marker + SUFFIX_UINT8_ELEMENTS);
                _outputBuffer[_outputTail++] = (byte) i;
                return;
            }
            b0 = (byte) i;
            i >>= 8;
            if (i <= 0xFF) {
                _outputBuffer[_outputTail++] = (byte) (marker + SUFFIX_UINT16_ELEMENTS);
                _outputBuffer[_outputTail++] = (byte) i;
                _outputBuffer[_outputTail++] = b0;
                return;
            }
        } else {
            b0 = (byte) i;
            i >>= 8;
        }
        _outputBuffer[_outputTail++] = (byte) (marker + SUFFIX_UINT32_ELEMENTS);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        _outputBuffer[_outputTail++] = b0;
    }

    @Override
    public void writeNumber(long l) throws IOException {
        _verifyValueWrite("write number");
        if (_cfgMinimalInts) { // maybe 32 bits is enough?
            if (l >= 0) {
                // 31-Mar-2021, tatu: [dataformats-cbor#269] Incorrect boundary check,
                //     was off by one, resulting in truncation to 0
                if (l < 0x100000000L) {
                    _writeIntMinimal(PREFIX_TYPE_INT_POS, (int) l);
                    return;
                }
            } else if (l >= -0x100000000L) {
                _writeIntMinimal(PREFIX_TYPE_INT_NEG, (int) (-l - 1));
                return;
            }
        }
        _ensureRoomForOutput(9);
        if (l < 0L) {
            l += 1;
            l = -l;
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_NEG + SUFFIX_UINT64_ELEMENTS);
        } else {
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_POS + SUFFIX_UINT64_ELEMENTS);
        }
        int i = (int) (l >> 32);
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        i = (int) l;
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    @Override
    public void writeNumber(BigInteger v) throws IOException {
        if (v == null) {
            writeNull();
            return;
        }
        _verifyValueWrite("write number");
        _write(v);
    }

    // Main write method isolated so that it can be called directly
    // in cases where that is needed (to encode BigDecimal)
    protected void _write(BigInteger v) throws IOException {
        /*
         * Supported by using type tags, as per spec: major type for tag '6'; 5
         * LSB either 2 for positive bignum or 3 for negative bignum. And then
         * byte sequence that encode variable length integer.
         */
        if (v.signum() < 0) {
            _writeByte(BYTE_TAG_BIGNUM_NEG);
            v = v.negate();
        } else {
            _writeByte(BYTE_TAG_BIGNUM_POS);
        }
        byte[] data = v.toByteArray();
        final int len = data.length;
        if (_stringRefs == null) {
            _writeLengthMarker(PREFIX_TYPE_BYTES, len);
            _writeBytes(data, 0, len);
        } else {
            ByteBuffer bytesRef = ByteBuffer.wrap(data);
            Integer index = _stringRefs.get(bytesRef);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
            } else {
                _writeLengthMarker(PREFIX_TYPE_BYTES, len);
                _writeBytes(data, 0, len);
                if (shouldReferenceString(_stringRefs.size(), len)) {
                    _stringRefs.put(bytesRef, _stringRefs.size());
                }
            }
        }
    }

    @Override
    public void writeNumber(double d) throws IOException {
        _verifyValueWrite("write number");
        if (_cfgMinimalDoubles) {
            _writeDoubleMinimal(d);
        } else {
            _writeDoubleNoCheck(d);
        }
    }

    @Override
    public void writeNumber(float f) throws IOException {
        _verifyValueWrite("write number");
        _writeFloatNoCheck(f);
    }

    @Override
    public void writeNumber(BigDecimal dec) throws IOException {
        if (dec == null) {
            writeNull();
            return;
        }
        _verifyValueWrite("write number");
        /* Supported by using type tags, as per spec: major type for tag '6'; 5
         * LSB 4. And then a two-element array; integer exponent, and int/bigint
         * mantissa
         */
        // 12-May-2016, tatu: Before 2.8, used "bigfloat", but that was
        // incorrect...
        _writeByte(BYTE_TAG_DECIMAL_FRACTION);
        _writeByte(BYTE_ARRAY_2_ELEMENTS);

        // 27-Nov-2019, tatu: As per [dataformats-binary#139] need to change sign here
        int scale = dec.scale();
        _writeIntValue(-scale);
        // Hmmmh. Specification suggest use of regular integer for mantissa. But
        // if it doesn't fit, use "bignum"
        BigInteger unscaled = dec.unscaledValue();
        int bitLength = unscaled.bitLength();
        if (bitLength <= 31) {
            _writeIntValue(unscaled.intValue());
        } else if (bitLength <= 63) {
            _writeLongValue(unscaled.longValue());
        } else {
            _write(unscaled);
        }
    }

    @Override
    public void writeNumber(String encodedValue) throws IOException,
            JsonGenerationException, UnsupportedOperationException {
        // just write as a String -- CBOR does not require schema, so
        // databinding
        // on receiving end should be able to coerce it appropriately
        writeString(encodedValue);
    }

    /*
    /**********************************************************
    /* Implementations for other methods
    /**********************************************************
     */

    @Override
    protected final void _verifyValueWrite(String typeMsg) throws IOException {
        if (!_streamWriteContext.writeValue()) {
            _reportError("Can not " + typeMsg + ", expecting field name/id");
        }
        // decrementElementsRemainingCount()
        int count = _currentRemainingElements;
        if (count != INDEFINITE_LENGTH) {
            --count;

            // 28-Jun-2016, tatu: _Should_ check overrun immediately (instead of waiting
            //    for end of Object/Array), but has 10% performance penalty for some reason,
            //    should figure out why and how to avoid
            if (count < 0) {
                _failSizedArrayOrObject();
                return; // never gets here
            }
            _currentRemainingElements = count;
        }
    }

    private void _failSizedArrayOrObject() throws IOException
    {
        _reportError(String.format("%s size mismatch: number of element encoded is not equal to reported array/map size.",
                _streamWriteContext.typeDesc()));
    }

    /*
    /**********************************************************
    /* Low-level output handling
    /**********************************************************
     */

    @Override
    public final void flush() throws IOException {
        _flushBuffer();
        if (isEnabled(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)) {
            _out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (!isClosed()) {
            // First: let's see that we still have buffers...
            if ((_outputBuffer != null)
                    && isEnabled(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT)) {
                while (true) {
                    JsonStreamContext ctxt = getOutputContext();
                    if (ctxt.inArray()) {
                        writeEndArray();
                    } else if (ctxt.inObject()) {
                        writeEndObject();
                    } else {
                        break;
                    }
                }
            }
            _flushBuffer();

            if (_ioContext.isResourceManaged()
                    || isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
                _out.close();
            } else if (isEnabled(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)) {
                // 14-Jan-2019, tatu: [dataformats-binary#155]: unless prevented via feature
                // If we can't close it, we should at least flush
                _out.flush();
            }
            // Internal buffer(s) generator has can now be released as well
            _releaseBuffers();
            super.close();
        }
    }

    /*
    /**********************************************************
     * Extended API, CBOR-specific encoded output
    /**********************************************************
     */

    /**
     * Method for writing out an explicit CBOR Tag.
     *
     * @param tagId
     *            Positive integer (0 or higher)
     *
     * @since 2.5
     */
    public void writeTag(int tagId) throws IOException {
        if (tagId < 0) {
            throw new IllegalArgumentException(
                    "Can not write negative tag ids (" + tagId + ")");
        }
        _writeLengthMarker(PREFIX_TYPE_TAG, tagId);
    }

    /*
    /**********************************************************
    /* Extended API, raw bytes (by-passing encoder)
    /**********************************************************
     */

    /**
     * Method for directly inserting specified byte in output at current
     * position.
     * <p>
     * NOTE: only use this method if you really know what you are doing.
     */
    public void writeRaw(byte b) throws IOException {
        _writeByte(b);
    }

    /**
     * Method for directly inserting specified bytes in output at current
     * position.
     * <p>
     * NOTE: only use this method if you really know what you are doing.
     */
    public void writeBytes(byte[] data, int offset, int len) throws IOException {
        _writeBytes(data, offset, len);
    }

    /*
    /**********************************************************
    /* Internal methods: low-level text output
    /**********************************************************
     */

    private final static int MAX_SHORT_STRING_CHARS = 23;
    // in case it's > 23 bytes
    private final static int MAX_SHORT_STRING_BYTES = 23 * 3 + 2;

    private final static int MAX_MEDIUM_STRING_CHARS = 255;
    // in case it's > 255 bytes
    private final static int MAX_MEDIUM_STRING_BYTES = 255 * 3 + 3;

    protected final void _writeString(String name) throws IOException {
        int len = name.length();
        if (len == 0) {
            _writeByte(BYTE_EMPTY_STRING);
            return;
        }

        // Check if this is a previously referenced string. This will only be done for strings that
        // have a definite length.
        if (_stringRefs != null && len <= MAX_LONG_STRING_CHARS) {
            Integer index = _stringRefs.get(name);
            if (index != null) {
                writeTag(TAG_ID_STRINGREF);
                _writeIntMinimal(PREFIX_TYPE_INT_POS, index);
                return;
            }
        }

        // Actually, let's not bother with copy for shortest strings
        if (len <= MAX_SHORT_STRING_CHARS) {
            _ensureSpace(MAX_SHORT_STRING_BYTES); // can afford approximate length
            int actual = _encode(_outputTail + 1, name, len);
            // Store reference for later if valid to do so.
            if (_stringRefs != null && shouldReferenceString(_stringRefs.size(), actual)) {
                _stringRefs.put(name, _stringRefs.size());
            }
            final byte[] buf = _outputBuffer;
            int ix = _outputTail;
            if (actual <= MAX_SHORT_STRING_CHARS) { // fits in prefix byte
                buf[ix++] = (byte) (PREFIX_TYPE_TEXT + actual);
                _outputTail = ix + actual;
                return;
            }
            // no, have to move. Blah.
            System.arraycopy(buf, ix + 1, buf, ix + 2, actual);
            buf[ix++] = BYTE_STRING_1BYTE_LEN;
            buf[ix++] = (byte) actual;
            _outputTail = ix + actual;
            return;
        }

        char[] cbuf = _charBuffer;
        if (len > cbuf.length) {
            _charBuffer = cbuf = new char[Math
                    .max(_charBuffer.length + 32, len)];
        }
        name.getChars(0, len, cbuf, 0);
        int actual = _writeString(cbuf, 0, len);
        // Store reference for later if valid to do so. Actual length will be negative if an
        // indefinite length string was written.
        if (actual >= 0 && _stringRefs != null &&
                shouldReferenceString(_stringRefs.size(), actual)) {
            _stringRefs.put(name, _stringRefs.size());
        }
    }

    protected final void _ensureSpace(int needed) throws IOException {
        if ((_outputTail + needed + 3) > _outputEnd) {
            _flushBuffer();
        }
    }

    protected final int _writeString(char[] text, int offset, int len)
            throws IOException
    {
        if (len <= MAX_SHORT_STRING_CHARS) { // possibly short string (not necessarily)
            _ensureSpace(MAX_SHORT_STRING_BYTES); // can afford approximate length
            int actual = _encode(_outputTail + 1, text, offset, offset + len);
            final byte[] buf = _outputBuffer;
            int ix = _outputTail;
            if (actual <= MAX_SHORT_STRING_CHARS) { // fits in prefix byte
                buf[ix++] = (byte) (PREFIX_TYPE_TEXT + actual);
                _outputTail = ix + actual;
                return actual;
            }
            // no, have to move. Blah.
            System.arraycopy(buf, ix + 1, buf, ix + 2, actual);
            buf[ix++] = BYTE_STRING_1BYTE_LEN;
            buf[ix++] = (byte) actual;
            _outputTail = ix + actual;
            return actual;
        }
        if (len <= MAX_MEDIUM_STRING_CHARS) {
            _ensureSpace(MAX_MEDIUM_STRING_BYTES); // short enough, can approximate
            int actual = _encode(_outputTail + 2, text, offset, offset + len);
            final byte[] buf = _outputBuffer;
            int ix = _outputTail;
            if (actual <= MAX_MEDIUM_STRING_CHARS) { // fits as expected
                buf[ix++] = BYTE_STRING_1BYTE_LEN;
                buf[ix++] = (byte) actual;
                _outputTail = ix + actual;
                return actual;
            }
            // no, have to move. Blah.
            System.arraycopy(buf, ix + 2, buf, ix + 3, actual);
            buf[ix++] = BYTE_STRING_2BYTE_LEN;
            buf[ix++] = (byte) (actual >> 8);
            buf[ix++] = (byte) actual;
            _outputTail = ix + actual;
            return actual;
        }
        if (len <= MAX_LONG_STRING_CHARS) { // no need to chunk yet
            // otherwise, long but single chunk
            _ensureSpace(MAX_LONG_STRING_BYTES); // calculate accurate length to
                                                 // avoid extra flushing
            int ix = _outputTail;
            int actual = _encode(ix + 3, text, offset, offset + len);
            final byte[] buf = _outputBuffer;
            buf[ix++] = BYTE_STRING_2BYTE_LEN;
            buf[ix++] = (byte) (actual >> 8);
            buf[ix++] = (byte) actual;
            _outputTail = ix + actual;
            return actual;
        }
        _writeChunkedString(text, offset, len);
        return -1;
    }

    protected final void _writeChunkedString(char[] text, int offset, int len)
        throws IOException
    {
        // need to use a marker first
        _writeByte(BYTE_STRING_INDEFINITE);

        while (len > MAX_LONG_STRING_CHARS) {
            _ensureSpace(MAX_LONG_STRING_BYTES); // marker and single-byte length?
            int ix = _outputTail;
            int amount = MAX_LONG_STRING_CHARS;

            // 23-May-2016, tatu: Make sure NOT to try to split surrogates in half
            int end = offset + amount;
            char c = text[end-1];
            if (c >= SURR1_FIRST && c <= SURR1_LAST) {
                --end;
                --amount;
            }
            int actual = _encode(_outputTail + 3, text, offset, end);
            final byte[] buf = _outputBuffer;
            buf[ix++] = BYTE_STRING_2BYTE_LEN;
            buf[ix++] = (byte) (actual >> 8);
            buf[ix++] = (byte) actual;
            _outputTail = ix + actual;
            offset += amount;
            len -= amount;
        }
        // and for the last chunk, just use recursion
        if (len > 0) {
            _writeString(text, offset, len);
        }
        // plus end marker
        _writeByte(BYTE_BREAK);
    }

    /*
    /**********************************************************
    /* Internal methods, UTF-8 encoding
    /**********************************************************
     */

    /**
     * Helper method called when the whole character sequence is known to fit in
     * the output buffer regardless of UTF-8 expansion.
     */
    private final int _encode(int outputPtr, char[] str, int i, int end) throws IOException
    {
        // First: let's see if it's all ASCII: that's rather fast
        final byte[] outBuf = _outputBuffer;
        final int outputStart = outputPtr;
        do {
            int c = str[i];
            if (c > 0x7F) {
                return _shortUTF8Encode2(str, i, end, outputPtr, outputStart);
            }
            outBuf[outputPtr++] = (byte) c;
        } while (++i < end);
        return outputPtr - outputStart;
    }

    /**
     * Helper method called when the whole character sequence is known to fit in
     * the output buffer, but not all characters are single-byte (ASCII)
     * characters.
     */
    private final int _shortUTF8Encode2(char[] str, int i, int end,
            int outputPtr, int outputStart) throws IOException
    {
        final byte[] outBuf = _outputBuffer;
        while (i < end) {
            int c = str[i++];
            if (c <= 0x7F) {
                outBuf[outputPtr++] = (byte) c;
                continue;
            }
            // Nope, multi-byte:
            if (c < 0x800) { // 2-byte
                outBuf[outputPtr++] = (byte) (0xc0 | (c >> 6));
                outBuf[outputPtr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // 3 or 4 bytes (surrogate)
            if (c < SURR1_FIRST || c > SURR2_LAST) { // regular 3-byte character
                outBuf[outputPtr++] = (byte) (0xe0 | (c >> 12));
                outBuf[outputPtr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                outBuf[outputPtr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // Yup, looks like a surrogate pair... but is it?
            if ((c <= SURR1_LAST) && (i < end)) { // must be from first range and have another char
                final int d = str[i];
                if ((d <= SURR2_LAST) && (d >= SURR2_FIRST)) {
                    ++i;
                    outputPtr = _decodeAndWriteSurrogate(c, d, outBuf, outputPtr);
                    continue;
                }
                outputPtr = _invalidSurrogateEnd(c, d, outBuf, outputPtr);
                continue;
            }
            // Nah, something wrong
            outputPtr = _invalidSurrogateStart(c, outBuf, outputPtr);
        }
        return (outputPtr - outputStart);
    }

    private final int _encode(int outputPtr, String str, int len) throws IOException {
        final byte[] outBuf = _outputBuffer;
        final int outputStart = outputPtr;

        for (int i = 0; i < len; ++i) {
            int c = str.charAt(i);
            if (c > 0x7F) {
                return _encode2(i, outputPtr, str, len, outputStart);
            }
            outBuf[outputPtr++] = (byte) c;
        }
        return (outputPtr - outputStart);
    }

    private final int _encode2(int i, int outputPtr, String str, int len,
            int outputStart) throws IOException
    {
        final byte[] outBuf = _outputBuffer;
        // no; non-ASCII stuff, slower loop
        while (i < len) {
            int c = str.charAt(i++);
            if (c <= 0x7F) {
                outBuf[outputPtr++] = (byte) c;
                continue;
            }
            // Nope, multi-byte:
            if (c < 0x800) { // 2-byte
                outBuf[outputPtr++] = (byte) (0xc0 | (c >> 6));
                outBuf[outputPtr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // 3 or 4 bytes (surrogate)
            if (c < SURR1_FIRST || c > SURR2_LAST) { // regular 3-byte character
                outBuf[outputPtr++] = (byte) (0xe0 | (c >> 12));
                outBuf[outputPtr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                outBuf[outputPtr++] = (byte) (0x80 | (c & 0x3f));
                continue;
            }
            // Yup, looks like a surrogate pair... but is it?
            if ((c <= SURR1_LAST) && (i < len)) { // must be from first range and have another char
                final int d = str.charAt(i);
                if ((d <= SURR2_LAST) && (d >= SURR2_FIRST)) {
                    ++i;
                    outputPtr = _decodeAndWriteSurrogate(c, d, outBuf, outputPtr);
                    continue;
                }
                outputPtr = _invalidSurrogateEnd(c, d, outBuf, outputPtr);
                continue;
            }
            // Nah, something wrong
            outputPtr = _invalidSurrogateStart(c, outBuf, outputPtr);
        }
        return (outputPtr - outputStart);
    }

    private int _invalidSurrogateStart(int code, byte[] outBuf, int outputPtr)
        throws IOException
    {
        if (isEnabled(Feature.LENIENT_UTF_ENCODING)) {
            return _appendReplacementChar(outBuf, outputPtr);
        }
        // Will be called in two distinct cases: either first character is
        // invalid (code range of second part), or first character is valid
        // but there is no second part to encode
        if (code <= SURR1_LAST) {
            // Unmatched first part (closing without second part?)
            _reportError(String.format(
"Unmatched surrogate pair, starts with valid high surrogate (0x%04X) but ends without low surrogate",
code));
        }
        _reportError(String.format(
"Invalid surrogate pair, starts with invalid high surrogate (0x%04X), not in valid range [0xD800, 0xDBFF]",
code));
        return 0; // never gets here
    }

    private int _invalidSurrogateEnd(int surr1, int surr2,
            byte[] outBuf, int outputPtr)
        throws IOException
    {
        if (isEnabled(Feature.LENIENT_UTF_ENCODING)) {
            return _appendReplacementChar(outBuf, outputPtr);
        }
        _reportError(String.format(
"Invalid surrogate pair, starts with valid high surrogate (0x%04X)"
+" but ends with invalid low surrogate (0x%04X), not in valid range [0xDC00, 0xDFFF]",
surr1, surr2));
        return 0; // never gets here
    }

    private int _appendReplacementChar(byte[] outBuf, int outputPtr) {
        outBuf[outputPtr++] = (byte) (0xe0 | (REPLACEMENT_CHAR >> 12));
        outBuf[outputPtr++] = (byte) (0x80 | ((REPLACEMENT_CHAR >> 6) & 0x3f));
        outBuf[outputPtr++] = (byte) (0x80 | (REPLACEMENT_CHAR & 0x3f));
        return outputPtr;
    }

    private int _decodeAndWriteSurrogate(int surr1, int surr2,
            byte[] outBuf, int outputPtr)
    {
        final int c = 0x10000 + ((surr1 - SURR1_FIRST) << 10)
                + (surr2 - SURR2_FIRST);
        outBuf[outputPtr++] = (byte) (0xf0 | (c >> 18));
        outBuf[outputPtr++] = (byte) (0x80 | ((c >> 12) & 0x3f));
        outBuf[outputPtr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
        outBuf[outputPtr++] = (byte) (0x80 | (c & 0x3f));
        return outputPtr;
    }

    /*
    /**********************************************************
    /* Internal methods, writing bytes
    /**********************************************************
     */

    private final void _ensureRoomForOutput(int needed) throws IOException {
        if ((_outputTail + needed) >= _outputEnd) {
            _flushBuffer();
        }
    }

    private final void _writeIntValue(int i) throws IOException {
        int marker;
        if (i < 0) {
            i = -i - 1;
            marker = PREFIX_TYPE_INT_NEG;
        } else {
            marker = PREFIX_TYPE_INT_POS;
        }
        _writeLengthMarker(marker, i);
    }

    private final void _writeLongValue(long l) throws IOException {
        _ensureRoomForOutput(9);
        if (l < 0) {
            l += 1;
            l = -l;
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_NEG + SUFFIX_UINT64_ELEMENTS);
        } else {
            _outputBuffer[_outputTail++] = (PREFIX_TYPE_INT_POS + SUFFIX_UINT64_ELEMENTS);
        }
        int i = (int) (l >> 32);
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        i = (int) l;
        _outputBuffer[_outputTail++] = (byte) (i >> 24);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
    }

    private final void _writeLengthMarker(int majorType, int i)
            throws IOException {
        _ensureRoomForOutput(5);
        if (i < 24) {
            _outputBuffer[_outputTail++] = (byte) (majorType + i);
            return;
        }
        if (i <= 0xFF) {
            _outputBuffer[_outputTail++] = (byte) (majorType + SUFFIX_UINT8_ELEMENTS);
            _outputBuffer[_outputTail++] = (byte) i;
            return;
        }
        final byte b0 = (byte) i;
        i >>= 8;
        if (i <= 0xFF) {
            _outputBuffer[_outputTail++] = (byte) (majorType + SUFFIX_UINT16_ELEMENTS);
            _outputBuffer[_outputTail++] = (byte) i;
            _outputBuffer[_outputTail++] = b0;
            return;
        }
        _outputBuffer[_outputTail++] = (byte) (majorType + SUFFIX_UINT32_ELEMENTS);
        _outputBuffer[_outputTail++] = (byte) (i >> 16);
        _outputBuffer[_outputTail++] = (byte) (i >> 8);
        _outputBuffer[_outputTail++] = (byte) i;
        _outputBuffer[_outputTail++] = b0;
    }

    private final void _writeByte(byte b) throws IOException {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        _outputBuffer[_outputTail++] = b;
    }

    /*
     * private final void _writeBytes(byte b1, byte b2) throws IOException { if
     * ((_outputTail + 1) >= _outputEnd) { _flushBuffer(); }
     * _outputBuffer[_outputTail++] = b1; _outputBuffer[_outputTail++] = b2; }
     */

    private final void _writeBytes(byte[] data, int offset, int len)
            throws IOException {
        if (len == 0) {
            return;
        }
        if ((_outputTail + len) >= _outputEnd) {
            _writeBytesLong(data, offset, len);
            return;
        }
        // common case, non-empty, fits in just fine:
        System.arraycopy(data, offset, _outputBuffer, _outputTail, len);
        _outputTail += len;
    }

    private final int _writeBytes(InputStream in, int bytesLeft)
            throws IOException {
        while (bytesLeft > 0) {
            int room = _outputEnd - _outputTail;
            if (room <= 0) {
                _flushBuffer();
                room = _outputEnd - _outputTail;
            }
            int count = in.read(_outputBuffer, _outputTail, room);
            if (count < 0) {
                break;
            }
            _outputTail += count;
            bytesLeft -= count;
        }
        return bytesLeft;
    }

    private final void _writeBytesLong(byte[] data, int offset, int len)
            throws IOException {
        if (_outputTail >= _outputEnd) {
            _flushBuffer();
        }
        while (true) {
            int currLen = Math.min(len, (_outputEnd - _outputTail));
            System.arraycopy(data, offset, _outputBuffer, _outputTail, currLen);
            _outputTail += currLen;
            if ((len -= currLen) == 0) {
                break;
            }
            offset += currLen;
            _flushBuffer();
        }
    }

    /*
    /**********************************************************
    /* Internal methods, buffer handling
    /**********************************************************
     */

    @Override
    protected void _releaseBuffers() {
        byte[] buf = _outputBuffer;
        if (buf != null && _bufferRecyclable) {
            _outputBuffer = null;
            _ioContext.releaseWriteEncodingBuffer(buf);
        }
        char[] cbuf = _charBuffer;
        if (cbuf != null) {
            _charBuffer = null;
            _ioContext.releaseConcatBuffer(cbuf);
        }
    }

    protected final void _flushBuffer() throws IOException {
        if (_outputTail > 0) {
            _bytesWritten += _outputTail;
            _out.write(_outputBuffer, 0, _outputTail);
            _outputTail = 0;
        }
    }

    /*
    /**********************************************************
    /* Internal methods, size control for array and objects
    /**********************************************************
	*/

    private final void closeComplexElement() throws IOException {
        switch (_currentRemainingElements) {
        case INDEFINITE_LENGTH:
            _writeByte(BYTE_BREAK);
            break;
        case 0: // expected for sized ones
            break;
        default:
            _reportError(String.format("%s size mismatch: expected %d more elements",
                    _streamWriteContext.typeDesc(), _currentRemainingElements));
        }
        _currentRemainingElements = (_elementCountsPtr == 0)
                ? INDEFINITE_LENGTH
                        : _elementCounts[--_elementCountsPtr];
    }

    /*
    /**********************************************************
    /* Internal methods, error reporting
    /**********************************************************
     */

    protected UnsupportedOperationException _notSupported() {
        return new UnsupportedOperationException();
    }
}
