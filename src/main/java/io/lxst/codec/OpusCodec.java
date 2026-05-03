package io.lxst.codec;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opus audio codec backed by the system libopus via JNA.
 *
 * Requires libopus.so (Linux), libopus.dylib (macOS) or opus.dll (Windows) to be
 * installed.  Mimics the Python pyogg-based Opus class from LXST exactly,
 * including the same profile constants and static helper methods.
 */
public class OpusCodec extends Codec {

    private static final Logger LOG = Logger.getLogger(OpusCodec.class.getName());

    // ── Opus application types ────────────────────────────────────────────────
    private static final int OPUS_APPLICATION_VOIP  = 2048;
    private static final int OPUS_APPLICATION_AUDIO = 2049;

    // ── Opus CTL request codes ────────────────────────────────────────────────
    private static final int OPUS_SET_BITRATE_REQUEST       = 4002;
    private static final int OPUS_SET_MAX_BANDWIDTH_REQUEST = 4004;

    // ── LXST profile constants (mirrors Python Opus class) ───────────────────
    public static final int PROFILE_VOICE_LOW    = 0x00;
    public static final int PROFILE_VOICE_MEDIUM = 0x01;
    public static final int PROFILE_VOICE_HIGH   = 0x02;
    public static final int PROFILE_VOICE_MAX    = 0x03;
    public static final int PROFILE_AUDIO_MIN    = 0x04;
    public static final int PROFILE_AUDIO_LOW    = 0x05;
    public static final int PROFILE_AUDIO_MEDIUM = 0x06;
    public static final int PROFILE_AUDIO_HIGH   = 0x07;
    public static final int PROFILE_AUDIO_MAX    = 0x08;

    public static final double  FRAME_QUANTA_MS = 2.5;
    public static final double  FRAME_MAX_MS    = 60.0;
    public static final double[] VALID_FRAME_MS = {2.5, 5, 10, 20, 40, 60};

    private static final float TYPE_MAP_FACTOR = 32767.0f;

    // ── libopus JNA handle ────────────────────────────────────────────────────
    private static NativeLibrary libOpus;
    static {
        try {
            libOpus = NativeLibrary.getInstance("opus");
        } catch (UnsatisfiedLinkError e) {
            LOG.warning("libopus not found – OpusCodec will not be usable: " + e.getMessage());
            libOpus = null;
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private final int profile;
    private int inputChannels;
    private int outputChannels;
    private int outputSampleRate;
    private int bitrateCeiling;
    private int bitdepth = 16;
    private String application;

    private Pointer encoderHandle;
    private Pointer decoderHandle;
    private boolean encoderConfigured;
    private boolean decoderConfigured;

    private long outputBytes;
    private double outputMs;
    private double outputBitrate;

    // ── Constructors ──────────────────────────────────────────────────────────

    public OpusCodec() {
        this(PROFILE_VOICE_LOW);
    }

    public OpusCodec(int profile) {
        this.frameQuantaMs = FRAME_QUANTA_MS;
        this.frameMaxMs    = FRAME_MAX_MS;
        this.validFrameMs  = VALID_FRAME_MS;
        this.channels      = 1;
        this.inputChannels = 1;
        this.outputChannels = 2;
        this.profile       = profile;
        setProfile(profile);
    }

    // ── Static profile helpers ────────────────────────────────────────────────

    public static int profileChannels(int profile) {
        switch (profile) {
            case PROFILE_VOICE_LOW: case PROFILE_VOICE_MEDIUM: case PROFILE_VOICE_HIGH:
            case PROFILE_AUDIO_MIN: case PROFILE_AUDIO_LOW:
                return 1;
            default:
                return 2;
        }
    }

    public static int profileSampleRate(int profile) {
        switch (profile) {
            case PROFILE_VOICE_LOW:    return 8000;
            case PROFILE_VOICE_MEDIUM: return 24000;
            case PROFILE_AUDIO_MIN:    return 8000;
            case PROFILE_AUDIO_LOW:    return 12000;
            case PROFILE_AUDIO_MEDIUM: return 24000;
            default:                   return 48000;
        }
    }

    public static String profileApplication(int profile) {
        switch (profile) {
            case PROFILE_VOICE_LOW: case PROFILE_VOICE_MEDIUM:
            case PROFILE_VOICE_HIGH: case PROFILE_VOICE_MAX:
                return "voip";
            default:
                return "audio";
        }
    }

    public static int profileBitrateCeiling(int profile) {
        switch (profile) {
            case PROFILE_VOICE_LOW:    return 6000;
            case PROFILE_VOICE_MEDIUM: return 8000;
            case PROFILE_VOICE_HIGH:   return 16000;
            case PROFILE_VOICE_MAX:    return 32000;
            case PROFILE_AUDIO_MIN:    return 8000;
            case PROFILE_AUDIO_LOW:    return 14000;
            case PROFILE_AUDIO_MEDIUM: return 28000;
            case PROFILE_AUDIO_HIGH:   return 56000;
            case PROFILE_AUDIO_MAX:    return 128000;
            default: throw new CodecError("Unsupported profile: " + profile);
        }
    }

    public static int maxBytesPerFrame(int bitrateCeiling, double frameDurationMs) {
        return (int) Math.ceil((bitrateCeiling / 8.0) * (frameDurationMs / 1000.0));
    }

    // ── Profile setup ─────────────────────────────────────────────────────────

    private void setProfile(int profile) {
        this.channels      = profileChannels(profile);
        this.inputChannels = this.channels;
        this.outputSampleRate = profileSampleRate(profile);
        this.application   = profileApplication(profile);
        this.bitrateCeiling = profileBitrateCeiling(profile);
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    @Override
    public byte[] encode(float[][] frame) {
        requireLibOpus();

        int samples = frame.length;
        int inChans = frame[0].length;

        // Channel adjustment
        float[][] adj = adjustChannels(frame, inputChannels);

        // Convert float[] → int16 PCM
        short[] pcm = floatToShort(adj);

        // Resample if source rate != output rate
        if (source != null && source.getSampleRate() != outputSampleRate) {
            pcm = resampleShort(pcm, inputChannels, source.getSampleRate(), outputSampleRate);
        }

        int frameSize = pcm.length / inputChannels;
        double frameDurationMs = (frameSize * 1000.0) / outputSampleRate;

        // Lazy encoder init
        if (!encoderConfigured) {
            int appCode = application.equals("voip") ? OPUS_APPLICATION_VOIP : OPUS_APPLICATION_AUDIO;
            IntByReference err = new IntByReference();
            encoderHandle = (Pointer) libOpus.getFunction("opus_encoder_create")
                    .invoke(Pointer.class, new Object[]{outputSampleRate, inputChannels, appCode, err});
            if (err.getValue() != 0) throw new CodecError("opus_encoder_create failed: " + err.getValue());
            LOG.fine(this + " encoder set to " + inputChannels + " channels, " + outputSampleRate + " Hz");
            encoderConfigured = true;
        }

        // Update bitrate
        int maxBytes = maxBytesPerFrame(bitrateCeiling, frameDurationMs);
        int bitrate = (int) ((maxBytes * 8.0) / (frameDurationMs / 1000.0));
        libOpus.getFunction("opus_encoder_ctl")
                .invoke(int.class, new Object[]{encoderHandle, OPUS_SET_BITRATE_REQUEST, bitrate});

        // Encode
        byte[] output = new byte[maxBytes + 32];
        short[] nativePcm = new short[pcm.length];
        System.arraycopy(pcm, 0, nativePcm, 0, pcm.length);

        int encoded = (int) (Integer) libOpus.getFunction("opus_encode")
                .invoke(int.class, new Object[]{encoderHandle, nativePcm, frameSize, output, output.length});
        if (encoded < 0) throw new CodecError("opus_encode failed: " + encoded);

        byte[] result = new byte[encoded];
        System.arraycopy(output, 0, result, 0, encoded);

        outputBytes += encoded;
        outputMs    += frameDurationMs;
        outputBitrate = (outputBytes * 8.0) / (outputMs / 1000.0);

        return result;
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    @Override
    public float[][] decode(byte[] frameBytes) {
        requireLibOpus();

        // Lazy decoder init
        if (!decoderConfigured) {
            int outCh = (sink != null && sink.getChannels() > 0) ? sink.getChannels()
                       : Math.max(outputChannels, channels);
            channels = outCh;
            int sinkRate = (sink != null && sink.getSampleRate() > 0) ? sink.getSampleRate() : outputSampleRate;
            IntByReference err = new IntByReference();
            decoderHandle = (Pointer) libOpus.getFunction("opus_decoder_create")
                    .invoke(Pointer.class, new Object[]{sinkRate, outCh, err});
            if (err.getValue() != 0) throw new CodecError("opus_decoder_create failed: " + err.getValue());
            LOG.fine(this + " decoder set to " + outCh + " channels, " + sinkRate + " Hz");
            decoderConfigured = true;
        }

        // Max frame size: 60 ms at 48 kHz stereo
        int maxFrameSamples = 5760;
        short[] pcm = new short[maxFrameSamples * channels];
        int sinkRate = (sink != null && sink.getSampleRate() > 0) ? sink.getSampleRate() : outputSampleRate;
        int frameSize = (int) (60 * sinkRate / 1000); // max 60 ms

        int decoded = (int) (Integer) libOpus.getFunction("opus_decode")
                .invoke(int.class, new Object[]{decoderHandle, frameBytes, frameBytes.length, pcm, frameSize, 0});
        if (decoded < 0) throw new CodecError("opus_decode failed: " + decoded);

        return shortToFloat(pcm, decoded, channels);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void close() {
        if (encoderHandle != null) {
            libOpus.getFunction("opus_encoder_destroy").invoke(new Object[]{encoderHandle});
            encoderHandle = null;
        }
        if (decoderHandle != null) {
            libOpus.getFunction("opus_decoder_destroy").invoke(new Object[]{decoderHandle});
            decoderHandle = null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void requireLibOpus() {
        if (libOpus == null)
            throw new CodecError("libopus is not available on this system. Install libopus to use OpusCodec.");
    }

    private static float[][] adjustChannels(float[][] frame, int targetChannels) {
        int samples = frame.length;
        int src     = frame[0].length;
        if (src == targetChannels) return frame;
        float[][] out = new float[samples][targetChannels];
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < targetChannels; c++) {
                out[s][c] = (c < src) ? frame[s][c] : frame[s][src - 1];
            }
        }
        return out;
    }

    private static short[] floatToShort(float[][] frame) {
        int samples = frame.length;
        int chans   = frame[0].length;
        short[] pcm = new short[samples * chans];
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < chans; c++) {
                float v = frame[s][c];
                if (v >  1.0f) v =  1.0f;
                if (v < -1.0f) v = -1.0f;
                pcm[s * chans + c] = (short) (v * TYPE_MAP_FACTOR);
            }
        }
        return pcm;
    }

    static float[][] shortToFloat(short[] pcm, int sampleCount, int channels) {
        float[][] frame = new float[sampleCount][channels];
        for (int s = 0; s < sampleCount; s++) {
            for (int c = 0; c < channels; c++) {
                frame[s][c] = pcm[s * channels + c] / TYPE_MAP_FACTOR;
            }
        }
        return frame;
    }

    /** Very simple linear resampler: good enough for up/down-sampling by small integer ratios. */
    private static short[] resampleShort(short[] input, int channels, int inputRate, int outputRate) {
        if (inputRate == outputRate) return input;
        int inputSamples  = input.length / channels;
        int outputSamples = (int) Math.round((double) inputSamples * outputRate / inputRate);
        short[] output = new short[outputSamples * channels];
        for (int s = 0; s < outputSamples; s++) {
            double pos = (double) s * inputSamples / outputSamples;
            int lo     = (int) pos;
            int hi     = Math.min(lo + 1, inputSamples - 1);
            float frac = (float) (pos - lo);
            for (int c = 0; c < channels; c++) {
                float a = input[lo * channels + c];
                float b = input[hi * channels + c];
                output[s * channels + c] = (short) (a + frac * (b - a));
            }
        }
        return output;
    }

    @Override
    public String toString() {
        return "<lxst.codec.Opus>";
    }
}
