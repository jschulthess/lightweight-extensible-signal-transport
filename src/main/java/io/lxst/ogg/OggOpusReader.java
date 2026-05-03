package io.lxst.ogg;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Reads an OGG Opus file and decodes it to an interleaved short[] PCM buffer.
 *
 * Uses libopus (via JNA) for decoding. Only mono and stereo streams are fully
 * tested; multi-channel streams with a mapping family > 0 are not supported.
 */
public class OggOpusReader implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OggOpusReader.class.getName());

    private static NativeLibrary libOpus;
    static {
        try {
            libOpus = NativeLibrary.getInstance("opus");
        } catch (UnsatisfiedLinkError e) {
            libOpus = null;
        }
    }

    private final int    sampleRate;
    private final int    channels;
    private final short[] samples;   // full decoded audio, interleaved

    public OggOpusReader(String filePath) throws IOException {
        if (libOpus == null) throw new IOException("libopus not available; cannot read OGG Opus files");

        try (InputStream in = new BufferedInputStream(new FileInputStream(filePath))) {
            // ── Read OpusHead ─────────────────────────────────────────────────
            OggPage headPage = OggPage.read(in);
            if ((headPage.headerType & OggPage.FLAG_BOS) == 0)
                throw new IOException("First OGG page is not BOS");
            byte[] headData = headPage.data;
            if (!new String(headData, 0, 8).equals("OpusHead"))
                throw new IOException("Missing OpusHead packet");

            int version       = headData[8] & 0xFF;
            int channels      = headData[9] & 0xFF;
            int preSkip        = ((headData[10] & 0xFF) | ((headData[11] & 0xFF) << 8));
            int inputSampleRate = OggPage.readInt32LE(arrayStream(headData, 12));
            // output gain at [16..17] – we ignore it for simplicity
            // channel mapping family at [18]

            this.channels   = channels;
            this.sampleRate = 48000; // Opus always decodes to 48 kHz

            // ── Read OpusTags (skip) ──────────────────────────────────────────
            OggPage tagsPage = OggPage.read(in);

            // ── Create decoder ────────────────────────────────────────────────
            IntByReference err = new IntByReference();
            Pointer decoder = (Pointer) libOpus.getFunction("opus_decoder_create")
                    .invoke(Pointer.class, new Object[]{48000, channels, err});
            if (err.getValue() != 0) throw new IOException("opus_decoder_create failed: " + err.getValue());

            // ── Decode audio pages ────────────────────────────────────────────
            java.util.List<short[]> chunks = new java.util.ArrayList<>();
            int totalSamples = 0;
            int maxFrameSize = 5760 * channels; // 60ms at 48kHz

            try {
                while (true) {
                    OggPage page;
                    try {
                        page = OggPage.read(in);
                    } catch (java.io.EOFException e) {
                        break;
                    }

                    // Decode each packet in the page
                    // A simple page has the full packet as its data
                    // (For very long frames, packets span pages, but we handle the common case)
                    byte[] packetData = page.data;
                    if (packetData.length == 0) continue;

                    short[] pcm = new short[maxFrameSize];
                    int decoded = (int) (Integer) libOpus.getFunction("opus_decode")
                            .invoke(int.class, new Object[]{decoder, packetData, packetData.length, pcm, 5760, 0});
                    if (decoded < 0) {
                        LOG.warning("opus_decode error " + decoded + ", skipping frame");
                        continue;
                    }

                    short[] chunk = new short[decoded * channels];
                    System.arraycopy(pcm, 0, chunk, 0, decoded * channels);
                    chunks.add(chunk);
                    totalSamples += decoded;

                    if ((page.headerType & OggPage.FLAG_EOS) != 0) break;
                }
            } finally {
                libOpus.getFunction("opus_decoder_destroy").invoke(new Object[]{decoder});
            }

            // Remove pre-skip samples
            int skipSamples = Math.min(preSkip, totalSamples);
            totalSamples -= skipSamples;

            short[] allSamples = new short[totalSamples * channels];
            int destPos = 0;
            int toSkip  = skipSamples * channels;
            for (short[] chunk : chunks) {
                if (toSkip > 0) {
                    int skip = Math.min(toSkip, chunk.length);
                    toSkip  -= skip;
                    if (skip < chunk.length) {
                        System.arraycopy(chunk, skip, allSamples, destPos, chunk.length - skip);
                        destPos += chunk.length - skip;
                    }
                } else {
                    System.arraycopy(chunk, 0, allSamples, destPos, chunk.length);
                    destPos += chunk.length;
                }
            }
            this.samples = allSamples;
        }
    }

    public int getSampleRate() { return sampleRate; }
    public int getChannels()   { return channels; }

    /** Total PCM sample frames (each frame = channels samples). */
    public int getSampleCount() { return samples.length / channels; }

    /** Duration in seconds. */
    public double getDurationSeconds() { return (double) getSampleCount() / sampleRate; }

    /**
     * Convert to normalised float[][] [sampleFrames][channels] in [-1.0, 1.0].
     */
    public float[][] asFloatArray() {
        int n      = getSampleCount();
        float[][] out = new float[n][channels];
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < channels; c++) {
                out[s][c] = samples[s * channels + c] / 32768.0f;
            }
        }
        return out;
    }

    @Override
    public void close() {}

    // ── Helper: wrap a byte array slice as an InputStream for OggPage.readInt32LE ──

    private static java.io.ByteArrayInputStream arrayStream(byte[] data, int offset) {
        return new java.io.ByteArrayInputStream(data, offset, data.length - offset);
    }
}
