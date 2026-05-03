package io.lxst.sink;

import io.lxst.source.Source;

import javax.sound.sampled.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays decoded float[][] audio frames to the default (or a named) speaker.
 *
 * Mirrors Python LXST Sinks.LineSink.
 */
public class LineSink implements LocalSink {

    private static final Logger LOG = Logger.getLogger(LineSink.class.getName());

    private static final int   MAX_FRAMES     = 6;
    private static final int   AUTOSTART_MIN  = 1;
    private static final int   FRAME_TIMEOUT  = 8;
    private static final float TYPE_MAP_FACTOR = 32767.0f;

    private final String preferredDevice;
    private final boolean autoDigest;
    private final boolean lowLatency;

    private final Deque<float[][]> frameDeque  = new ArrayDeque<>();
    private final Object insertLock  = new Object();
    private final Object digestLock  = new Object();

    private volatile boolean shouldRun  = false;
    private Thread digestThread;
    private SourceDataLine outputLine;

    private int sampleRate;
    private int channels;
    private int samplesPerFrame = -1;
    private double frameTime;
    private double outputLatency;
    private final int bufferMaxHeight = MAX_FRAMES - 3;

    private long underrunAt = -1;

    public LineSink() {
        this(null);
    }

    public LineSink(String preferredDevice) {
        this(preferredDevice, true, false);
    }

    public LineSink(String preferredDevice, boolean autoDigest, boolean lowLatency) {
        this.preferredDevice = preferredDevice;
        this.autoDigest      = autoDigest;
        this.lowLatency      = lowLatency;
        // Detect default sample rate from system
        this.sampleRate = 48000;
        this.channels   = 2;
    }

    @Override
    public boolean canReceive(Source fromSource) {
        synchronized (insertLock) {
            return frameDeque.size() < bufferMaxHeight;
        }
    }

    @Override
    public int getChannels()    { return channels; }

    @Override
    public int getSampleRate()  { return sampleRate; }

    /**
     * Accepts a decoded float[][] frame [samples][channels].
     * The sink infers sample rate and channel count from the first frame.
     */
    @Override
    public void handleFrame(byte[] frameRaw, Source source) {
        throw new UnsupportedOperationException("LineSink.handleFrame(byte[]) – use handleDecodedFrame(float[][], Source)");
    }

    public void handleDecodedFrame(float[][] frame, Source source) {
        synchronized (insertLock) {
            frameDeque.addLast(frame);

            if (samplesPerFrame < 0) {
                samplesPerFrame = frame.length;
                channels        = frame[0].length;
                frameTime       = (double) samplesPerFrame / sampleRate;
                LOG.fine(this + " starting at " + samplesPerFrame + " spf, " + channels + " ch");
            }

            if (autoDigest && !shouldRun && frameDeque.size() >= AUTOSTART_MIN) {
                start();
            }
        }
    }

    public void start() {
        if (!shouldRun) {
            shouldRun    = true;
            digestThread = new Thread(this::digestJob, "LineSink-digest");
            digestThread.setDaemon(true);
            digestThread.start();
        }
    }

    public void stop() {
        shouldRun = false;
    }

    public void enableLowLatency() {
        // Placeholder – advanced low-latency mode not implemented in javax.sound.sampled
        LOG.fine("enableLowLatency requested (no-op in current backend)");
    }

    private void digestJob() {
        synchronized (digestLock) {
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            try {
                outputLine = (SourceDataLine) AudioSystem.getLine(info);
                outputLine.open(format, samplesPerFrame * channels * 2 * MAX_FRAMES);
                outputLine.start();
            } catch (LineUnavailableException e) {
                LOG.log(Level.WARNING, "LineSink: cannot open audio output line", e);
                return;
            }

            byte[] byteBuf = new byte[samplesPerFrame * channels * 2];

            while (shouldRun) {
                float[][] frame;
                synchronized (insertLock) {
                    frame = frameDeque.isEmpty() ? null : frameDeque.pollFirst();
                    if (frame != null) {
                        outputLatency  = frameDeque.size() * frameTime;
                        underrunAt     = -1;
                        if (frameDeque.size() > bufferMaxHeight) {
                            frameDeque.pollFirst();
                            LOG.fine(this + " buffer lag, dropping frame");
                        }
                    }
                }

                if (frame != null) {
                    // Clip channels to output device count
                    int outCh = Math.min(frame[0].length, channels);
                    int spf   = frame.length;
                    for (int s = 0; s < spf; s++) {
                        for (int c = 0; c < outCh; c++) {
                            float v = frame[s][c];
                            if (v >  1.0f) v =  1.0f;
                            if (v < -1.0f) v = -1.0f;
                            short pcm = (short) (v * TYPE_MAP_FACTOR);
                            int off   = (s * outCh + c) * 2;
                            byteBuf[off]     = (byte) (pcm & 0xFF);
                            byteBuf[off + 1] = (byte) ((pcm >> 8) & 0xFF);
                        }
                    }
                    outputLine.write(byteBuf, 0, spf * outCh * 2);
                } else {
                    long now = System.currentTimeMillis();
                    if (underrunAt < 0) {
                        underrunAt = now;
                    } else if (now > underrunAt + (long) (frameTime * FRAME_TIMEOUT * 1000)) {
                        LOG.fine(this + " no frames available, stopping playback");
                        shouldRun = false;
                    } else {
                        try { Thread.sleep((long) (frameTime * 0.1 * 1000)); } catch (InterruptedException ignored) {}
                    }
                }
            }

            outputLine.drain();
            outputLine.close();
        }
    }

    @Override
    public String toString() {
        return "<lxst.LineSink>";
    }
}
