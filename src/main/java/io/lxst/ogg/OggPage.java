package io.lxst.ogg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Represents one OGG page (RFC 3533).
 * Handles framing, checksumming, and serialisation.
 */
public class OggPage {

    private static final byte[] CAPTURE_PATTERN = {0x4F, 0x67, 0x67, 0x53}; // "OggS"

    // Header type flags
    public static final int FLAG_CONTINUED  = 0x01;
    public static final int FLAG_BOS        = 0x02;
    public static final int FLAG_EOS        = 0x04;

    // The OGG CRC polynomial (ISO 3309, big-endian reflected)
    private static final int[] CRC_TABLE = buildCrcTable();

    public int  headerType;
    public long granulePosition;
    public int  serialNumber;
    public int  sequenceNumber;
    public byte[] data; // raw page payload (all segments concatenated)

    // ── Static factory: read one OGG page from stream ────────────────────────

    public static OggPage read(InputStream in) throws IOException {
        // Capture pattern
        byte[] cap = readBytes(in, 4);
        if (!Arrays.equals(cap, CAPTURE_PATTERN))
            throw new IOException("Invalid OGG capture pattern");

        int version    = readByte(in);
        int headerType = readByte(in);
        long granule   = readInt64LE(in);
        int serial     = readInt32LE(in);
        int seqNum     = readInt32LE(in);
        /* checksum */   readInt32LE(in); // we don't verify here
        int segCount   = readByte(in);
        byte[] segTable = readBytes(in, segCount);

        int totalBytes = 0;
        for (byte b : segTable) totalBytes += (b & 0xFF);

        byte[] pageData = readBytes(in, totalBytes);

        OggPage page = new OggPage();
        page.headerType    = headerType;
        page.granulePosition = granule;
        page.serialNumber  = serial;
        page.sequenceNumber = seqNum;
        page.data          = pageData;
        return page;
    }

    // ── Write page to stream ──────────────────────────────────────────────────

    public void write(OutputStream out) throws IOException {
        // Build lacing values
        int remaining = data.length;
        java.util.List<Integer> segs = new java.util.ArrayList<>();
        if (remaining == 0) {
            segs.add(0);
        } else {
            while (remaining > 0) {
                int seg = Math.min(255, remaining);
                segs.add(seg);
                remaining -= seg;
                if (seg == 255) segs.add(0); // continuation with 0-byte segment
            }
        }

        int headerSize = 27 + segs.size();
        byte[] header  = new byte[headerSize];
        System.arraycopy(CAPTURE_PATTERN, 0, header, 0, 4);
        header[4]  = 0;                            // version
        header[5]  = (byte) headerType;
        writeInt64LE(header, 6, granulePosition);
        writeInt32LE(header, 14, serialNumber);
        writeInt32LE(header, 18, sequenceNumber);
        // CRC placeholder at [22..25]
        header[26] = (byte) segs.size();
        for (int i = 0; i < segs.size(); i++) header[27 + i] = (byte) (int) segs.get(i);

        // Compute CRC over header + data
        int crc = 0;
        crc = updateCrc(crc, header, 0, header.length);
        crc = updateCrc(crc, data,   0, data.length);
        writeInt32LE(header, 22, crc);

        out.write(header);
        out.write(data);
    }

    // ── CRC helpers ───────────────────────────────────────────────────────────

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = ((r & 0x80000000) != 0) ? (r << 1) ^ 0x04C11DB7 : (r << 1);
            }
            table[i] = r;
        }
        return table;
    }

    private static int updateCrc(int crc, byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            crc = ((crc << 8) ^ CRC_TABLE[(((crc >> 24) & 0xFF) ^ (buf[i] & 0xFF)) & 0xFF]);
        }
        return crc;
    }

    // ── I/O helpers ───────────────────────────────────────────────────────────

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new java.io.EOFException("Unexpected end of OGG stream");
        return b;
    }

    private static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(buf, offset, count - offset);
            if (read < 0) throw new java.io.EOFException("Unexpected end of OGG stream");
            offset += read;
        }
        return buf;
    }

    static int readInt32LE(InputStream in) throws IOException {
        byte[] b = readBytes(in, 4);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    static long readInt64LE(InputStream in) throws IOException {
        byte[] b = readBytes(in, 8);
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    static void writeInt32LE(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    static void writeInt64LE(byte[] buf, int off, long value) {
        for (int i = 0; i < 8; i++) {
            buf[off + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }
}
