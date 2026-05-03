package io.lxst.source;

import io.lxst.codec.Codec;
import io.lxst.sink.Sink;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Passes decoded audio frames from a source directly to a sink, with an
 * optional in-memory queue.  Useful for connecting two pipeline stages without
 * any codec or device in between.
 *
 * Mirrors Python LXST Sources.Loopback.
 */
public class Loopback extends LocalSource implements io.lxst.sink.LocalSink {

    private static final int MAX_FRAMES = 128;

    private final Deque<float[][]> frameDeque = new ArrayDeque<>();
    private final Object loopbackLock = new Object();

    private volatile boolean shouldRun = false;
    private Codec codec;
    private Sink _sink;
    private Source _source;

    private int sampleRate = 48000;
    private int channels   = 1;
    private int bitDepth   = 32;

    public Loopback() {}

    public Loopback(Codec codec, Sink sink) {
        this.codec  = codec;
        this._sink  = sink;
    }

    // ── Source interface ──────────────────────────────────────────────────────

    @Override public void start() { shouldRun = true; }
    @Override public void stop()  { shouldRun = false; }
    @Override public boolean isShouldRun() { return shouldRun; }
    @Override public int getSampleRate()   { return sampleRate; }
    @Override public int getChannels()     { return channels; }
    @Override public int getBitDepth()     { return bitDepth; }
    @Override public Codec getCodec()      { return codec; }
    @Override public void setCodec(Codec c) { this.codec = c; }
    @Override public Sink getSink()        { return _sink; }
    @Override public void setSink(Sink s)  { this._sink = s; }
    public Source getSource()              { return _source; }
    public void setSource(Source s)        { this._source = s; }

    // ── Sink interface ────────────────────────────────────────────────────────

    @Override
    public boolean canReceive(Source fromSource) {
        if (_sink != null) return _sink.canReceive(fromSource);
        return true;
    }

    @Override
    public void handleFrame(byte[] frame, Source source) {
        synchronized (loopbackLock) {
            if (codec != null && _sink != null) {
                float[][] decoded = codec.decode(frame);
                _sink.handleFrame(encode(decoded), this);
            }
        }
    }

    /** For decoded frames coming from OpusFileSource (no re-encoding needed). */
    public void handleDecodedFrame(float[][] frame, Source source) {
        synchronized (loopbackLock) {
            if (_sink instanceof io.lxst.sink.LineSink) {
                ((io.lxst.sink.LineSink) _sink).handleDecodedFrame(frame, this);
            } else if (_sink instanceof io.lxst.sink.OpusFileSink) {
                ((io.lxst.sink.OpusFileSink) _sink).handleDecodedFrame(frame, this);
            } else if (_sink != null && codec != null) {
                _sink.handleFrame(encode(frame), this);
            }
        }
    }

    private byte[] encode(float[][] frame) {
        if (codec != null) return codec.encode(frame);
        throw new UnsupportedOperationException("No codec set on Loopback");
    }

    public void setSampleRate(int sr) { this.sampleRate = sr; }
    public void setChannelsCount(int ch) { this.channels = ch; }
}
