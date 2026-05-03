package io.lxst.generator;

import io.lxst.Mixer;
import io.lxst.codec.Codec;
import io.lxst.codec.CodecError;
import io.lxst.sink.LineSink;
import io.lxst.sink.Sink;
import io.lxst.source.Loopback;
import io.lxst.source.LocalSource;

import java.util.logging.Logger;

/**
 * Generates a continuous sine-wave tone at a configurable frequency and gain,
 * with optional ease-in/ease-out envelopes.
 *
 * Mirrors Python LXST Generators.ToneSource.
 */
public class ToneSource extends LocalSource {

    private static final Logger LOG = Logger.getLogger(ToneSource.class.getName());

    private static final int    DEFAULT_FRAME_MS   = 80;
    private static final int    DEFAULT_SAMPLERATE = 48000;
    private static final double DEFAULT_FREQUENCY  = 400.0;
    private static final double EASE_TIME_MS       = 20.0;

    private int targetFrameMs;
    private int samplesPerFrame;
    private double frameTime;
    private int sampleRate;
    private int channels;
    private int bitDepth = 32;

    private double frequency;
    private float  _gain;
    public  float  gain;   // target gain (settable from outside)
    private boolean ease;
    private double theta;
    private float  easeGain;
    private double easeTimeMs;
    private double easeStep;
    private double gainStep;
    private volatile boolean easingOut = false;
    private volatile boolean shouldRun = false;

    private Thread generateThread;
    private final Object generateLock = new Object();

    private Codec _codec;
    private Sink  _sink;

    public ToneSource(double frequency, float gain, boolean ease, double easeTimeMs,
                      int targetFrameMs, Codec codec, Sink sink, int channels) {
        this.frequency    = frequency;
        this._gain        = gain;
        this.gain         = gain;
        this.ease         = ease;
        this.easeTimeMs   = easeTimeMs;
        this.targetFrameMs = targetFrameMs;
        this.sampleRate   = DEFAULT_SAMPLERATE;
        this.channels     = channels;
        this.theta        = 0;
        this.easeGain     = 0;
        setSink(sink);
        setCodec(codec);
    }

    public ToneSource(double frequency, float gain, double easeTimeMs, int targetFrameMs, Codec codec, Sink sink) {
        this(frequency, gain, true, easeTimeMs, targetFrameMs, codec, sink, 1);
    }

    public ToneSource(double frequency, float gain, int targetFrameMs, Codec codec, Sink sink) {
        this(frequency, gain, true, EASE_TIME_MS, targetFrameMs, codec, sink, 1);
    }

    @Override
    public Codec getCodec() { return _codec; }

    @Override
    public void setCodec(Codec codec) {
        if (codec == null) { _codec = null; return; }
        _codec = codec;
        if (codec.preferredSampleRate != null) sampleRate = codec.preferredSampleRate;
        if (codec.frameQuantaMs != null && targetFrameMs % codec.frameQuantaMs != 0) {
            targetFrameMs = (int) (Math.ceil(targetFrameMs / codec.frameQuantaMs) * codec.frameQuantaMs);
        }
        if (codec.frameMaxMs != null && targetFrameMs > codec.frameMaxMs) {
            targetFrameMs = codec.frameMaxMs.intValue();
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
        samplesPerFrame = (int) Math.ceil(targetFrameMs / 1000.0 * sampleRate);
        frameTime       = (double) samplesPerFrame / sampleRate;
        easeStep  = 1.0 / (sampleRate * (easeTimeMs / 1000.0));
        gainStep  = 0.02 / (sampleRate * (easeTimeMs / 1000.0));
        codec.source = this;
        codec.sink   = _sink;
    }

    @Override public Sink getSink()       { return _sink; }
    @Override public void setSink(Sink s) { _sink = s; if (_codec != null) _codec.sink = s; }

    @Override
    public void start() {
        if (!shouldRun) {
            LOG.fine(this + " starting at " + samplesPerFrame + " spf, " + channels + " ch");
            easeGain     = ease ? 0 : 1;
            shouldRun    = true;
            generateThread = new Thread(this::generateJob, "ToneSource-gen");
            generateThread.setDaemon(true);
            generateThread.start();
        }
    }

    @Override
    public void stop() {
        if (!ease) {
            shouldRun = false;
        } else {
            easingOut = true;
        }
    }

    public boolean isRunning() {
        return shouldRun && !easingOut;
    }

    @Override public boolean isShouldRun() { return shouldRun; }
    @Override public int getSampleRate()   { return sampleRate; }
    @Override public int getChannels()     { return channels; }
    @Override public int getBitDepth()     { return bitDepth; }

    private float[][] generateFrame() {
        float[][] frame = new float[samplesPerFrame][channels];
        double step = (frequency * 2.0 * Math.PI) / sampleRate;

        for (int n = 0; n < samplesPerFrame; n++) {
            theta += step;
            float amplitude = (float) (Math.sin(theta) * _gain * easeGain);
            for (int c = 0; c < channels; c++) frame[n][c] = amplitude;

            // Track target gain changes
            if (gain > _gain) { _gain += gainStep; if (_gain > gain) _gain = gain; }
            if (gain < _gain) { _gain -= gainStep; if (_gain < gain) _gain = gain; }

            // Ease envelope
            if (ease) {
                if (easeGain < 1.0f && !easingOut) {
                    easeGain += easeStep;
                    if (easeGain > 1.0f) easeGain = 1.0f;
                } else if (easingOut && easeGain > 0.0f) {
                    easeGain -= easeStep;
                    if (easeGain <= 0.0f) {
                        easeGain  = 0.0f;
                        easingOut = false;
                        shouldRun = false;
                    }
                }
            }
        }
        return frame;
    }

    private void generateJob() {
        synchronized (generateLock) {
            while (shouldRun) {
                if (_sink != null && _sink.canReceive(this)) {
                    float[][] frame = generateFrame();
                    if (_codec != null) {
                        byte[] encoded = _codec.encode(frame);
                        _sink.handleFrame(encoded, this);
                    } else {
                        forwardDecoded(frame);
                    }
                }
                try { Thread.sleep(Math.max(1L, (long) (frameTime * 0.1 * 1000))); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void forwardDecoded(float[][] frame) {
        if (_sink instanceof Mixer)          ((Mixer) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof LineSink)  ((LineSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof Loopback)  ((Loopback) _sink).handleDecodedFrame(frame, this);
    }

    @Override
    public String toString() { return "<lxst.ToneSource>"; }
}
