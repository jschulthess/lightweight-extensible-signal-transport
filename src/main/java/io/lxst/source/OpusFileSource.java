package io.lxst.source;

import io.lxst.Mixer;
import io.lxst.codec.Codec;
import io.lxst.codec.CodecError;
import io.lxst.ogg.OggOpusReader;
import io.lxst.sink.LineSink;
import io.lxst.sink.OpusFileSink;
import io.lxst.sink.Sink;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays back an OGG Opus file as a series of decoded float[][] frames.
 *
 * Mirrors Python LXST Sources.OpusFileSource.
 */
public class OpusFileSource extends LocalSource {

    private static final Logger LOG = Logger.getLogger(OpusFileSource.class.getName());

    private static final int    MAX_FRAMES       = 128;
    private static final int    DEFAULT_FRAME_MS = 100;

    private int targetFrameMs;
    private final boolean loop;
    private final boolean timed;

    private int sampleRate;
    private int channels;
    private int bitDepth = 16;
    private int samplesPerFrame;
    private double frameTime;

    private float[][] samples;   // full decoded audio
    private int sampleCount;

    private volatile boolean shouldRun = false;
    private Thread ingestThread;
    private final Object readLock = new Object();

    private Codec _codec;
    private Sink  _sink;

    public OpusFileSource(String filePath) throws IOException {
        this(filePath, DEFAULT_FRAME_MS, false, null, null, false);
    }

    public OpusFileSource(String filePath, int targetFrameMs) throws IOException {
        this(filePath, targetFrameMs, false, null, null, false);
    }

    public OpusFileSource(String filePath, boolean loop) throws IOException {
        this(filePath, DEFAULT_FRAME_MS, loop, null, null, false);
    }

    public OpusFileSource(String filePath, int targetFrameMs, boolean loop,
                          Codec codec, Sink sink, boolean timed) throws IOException {
        this.targetFrameMs = targetFrameMs;
        this.loop          = loop;
        this.timed         = timed;

        try (OggOpusReader reader = new OggOpusReader(filePath)) {
            this.sampleRate  = reader.getSampleRate();
            this.channels    = reader.getChannels();
            this.samples     = reader.asFloatArray();
            this.sampleCount = reader.getSampleCount();
        }

        double lengthMs = (sampleCount * 1000.0) / sampleRate;
        LOG.fine(this + " loaded " + String.format("%.2fs", lengthMs / 1000) + " of audio from " + filePath);

        setCodec(codec);
        setSink(sink);
    }

    @Override
    public Codec getCodec() { return _codec; }

    @Override
    public void setCodec(Codec codec) {
        if (codec == null) { _codec = null; return; }
        _codec = codec;
        if (codec.frameQuantaMs != null && targetFrameMs % codec.frameQuantaMs != 0) {
            targetFrameMs = (int) (Math.ceil(targetFrameMs / codec.frameQuantaMs) * codec.frameQuantaMs);
        }
        if (codec.frameMaxMs != null && targetFrameMs > codec.frameMaxMs) {
            targetFrameMs = codec.frameMaxMs.intValue();
        }
        if (codec.validFrameMs != null) {
            targetFrameMs = closestValidMs(codec.validFrameMs, targetFrameMs);
        }
        samplesPerFrame = (int) Math.ceil(targetFrameMs / 1000.0 * sampleRate);
        frameTime       = (double) samplesPerFrame / sampleRate;
        codec.source    = this;
        codec.sink      = _sink;
        LOG.fine(this + " frame time is " + String.format("%.3fs", frameTime));
    }

    @Override public Sink getSink()       { return _sink; }
    @Override public void setSink(Sink s) { this._sink = s; if (_codec != null) _codec.sink = s; }

    @Override public boolean isShouldRun() { return shouldRun; }
    public boolean isRunning()             { return shouldRun; }

    @Override
    public void start() {
        if (!shouldRun) {
            LOG.fine(this + " starting at " + samplesPerFrame + " spf, " + channels + " ch");
            shouldRun    = true;
            ingestThread = new Thread(this::ingestJob, "OpusFileSource-ingest");
            ingestThread.setDaemon(true);
            ingestThread.start();
        }
    }

    @Override public void stop() { shouldRun = false; }

    @Override public int getSampleRate() { return sampleRate; }
    @Override public int getChannels()   { return channels; }
    @Override public int getBitDepth()   { return bitDepth; }

    private void ingestJob() {
        synchronized (readLock) {
            long nextFrameTime = System.nanoTime();
            int fi = 0;
            while (shouldRun) {
                if (_sink != null && _sink.canReceive(this) && (!timed || System.nanoTime() >= nextFrameTime)) {
                    nextFrameTime = System.nanoTime() + (long) (frameTime * 1e9);
                    fi++;
                    int fs = (fi - 1) * samplesPerFrame;
                    int fe = Math.min(fi * samplesPerFrame, sampleCount);
                    if (fs >= sampleCount) {
                        if (loop) {
                            LOG.fine(this + " exhausted file samples, looping...");
                            fi = 0;
                        } else {
                            LOG.fine(this + " exhausted file samples, stopping...");
                            shouldRun = false;
                        }
                        continue;
                    }

                    int len = fe - fs;
                    float[][] frame = new float[len][channels];
                    for (int s = 0; s < len; s++) frame[s] = samples[fs + s];

                    if (_codec != null) {
                        byte[] encoded = _codec.encode(frame);
                        if (_sink.canReceive(this)) _sink.handleFrame(encoded, this);
                    } else {
                        passToSink(frame);
                    }
                } else {
                    try { Thread.sleep((long) (frameTime * 0.1 * 1000)); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void passToSink(float[][] frame) {
        if (_sink instanceof LineSink)          ((LineSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof OpusFileSink) ((OpusFileSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof Mixer)        ((Mixer) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof Loopback)     ((Loopback) _sink).handleDecodedFrame(frame, this);
    }

    private static int closestValidMs(double[] valid, int target) {
        int best = (int) valid[0];
        int bestDiff = Math.abs(target - best);
        for (double v : valid) {
            int diff = Math.abs(target - (int) v);
            if (diff < bestDiff) { bestDiff = diff; best = (int) v; }
        }
        return best;
    }

    @Override
    public String toString() { return "<lxst.OpusFileSource>"; }
}
