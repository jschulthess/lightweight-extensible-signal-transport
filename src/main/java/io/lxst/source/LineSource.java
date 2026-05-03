package io.lxst.source;

import io.lxst.Mixer;
import io.lxst.codec.Codec;
import io.lxst.codec.CodecError;
import io.lxst.codec.NullCodec;
import io.lxst.filter.Filter;
import io.lxst.sink.LineSink;
import io.lxst.sink.OpusFileSink;
import io.lxst.sink.Sink;

import javax.sound.sampled.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads audio from a microphone and feeds it through a codec to a sink.
 *
 * Mirrors Python LXST Sources.LineSource.
 */
public class LineSource extends LocalSource {

    private static final Logger LOG = Logger.getLogger(LineSource.class.getName());

    private static final int    MAX_FRAMES       = 128;
    private static final int    DEFAULT_FRAME_MS = 80;
    private static final int    SYSTEM_SAMPLERATE = 48000;
    private static final float  TYPE_MAP_FACTOR  = 32768.0f;

    private final String preferredDevice;
    private int targetFrameMs;
    private final List<Filter> filters;
    private final double easeIn;
    private final double skip;

    private int sampleRate  = SYSTEM_SAMPLERATE;
    private int channels    = 1;
    private int bitDepth    = 16;
    private int samplesPerFrame;
    private double frameTime;

    private volatile boolean shouldRun = false;
    private Thread ingestThread;
    private final Object recordingLock = new Object();

    private Codec _codec;
    private Sink _sink;

    private float gain;
    private float targetGain;
    private float currentGain;

    public LineSource() {
        this(null, DEFAULT_FRAME_MS, null, null, null, 0.0f, 0.0, 0.0);
    }

    public LineSource(String preferredDevice, int targetFrameMs, Codec codec, Sink sink,
                      List<Filter> filters, float gain, double easeIn, double skip) {
        this.preferredDevice = preferredDevice;
        this.targetFrameMs   = targetFrameMs;
        this.filters         = filters;
        this.easeIn          = easeIn;
        this.skip            = skip;
        this.gain            = gain;
        this.targetGain      = linearGain(gain);
        this.currentGain     = (easeIn != 0.0) ? 0.0f : this.targetGain;
        setCodec(codec);
        setSink(sink);
    }

    private static float linearGain(float gainDb) {
        return (float) Math.pow(10, gainDb / 10.0);
    }

    @Override
    public Codec getCodec() { return _codec; }

    @Override
    public void setCodec(Codec codec) {
        if (codec == null) { _codec = null; return; }
        _codec = codec;
        if (codec.preferredSampleRate != null) sampleRate = codec.preferredSampleRate;
        if (codec.frameQuantaMs != null && targetFrameMs % codec.frameQuantaMs != 0) {
            targetFrameMs = (int) (Math.ceil((double) targetFrameMs / codec.frameQuantaMs) * codec.frameQuantaMs);
            LOG.fine(this + " target frame time quantized to " + targetFrameMs + "ms");
        }
        if (codec.frameMaxMs != null && targetFrameMs > codec.frameMaxMs) {
            targetFrameMs = codec.frameMaxMs.intValue();
            LOG.fine(this + " target frame time clamped to " + targetFrameMs + "ms");
        }
        if (codec.validFrameMs != null) {
            targetFrameMs = closestValidMs(codec.validFrameMs, targetFrameMs);
        }
        samplesPerFrame = (int) Math.ceil((targetFrameMs / 1000.0) * sampleRate);
        frameTime       = (double) samplesPerFrame / sampleRate;
        codec.source    = this;
        codec.sink      = _sink;
    }

    @Override public Sink getSink()       { return _sink; }
    @Override public void setSink(Sink s) { this._sink = s; if (_codec != null) _codec.sink = s; }

    @Override public void start() {
        if (!shouldRun) {
            LOG.fine(this + " starting at " + samplesPerFrame + " spf, " + channels + " ch");
            shouldRun    = true;
            ingestThread = new Thread(this::ingestJob, "LineSource-ingest");
            ingestThread.setDaemon(true);
            ingestThread.start();
        }
    }

    @Override public void stop() { shouldRun = false; }

    @Override public boolean isShouldRun() { return shouldRun; }
    @Override public int getSampleRate()   { return sampleRate; }
    @Override public int getChannels()     { return channels; }
    @Override public int getBitDepth()     { return bitDepth; }

    private void ingestJob() {
        synchronized (recordingLock) {
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                LOG.warning(this + " audio input not supported with format " + format);
                return;
            }
            TargetDataLine line;
            try {
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format, samplesPerFrame * channels * 2 * 4);
                line.start();
            } catch (LineUnavailableException e) {
                LOG.log(Level.WARNING, this + " cannot open audio input", e);
                return;
            }

            byte[]  buf      = new byte[samplesPerFrame * channels * 2];
            long    started  = System.currentTimeMillis();
            boolean skipDone = (skip <= 0.0);
            boolean easeDone = (easeIn <= 0.0);

            try {
                while (shouldRun) {
                    int read = line.read(buf, 0, buf.length);
                    if (read <= 0) continue;

                    if (!skipDone) {
                        if ((System.currentTimeMillis() - started) > skip * 1000.0) {
                            skipDone = true;
                            started  = System.currentTimeMillis();
                        }
                        continue;
                    }

                    float[][] frame = bytesToFloat(buf, read, channels);

                    if (filters != null) {
                        for (Filter f : filters) frame = f.handleFrame(frame, sampleRate);
                    }

                    if (currentGain != 1.0f) {
                        for (float[] row : frame) for (int c = 0; c < row.length; c++) row[c] *= currentGain;
                    }

                    if (_sink != null && _sink.canReceive(this)) {
                        if (_codec != null && !(_codec instanceof NullCodec)) {
                            byte[] encoded = _codec.encode(frame);
                            _sink.handleFrame(encoded, this);
                        } else {
                            forwardDecoded(frame);
                        }
                    }

                    if (!easeDone) {
                        double elapsed = (System.currentTimeMillis() - started) / 1000.0;
                        currentGain = (float) (elapsed / easeIn * targetGain);
                        if (currentGain >= targetGain) { currentGain = targetGain; easeDone = true; }
                    }
                }
            } finally {
                line.stop();
                line.close();
            }
        }
    }

    private static float[][] bytesToFloat(byte[] buf, int length, int channels) {
        int samples = (length / 2) / channels;
        float[][] frame = new float[samples][channels];
        for (int s = 0; s < samples; s++) {
            for (int c = 0; c < channels; c++) {
                int off = (s * channels + c) * 2;
                short pcm = (short) ((buf[off] & 0xFF) | (buf[off + 1] << 8));
                frame[s][c] = pcm / TYPE_MAP_FACTOR;
            }
        }
        return frame;
    }

    private void forwardDecoded(float[][] frame) {
        if (_sink instanceof Mixer)          ((Mixer) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof LineSink)  ((LineSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof OpusFileSink) ((OpusFileSink) _sink).handleDecodedFrame(frame, this);
        else if (_sink instanceof Loopback)  ((Loopback) _sink).handleDecodedFrame(frame, this);
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
    public String toString() { return "<lxst.LineSource>"; }
}
