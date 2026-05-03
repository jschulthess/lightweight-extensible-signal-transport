package io.lxst.ogg;

import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import io.lxst.codec.OpusCodec;

import java.io.*;
import java.util.logging.Logger;

/**
 * Writes raw 16-bit PCM audio frames to an OGG Opus file using libopus via JNA.
 * Implements AutoCloseable for use with try-with-resources.
 *
 * The stream is laid out as:
 *   page 0  – BOS + OpusHead
 *   page 1  – OpusTags
 *   page 2+ – Audio data (one Opus frame per page for simplicity)
 */
public class OggOpusWriter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OggOpusWriter.class.getName());

    private static final int PRE_SKIP        = 312;
    private static final int MAX_FRAME_BYTES = 4000;

    private static NativeLibrary libOpus;
    static {
        try {
            libOpus = NativeLibrary.getInstance("opus");
        } catch (UnsatisfiedLinkError e) {
            libOpus = null;
        }
    }

    private final BufferedOutputStream out;
    private final int sampleRate;
    private final int channels;
    private final int maxBytesPerFrame;
    private final Pointer encoderHandle;

    private int  serialNumber;
    private int  sequenceNumber = 0;
    private long granulePosition = 0;

    public OggOpusWriter(String filePath, int sampleRate, int channels, int profile, int maxBytesPerFrame)
            throws IOException {
        if (libOpus == null) throw new IOException("libopus not available; cannot write OGG Opus files");

        this.sampleRate       = sampleRate;
        this.channels         = channels;
        this.maxBytesPerFrame = maxBytesPerFrame;
        this.serialNumber     = (int) System.nanoTime(); // random serial

        // Create encoder
        int appCode = OpusCodec.profileApplication(profile).equals("voip")
                      ? 2048 : 2049; // VOIP vs AUDIO
        IntByReference err = new IntByReference();
        encoderHandle = (Pointer) libOpus.getFunction("opus_encoder_create")
                .invoke(Pointer.class, new Object[]{sampleRate, channels, appCode, err});
        if (err.getValue() != 0) throw new IOException("opus_encoder_create failed: " + err.getValue());

        int bitrate = OpusCodec.profileBitrateCeiling(profile);
        libOpus.getFunction("opus_encoder_ctl")
                .invoke(int.class, new Object[]{encoderHandle, 4002 /* SET_BITRATE */, bitrate});

        out = new BufferedOutputStream(new FileOutputStream(filePath));
        writeOpusHead();
        writeOpusTags();
    }

    private void writeOpusHead() throws IOException {
        byte[] head = new byte[19];
        System.arraycopy("OpusHead".getBytes(), 0, head, 0, 8);
        head[8]  = 1;                       // version
        head[9]  = (byte) channels;
        head[10] = (byte) (PRE_SKIP & 0xFF);
        head[11] = (byte) ((PRE_SKIP >> 8) & 0xFF);
        OggPage.writeInt32LE(head, 12, sampleRate);
        head[16] = 0; head[17] = 0;         // output gain
        head[18] = 0;                        // mapping family

        OggPage page = new OggPage();
        page.headerType     = OggPage.FLAG_BOS;
        page.granulePosition = -1L;
        page.serialNumber   = serialNumber;
        page.sequenceNumber = sequenceNumber++;
        page.data           = head;
        page.write(out);
    }

    private void writeOpusTags() throws IOException {
        byte[] vendor = "Java LXST".getBytes("UTF-8");
        byte[] tags   = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(), 0, tags, 0, 8);
        OggPage.writeInt32LE(tags,  8, vendor.length);
        System.arraycopy(vendor, 0, tags, 12, vendor.length);
        OggPage.writeInt32LE(tags, 12 + vendor.length, 0); // 0 user comments

        OggPage page = new OggPage();
        page.headerType     = 0;
        page.granulePosition = 0;
        page.serialNumber   = serialNumber;
        page.sequenceNumber = sequenceNumber++;
        page.data           = tags;
        page.write(out);
    }

    /**
     * Encode and write one frame of interleaved 16-bit PCM samples.
     * @param pcm interleaved short[] [sample0_ch0, sample0_ch1, sample1_ch0, …]
     */
    public void writePcm(short[] pcm) throws IOException {
        int frameSize    = pcm.length / channels;
        byte[] encoded   = new byte[maxBytesPerFrame + 32];
        int encodedBytes = (int) (Integer) libOpus.getFunction("opus_encode")
                .invoke(int.class, new Object[]{encoderHandle, pcm, frameSize, encoded, encoded.length});
        if (encodedBytes < 0) {
            LOG.warning("opus_encode error " + encodedBytes);
            return;
        }
        byte[] frameData = new byte[encodedBytes];
        System.arraycopy(encoded, 0, frameData, 0, encodedBytes);

        granulePosition += frameSize;

        OggPage page = new OggPage();
        page.headerType     = 0;
        page.granulePosition = granulePosition;
        page.serialNumber   = serialNumber;
        page.sequenceNumber = sequenceNumber++;
        page.data           = frameData;
        page.write(out);
    }

    @Override
    public void close() throws IOException {
        // Write EOS page (empty data, EOS flag)
        OggPage eos = new OggPage();
        eos.headerType     = OggPage.FLAG_EOS;
        eos.granulePosition = granulePosition;
        eos.serialNumber   = serialNumber;
        eos.sequenceNumber = sequenceNumber++;
        eos.data           = new byte[0];
        eos.write(out);

        out.flush();
        out.close();

        libOpus.getFunction("opus_encoder_destroy").invoke(new Object[]{encoderHandle});
    }
}
