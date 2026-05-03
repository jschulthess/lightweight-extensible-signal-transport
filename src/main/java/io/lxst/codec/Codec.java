package io.lxst.codec;

import io.lxst.sink.Sink;
import io.lxst.source.Source;

/**
 * Base class for audio codecs. Codecs encode float[][] PCM frames to bytes and
 * decode bytes back to float[][] PCM frames.
 *
 * Audio frames are represented as float[samples][channels] with values in [-1.0, 1.0].
 */
public abstract class Codec {

    /** Preferred sample rate, or null if codec accepts any rate. */
    public Integer preferredSampleRate = null;

    /** Encoding frame must be a multiple of this many milliseconds, or null. */
    public Double frameQuantaMs = null;

    /** Maximum allowed frame duration in milliseconds, or null. */
    public Double frameMaxMs = null;

    /** Set of valid frame durations in milliseconds (use closest if exact match not found), or null. */
    public double[] validFrameMs = null;

    /** Number of audio channels. */
    public int channels = 1;

    /** The source connected to this codec (for rate information during encoding). */
    public Source source = null;

    /** The sink connected to this codec (for channel/rate information during decoding). */
    public Sink sink = null;

    public abstract byte[] encode(float[][] frame);

    public abstract float[][] decode(byte[] frameBytes);
}
