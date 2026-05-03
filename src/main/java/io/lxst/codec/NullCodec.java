package io.lxst.codec;

/** Pass-through codec – encode returns frames unchanged; decode returns frames unchanged. */
public class NullCodec extends Codec {

    @Override
    public byte[] encode(float[][] frame) {
        throw new CodecError("NullCodec does not encode – it is a pass-through only");
    }

    @Override
    public float[][] decode(byte[] frameBytes) {
        throw new CodecError("NullCodec does not decode – it is a pass-through only");
    }
}
