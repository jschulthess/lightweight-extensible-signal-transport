package io.lxst.codec;

/** Codec header-byte registry – mirrors Python LXST.Codecs __init__.py. */
public final class Codecs {

    public static final int NULL   = 0xFF;
    public static final int RAW    = 0x00;
    public static final int OPUS   = 0x01;
    public static final int CODEC2 = 0x02;

    private Codecs() {}

    public static byte codecHeaderByte(Class<? extends Codec> codec) {
        if (codec == RawCodec.class)    return (byte) RAW;
        if (codec == OpusCodec.class)   return (byte) OPUS;
        if (codec == Codec2Codec.class) return (byte) CODEC2;
        throw new CodecError("No header mapping for codec type: " + codec.getName());
    }

    public static Class<? extends Codec> codecType(byte headerByte) {
        int h = headerByte & 0xFF;
        if (h == RAW)    return RawCodec.class;
        if (h == OPUS)   return OpusCodec.class;
        if (h == CODEC2) return Codec2Codec.class;
        throw new CodecError("Unknown codec header byte: 0x" + Integer.toHexString(h));
    }

    /** Instantiate a fresh codec from its header byte. */
    public static Codec newCodecFromHeader(byte headerByte) {
        int h = headerByte & 0xFF;
        if (h == RAW)    return new RawCodec();
        if (h == OPUS)   return new OpusCodec();
        if (h == CODEC2) return new Codec2Codec();
        throw new CodecError("Unknown codec header byte: 0x" + Integer.toHexString(h));
    }
}
