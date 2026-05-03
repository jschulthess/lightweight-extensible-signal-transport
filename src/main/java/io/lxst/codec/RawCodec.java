package io.lxst.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.logging.Logger;

/**
 * Uncompressed PCM codec. Encodes float[][] frames to bytes with a 1-byte header
 * describing the bit-depth and channel count; decodes the same format back.
 *
 * Header byte layout: [bitdepthCode(2) | channels-1(6)]
 *   bitdepthCode 0 → float16 (stored as float32 here since Java has no float16)
 *   bitdepthCode 1 → float32
 *   bitdepthCode 2 → float64 (double)
 */
public class RawCodec extends Codec {

    private static final Logger LOG = Logger.getLogger(RawCodec.class.getName());

    public static final int BITDEPTH_16  = 0;
    public static final int BITDEPTH_32  = 1;
    public static final int BITDEPTH_64  = 2;

    private final int bitdepth;
    private final int headerBitdepth;
    private Integer configuredChannels;

    public RawCodec() {
        this(null, 16);
    }

    public RawCodec(Integer channels, int bitdepth) {
        this.configuredChannels = channels;
        if (channels != null) this.channels = Math.min(Math.max(channels, 1), 32);
        this.bitdepth = bitdepth;
        if      (bitdepth >= 64) headerBitdepth = BITDEPTH_64;
        else if (bitdepth >= 32) headerBitdepth = BITDEPTH_32;
        else                     headerBitdepth = BITDEPTH_16;
    }

    @Override
    public byte[] encode(float[][] frame) {
        int samples  = frame.length;
        int chans = frame[0].length;

        if (configuredChannels == null) {
            configuredChannels = chans;
            channels = chans;
            LOG.fine(this + " encoder set to " + channels + " channels");
        }

        // Channel adjustment
        int outChans = channels;
        float[][] out = new float[samples][outChans];
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < outChans; c++) {
                if (c < chans) out[s][c] = frame[s][c];
                else            out[s][c] = frame[s][chans - 1];
            }
        }

        int headerByte = (headerBitdepth << 6) | (outChans - 1);
        int bytesPerSample = (headerBitdepth == BITDEPTH_64) ? 8 : 4;
        byte[] result = new byte[1 + samples * outChans * bytesPerSample];
        result[0] = (byte) headerByte;
        ByteBuffer buf = ByteBuffer.wrap(result, 1, result.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < outChans; c++) {
                if (headerBitdepth == BITDEPTH_64) buf.putDouble(out[s][c]);
                else                               buf.putFloat(out[s][c]);
            }
        }
        return result;
    }

    @Override
    public float[][] decode(byte[] frameBytes) {
        int header   = frameBytes[0] & 0xFF;
        int chans    = (header & 0b00111111) + 1;
        int bdCode   = header >> 6;
        int bytesPS  = (bdCode == BITDEPTH_64) ? 8 : 4;
        int dataLen  = frameBytes.length - 1;
        int samples  = dataLen / (chans * bytesPS);

        if (configuredChannels == null) channels = chans;

        ByteBuffer buf = ByteBuffer.wrap(frameBytes, 1, dataLen).order(ByteOrder.LITTLE_ENDIAN);
        float[][] result = new float[samples][chans];
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < chans; c++) {
                result[s][c] = (bdCode == BITDEPTH_64) ? (float) buf.getDouble() : buf.getFloat();
            }
        }
        return result;
    }
}
