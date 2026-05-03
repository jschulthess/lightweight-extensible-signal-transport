package io.lxst;

import io.lxst.codec.Codec;
import io.lxst.codec.CodecError;
import io.lxst.sink.LocalSink;
import io.lxst.sink.LineSink;
import io.lxst.sink.OpusFileSink;
import io.lxst.sink.Sink;
import io.lxst.source.LocalSource;
import io.lxst.source.Source;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mixes decoded audio from multiple sources and forwards the mixed signal
 * (optionally re-encoded) to a single sink.
 *
 * Mirrors Python LXST Mixer.Mixer.
 */
public class Mixer extends LocalSource implements LocalSink {

    private static final Logger LOG = Logger.getLogger(Mixer.class.getName());

    private static final int   MAX_FRAMES = 8;
    private static final float TYPE_MAP   = 32767.0f;

    private int targetFrameMs;
    private int samplesPerFrame;
    private double frameTime;

    private volatile boolean shouldRun = false;
    private Thread mixerThread;
    private final Object mixerLock = new Object();
    private final Object insertLock = new Object();

    private boolean muted = false;
    private float   gain  = 0.0f;
    private int     bitDepth = 32;

    private Integer sampleRate = null;
    private Integer channels   = null;

    private Codec _codec;
    private Sink  _sink;

    private final Map<Source, Deque<float[][]>> incomingFrames = new ConcurrentHashMap<>();

    public Mixer() {
        this(40, null, null, null, 0.0f);
    }

    public Mixer(int targetFrameMs) {
        this(targetFrameMs, null, null, null, 0.0f);
    }

    public Mixer(int targetFrameMs, Integer sampleRate, Codec codec, Sink sink, float gain) {
        this.targetFrameMs = targetFrameMs;
        this.frameTime     = targetFrameMs / 1000.0;
        this.gain          = gain;
        if (sampleRate != null) this.sampleRate = sampleRate;
        if (sink  != null) setSink(sink);
        if (codec != null) setCodec(codec);
    }

    // ── Source interface ──────────────────────────────────────────────────────

    @Override public void start() {
        if (!shouldRun) {
            LOG.fine(this + " starting");
            shouldRun   = true;
            mixerThread = new Thread(this::mixerJob, "Mixer-mix");
            mixerThread.setDaemon(true);
            mixerThread.start();
        }
    }

    @Override public void stop()            { shouldRun = false; }
    @Override public boolean isShouldRun()  { return shouldRun; }
    @Override public int getSampleRate()    { return sampleRate != null ? sampleRate : 48000; }
    @Override public int getChannels()      { return channels != null ? channels : 1; }
    @Override public int getBitDepth()      { return bitDepth; }
    @Override public Codec getCodec()       { return _codec; }
    @Override public void setCodec(Codec c) { applyCodec(c); }
    @Override public Sink getSink()         { return _sink; }
    @Override public void setSink(Sink s)   { _sink = s; }

    // ── Sink interface ────────────────────────────────────────────────────────

    @Override
    public boolean canReceive(Source fromSource) {
        if (!incomingFrames.containsKey(fromSource)) return true;
        return incomingFrames.get(fromSource).size() < MAX_FRAMES;
    }

    /** Receives encoded frames; decodes via source codec and queues for mixing. */
    @Override
    public void handleFrame(byte[] frame, Source source) {
        synchronized (insertLock) {
            ensureSourceRegistered(source);
            if (source.getCodec() != null) {
                float[][] pcm = source.getCodec().decode(frame);
                if (pcm != null) incomingFrames.get(source).offerLast(pcm);
            }
        }
    }

    private void ensureSourceRegistered(Source source) {
        if (!incomingFrames.containsKey(source)) {
            incomingFrames.put(source, new ArrayDeque<>(MAX_FRAMES));
            if (channels == null) channels = source.getChannels();
            if (sampleRate == null) {
                sampleRate      = source.getSampleRate();
                samplesPerFrame = (int) Math.ceil(targetFrameMs / 1000.0 * sampleRate);
                frameTime       = (double) samplesPerFrame / sampleRate;
                LOG.fine(this + " samplerate set to " + sampleRate);
                LOG.fine(this + " frame time is " + String.format("%.3fs", frameTime));
            }
        }
    }

    /** Receive already-decoded float[][] from network layer or other source. */
    public void handleDecodedFrame(float[][] frame, Source source) {
        synchronized (insertLock) {
            ensureSourceRegistered(source);
            Deque<float[][]> q = incomingFrames.get(source);
            if (q.size() < MAX_FRAMES) q.offerLast(frame);
        }
    }

    public void setSourceMaxFrames(Source source, int maxFrames) {
        synchronized (insertLock) {
            Deque<float[][]> existing = incomingFrames.get(source);
            ArrayDeque<float[][]> bounded = new ArrayDeque<>(maxFrames);
            if (existing != null) bounded.addAll(existing);
            incomingFrames.put(source, bounded);
        }
    }

    // ── Gain / mute ───────────────────────────────────────────────────────────

    public void setGain(float gain) { this.gain = gain; }
    public void mute(boolean mute)   { this.muted = mute; }
    public void unmute(boolean un)   { this.muted = !un; }
    public boolean isMuted()         { return muted; }

    // ── Mix loop ──────────────────────────────────────────────────────────────

    private float mixingGain() {
        if (muted)        return 0.0f;
        if (gain == 0.0f) return 1.0f;
        return (float) Math.pow(10, gain / 10.0);
    }

    private void mixerJob() {
        synchronized (mixerLock) {
            while (shouldRun) {
                if (_sink != null && _sink.canReceive(this)) {
                    float[][] mixed  = null;
                    int sourceCount  = 0;
                    float g          = mixingGain();

                    for (Map.Entry<Source, Deque<float[][]>> e : incomingFrames.entrySet()) {
                        Deque<float[][]> q = e.getValue();
                        float[][] next;
                        synchronized (insertLock) { next = q.isEmpty() ? null : q.pollFirst(); }
                        if (next == null) continue;

                        if (sourceCount == 0) {
                            mixed = multiply(next, g);
                        } else {
                            mixed = add(mixed, next, g);
                        }
                        sourceCount++;
                    }

                    if (sourceCount > 0 && mixed != null) {
                        clip(mixed);
                        try {
                            if (_codec != null) {
                                _sink.handleFrame(_codec.encode(mixed), this);
                            } else {
                                forwardDecoded(mixed);
                            }
                        } catch (Exception ex) {
                            LOG.log(Level.WARNING, this + " error while mixing", ex);
                        }
                    } else {
                        sleep(frameTime * 0.1);
                    }
                } else {
                    sleep(frameTime * 0.1);
                }
            }
        }
    }

    private void forwardDecoded(float[][] frame) {
        if (_sink instanceof LineSink)          ((LineSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof OpusFileSink) ((OpusFileSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof Mixer)        ((Mixer) _sink).handleDecodedFrame(frame, this);
    }

    private static float[][] multiply(float[][] frame, float gain) {
        int s = frame.length, ch = frame[0].length;
        float[][] out = new float[s][ch];
        for (int i = 0; i < s; i++) for (int c = 0; c < ch; c++) out[i][c] = frame[i][c] * gain;
        return out;
    }

    private static float[][] add(float[][] a, float[][] b, float gain) {
        int s  = Math.min(a.length, b.length);
        int ch = Math.min(a[0].length, b[0].length);
        float[][] out = new float[s][ch];
        for (int i = 0; i < s; i++) for (int c = 0; c < ch; c++) out[i][c] = a[i][c] + b[i][c] * gain;
        return out;
    }

    private static void clip(float[][] frame) {
        for (float[] row : frame) for (int c = 0; c < row.length; c++) {
            if (row[c] >  1.0f) row[c] =  1.0f;
            if (row[c] < -1.0f) row[c] = -1.0f;
        }
    }

    private static void sleep(double seconds) {
        try { Thread.sleep(Math.max(1L, (long) (seconds * 1000))); } catch (InterruptedException ignored) {}
    }

    // ── Codec setter with frame quantization ──────────────────────────────────

    private void applyCodec(Codec codec) {
        if (codec == null) { _codec = null; return; }
        _codec = codec;
        if (codec.preferredSampleRate != null)  sampleRate = codec.preferredSampleRate;
        if (codec.frameQuantaMs != null && targetFrameMs % codec.frameQuantaMs != 0) {
            targetFrameMs = (int) (Math.ceil(targetFrameMs / codec.frameQuantaMs) * codec.frameQuantaMs);
            LOG.fine(this + " target frame time quantized to " + targetFrameMs + "ms");
        }
        if (codec.frameMaxMs != null && targetFrameMs > codec.frameMaxMs) {
            targetFrameMs = codec.frameMaxMs.intValue();
            LOG.fine(this + " target frame time clamped to " + targetFrameMs + "ms");
        }
        if (codec.validFrameMs != null) {
            int best = (int) codec.validFrameMs[0];
            int bestDiff = Math.abs(targetFrameMs - best);
            for (double v : codec.validFrameMs) {
                int diff = Math.abs(targetFrameMs - (int) v);
                if (diff < bestDiff) { bestDiff = diff; best = (int) v; }
            }
            targetFrameMs = best;
        }
    }

    @Override
    public String toString() { return "<lxst.Mixer>"; }
}
