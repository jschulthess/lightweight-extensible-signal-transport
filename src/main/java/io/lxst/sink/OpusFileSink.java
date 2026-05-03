package io.lxst.sink;

import io.lxst.codec.OpusCodec;
import io.lxst.ogg.OggOpusWriter;
import io.lxst.source.Source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records decoded audio frames to an OGG Opus file.
 * Mirrors Python LXST Sinks.OpusFileSink.
 */
public class OpusFileSink implements LocalSink {

    private static final Logger LOG = Logger.getLogger(OpusFileSink.class.getName());

    private static final int MAX_FRAMES       = 64;
    private static final int AUTOSTART_MIN    = 1;
    private static final int FINALIZE_TIMEOUT = 2;
    private static final float TYPE_MAP_FACTOR = 32767.0f;

    private final boolean autoDigest;
    private final int profile;
    private String outputPath;

    private final Deque<float[][]> frameDeque = new ArrayDeque<>();
    private final Object insertLock = new Object();
    private final Object digestLock = new Object();

    private volatile boolean shouldRun          = false;
    private volatile boolean recordingStopped   = false;
    private volatile boolean finalized          = false;
    private Thread digestThread;

    private int sampleRate;
    private int channels;
    private int samplesPerFrame = -1;
    private double frameTime;
    private int outputSampleRate;

    public OpusFileSink() {
        this(null);
    }

    public OpusFileSink(String path) {
        this(path, OpusCodec.PROFILE_AUDIO_MAX);
    }

    public OpusFileSink(String path, int profile) {
        this.outputPath       = path;
        this.profile          = profile;
        this.autoDigest       = true;
        this.outputSampleRate = OpusCodec.profileSampleRate(profile);
        this.channels         = OpusCodec.profileChannels(profile);
        this.sampleRate       = 0;
    }

    public int getFramesWaiting() {
        return frameDeque.size();
    }

    public void setOutputPath(String path) { this.outputPath = path; }

    @Override
    public boolean canReceive(Source fromSource) {
        synchronized (insertLock) {
            if (recordingStopped) return false;
            return frameDeque.size() < MAX_FRAMES;
        }
    }

    @Override
    public int getChannels()   { return channels; }

    @Override
    public int getSampleRate() { return sampleRate > 0 ? sampleRate : outputSampleRate; }

    @Override
    public void handleFrame(byte[] frameRaw, Source source) {
        throw new UnsupportedOperationException("Use handleDecodedFrame(float[][], Source)");
    }

    public void handleDecodedFrame(float[][] frame, Source source) {
        synchronized (insertLock) {
            frameDeque.addLast(frame);
            if (samplesPerFrame < 0) {
                sampleRate      = source.getSampleRate();
                samplesPerFrame = frame.length;
                channels        = frame[0].length;
                frameTime       = (double) samplesPerFrame / sampleRate;
                LOG.fine(this + " starting at " + samplesPerFrame + " spf, " + channels + " ch");
            }
            if (autoDigest && !shouldRun && frameDeque.size() >= AUTOSTART_MIN) start();
        }
    }

    public void start() {
        if (!shouldRun) {
            shouldRun    = true;
            digestThread = new Thread(this::digestJob, "OpusFileSink-digest");
            digestThread.setDaemon(true);
            digestThread.start();
        }
    }

    public void stop() {
        if (shouldRun) {
            recordingStopped = true;
            long timeout = System.currentTimeMillis() + FINALIZE_TIMEOUT * 1000L;
            while (!frameDeque.isEmpty() && System.currentTimeMillis() < timeout) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            shouldRun = false;
            while (!finalized) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void digestJob() {
        synchronized (digestLock) {
            if (outputPath == null) throw new IllegalStateException("No output path configured for OpusFileSink");

            int bitrateCeiling = OpusCodec.profileBitrateCeiling(profile);
            int maxBytesPerFrame = OpusCodec.maxBytesPerFrame(bitrateCeiling, frameTime * 1000.0);

            try (OggOpusWriter writer = new OggOpusWriter(
                    outputPath,
                    outputSampleRate,
                    channels,
                    profile,
                    maxBytesPerFrame
            )) {
                int finalSilenceFrames = 10;
                long underrunAt = -1;

                while (shouldRun || finalSilenceFrames > 0) {
                    float[][] frame = null;
                    synchronized (insertLock) {
                        if (!frameDeque.isEmpty()) {
                            frame = frameDeque.pollFirst();
                            underrunAt = -1;
                        }
                    }

                    if (frame == null && !shouldRun && finalSilenceFrames > 0) {
                        finalSilenceFrames--;
                        frame = new float[samplesPerFrame][channels];
                    }

                    if (frame != null) {
                        frame = adjustFrame(frame, samplesPerFrame, channels);

                        if (sampleRate != outputSampleRate) {
                            frame = resample(frame, sampleRate, outputSampleRate);
                        }

                        short[] pcm = floatToShort(frame);
                        writer.writePcm(pcm);
                    } else {
                        if (underrunAt < 0) underrunAt = System.currentTimeMillis();
                        else { try { Thread.sleep((long) (frameTime * 0.1 * 1000)); } catch (InterruptedException ignored) {} }
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, this + " error during recording", e);
            }
            finalized = true;
        }
    }

    private static float[][] adjustFrame(float[][] frame, int targetSamples, int targetChannels) {
        int s   = frame.length;
        int ch  = frame[0].length;
        float[][] out = new float[Math.max(s, targetSamples)][targetChannels];
        for (int i = 0; i < targetSamples; i++) {
            for (int c = 0; c < targetChannels; c++) {
                if (i < s) out[i][c] = (c < ch) ? frame[i][c] : frame[i][ch - 1];
            }
        }
        return out;
    }

    private static float[][] resample(float[][] in, int inRate, int outRate) {
        int inSamples  = in.length;
        int outSamples = (int) Math.round((double) inSamples * outRate / inRate);
        int ch         = in[0].length;
        float[][] out  = new float[outSamples][ch];
        for (int s = 0; s < outSamples; s++) {
            double pos = (double) s * inSamples / outSamples;
            int lo     = (int) pos;
            int hi     = Math.min(lo + 1, inSamples - 1);
            float frac = (float) (pos - lo);
            for (int c = 0; c < ch; c++) {
                out[s][c] = in[lo][c] + frac * (in[hi][c] - in[lo][c]);
            }
        }
        return out;
    }

    private static short[] floatToShort(float[][] frame) {
        int s  = frame.length;
        int ch = frame[0].length;
        short[] pcm = new short[s * ch];
        for (int i = 0; i < s; i++) {
            for (int c = 0; c < ch; c++) {
                float v = frame[i][c];
                if (v >  1.0f) v =  1.0f;
                if (v < -1.0f) v = -1.0f;
                pcm[i * ch + c] = (short) (v * TYPE_MAP_FACTOR);
            }
        }
        return pcm;
    }

    @Override
    public String toString() {
        return "<lxst.OpusFileSink>";
    }
}
