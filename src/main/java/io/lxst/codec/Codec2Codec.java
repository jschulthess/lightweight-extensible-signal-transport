package io.lxst.codec;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * Codec2 voice codec backed by the system libcodec2 via JNA.
 *
 * Requires libcodec2.so (Linux), libcodec2.dylib (macOS) or codec2.dll (Windows).
 * Faithfully reproduces the Python Codec2 class from LXST.
 */
public class Codec2Codec extends Codec {

    private static final Logger LOG = Logger.getLogger(Codec2Codec.class.getName());

    // ── Mode constants (match Python Codec2 class) ────────────────────────────
    public static final int CODEC2_700C = 700;
    public static final int CODEC2_1200 = 1200;
    public static final int CODEC2_1300 = 1300;
    public static final int CODEC2_1400 = 1400;
    public static final int CODEC2_1600 = 1600;
    public static final int CODEC2_2400 = 2400;
    public static final int CODEC2_3200 = 3200;

    public static final int   INPUT_RATE      = 8000;
    public static final int   OUTPUT_RATE     = 8000;
    public static final double FRAME_QUANTA_MS = 40.0;

    // libcodec2 mode integer codes (internal)
    private static final int MODE_700C_INT  = 8;
    private static final int MODE_1200_INT  = 5;
    private static final int MODE_1300_INT  = 4;
    private static final int MODE_1400_INT  = 3;
    private static final int MODE_1600_INT  = 2;
    private static final int MODE_2400_INT  = 1;
    private static final int MODE_3200_INT  = 0;

    private static int modeToInt(int mode) {
        switch (mode) {
            case CODEC2_700C: return MODE_700C_INT;
            case CODEC2_1200: return MODE_1200_INT;
            case CODEC2_1300: return MODE_1300_INT;
            case CODEC2_1400: return MODE_1400_INT;
            case CODEC2_1600: return MODE_1600_INT;
            case CODEC2_2400: return MODE_2400_INT;
            case CODEC2_3200: return MODE_3200_INT;
            default: throw new CodecError("Unknown Codec2 mode: " + mode);
        }
    }

    // Header byte mapping matches Python HEADER_MODES / MODE_HEADERS
    private static final java.util.Map<Integer, Byte> MODE_HEADERS;
    private static final java.util.Map<Byte, Integer> HEADER_MODES;
    static {
        MODE_HEADERS = new java.util.HashMap<>();
        MODE_HEADERS.put(CODEC2_700C, (byte) 0x00);
        MODE_HEADERS.put(CODEC2_1200, (byte) 0x01);
        MODE_HEADERS.put(CODEC2_1300, (byte) 0x02);
        MODE_HEADERS.put(CODEC2_1400, (byte) 0x03);
        MODE_HEADERS.put(CODEC2_1600, (byte) 0x04);
        MODE_HEADERS.put(CODEC2_2400, (byte) 0x05);
        MODE_HEADERS.put(CODEC2_3200, (byte) 0x06);
        HEADER_MODES = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, Byte> e : MODE_HEADERS.entrySet()) {
            HEADER_MODES.put(e.getValue(), e.getKey());
        }
    }

    private static final float TYPE_MAP_FACTOR = 32767.0f;

    // ── libcodec2 JNA handle ─────────────────────────────────────────────────
    private static NativeLibrary libCodec2;
    static {
        try {
            libCodec2 = NativeLibrary.getInstance("codec2");
        } catch (UnsatisfiedLinkError e) {
            LOG.warning("libcodec2 not found – Codec2Codec will not be usable: " + e.getMessage());
            libCodec2 = null;
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private int mode;
    private byte modeHeaderByte;
    private Pointer c2State;

    public Codec2Codec() {
        this(CODEC2_2400);
    }

    public Codec2Codec(int mode) {
        this.frameQuantaMs = FRAME_QUANTA_MS;
        this.channels      = 1;
        this.bitdepth      = 16;
        setMode(mode);
    }

    // Keep the public bitdepth field accessible
    public int bitdepth = 16;

    private void setMode(int mode) {
        this.mode = mode;
        this.modeHeaderByte = MODE_HEADERS.get(mode);
        if (libCodec2 != null) {
            if (c2State != null)
                libCodec2.getFunction("codec2_destroy").invoke(new Object[]{c2State});
            c2State = (Pointer) libCodec2.getFunction("codec2_create")
                    .invoke(Pointer.class, new Object[]{modeToInt(mode)});
        }
    }

    private void requireLibCodec2() {
        if (libCodec2 == null)
            throw new CodecError("libcodec2 is not available on this system. Install libcodec2 to use Codec2Codec.");
        if (c2State == null) setMode(mode);
    }

    private int samplesPerFrame() {
        requireLibCodec2();
        return (int) (Integer) libCodec2.getFunction("codec2_samples_per_frame")
                .invoke(int.class, new Object[]{c2State});
    }

    private int bytesPerFrame() {
        requireLibCodec2();
        return (int) (Integer) libCodec2.getFunction("codec2_bits_per_frame")
                .invoke(int.class, new Object[]{c2State});
        // Note: libcodec2 names this _bits_per_frame but returns bytes
    }

    @Override
    public byte[] encode(float[][] frame) {
        requireLibCodec2();

        int samples = frame.length;
        // Take only first channel
        float[] mono = new float[samples];
        for (int s = 0; s < samples; s++) mono[s] = frame[s][0];

        short[] pcm = new short[samples];
        for (int s = 0; s < samples; s++) pcm[s] = (short) (mono[s] * TYPE_MAP_FACTOR);

        // Resample to 8 kHz if needed
        if (source != null && source.getSampleRate() != INPUT_RATE) {
            pcm = resampleShort(pcm, source.getSampleRate(), INPUT_RATE);
        }

        int spf   = samplesPerFrame();
        int bpf   = bytesPerFrame();
        int nFrames = pcm.length / spf;

        byte[] encoded = new byte[nFrames * bpf];
        for (int fi = 0; fi < nFrames; fi++) {
            short[] chunk = new short[spf];
            System.arraycopy(pcm, fi * spf, chunk, 0, spf);
            byte[] bits = new byte[bpf];
            libCodec2.getFunction("codec2_encode").invoke(new Object[]{c2State, bits, chunk});
            System.arraycopy(bits, 0, encoded, fi * bpf, bpf);
        }

        byte[] result = new byte[1 + encoded.length];
        result[0] = modeHeaderByte;
        System.arraycopy(encoded, 0, result, 1, encoded.length);
        return result;
    }

    @Override
    public float[][] decode(byte[] frameBytes) {
        requireLibCodec2();

        byte headerByte = frameBytes[0];
        byte[] data = new byte[frameBytes.length - 1];
        System.arraycopy(frameBytes, 1, data, 0, data.length);

        Integer frameMode = HEADER_MODES.get(headerByte);
        if (frameMode == null) frameMode = mode;
        if (frameMode != mode) setMode(frameMode);

        int spf   = samplesPerFrame();
        int bpf   = bytesPerFrame();
        int nFrames = data.length / bpf;

        short[] decoded = new short[nFrames * spf];
        for (int fi = 0; fi < nFrames; fi++) {
            byte[] bits = new byte[bpf];
            System.arraycopy(data, fi * bpf, bits, 0, bpf);
            short[] speech = new short[spf];
            libCodec2.getFunction("codec2_decode").invoke(new Object[]{c2State, speech, bits});
            System.arraycopy(speech, 0, decoded, fi * spf, spf);
        }

        // Resample from 8 kHz to sink rate if needed
        int sinkRate = (sink != null && sink.getSampleRate() > 0) ? sink.getSampleRate() : OUTPUT_RATE;
        if (sinkRate != OUTPUT_RATE) {
            decoded = resampleShort(decoded, OUTPUT_RATE, sinkRate);
        }

        float[][] result = new float[decoded.length][1];
        for (int s = 0; s < decoded.length; s++) result[s][0] = decoded[s] / TYPE_MAP_FACTOR;
        return result;
    }

    public void close() {
        if (libCodec2 != null && c2State != null) {
            libCodec2.getFunction("codec2_destroy").invoke(new Object[]{c2State});
            c2State = null;
        }
    }

    private static short[] resampleShort(short[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) return input;
        int outputSamples = (int) Math.round((double) input.length * outputRate / inputRate);
        short[] output = new short[outputSamples];
        for (int s = 0; s < outputSamples; s++) {
            double pos  = (double) s * input.length / outputSamples;
            int lo      = (int) pos;
            int hi      = Math.min(lo + 1, input.length - 1);
            float frac  = (float) (pos - lo);
            output[s]   = (short) (input[lo] + frac * (input[hi] - input[lo]));
        }
        return output;
    }

    @Override
    public String toString() {
        return "<lxst.codec.Codec2>";
    }
}
