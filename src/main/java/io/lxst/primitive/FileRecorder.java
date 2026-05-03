package io.lxst.primitive;

import io.lxst.codec.NullCodec;
import io.lxst.codec.OpusCodec;
import io.lxst.filter.BandPassFilter;
import io.lxst.filter.Filter;
import io.lxst.sink.OpusFileSink;
import io.lxst.source.LineSource;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Records audio from a microphone to an OGG Opus file.
 * Mirrors Python LXST Primitives.Recorders.FileRecorder.
 */
public class FileRecorder {

    private static final Logger LOG = Logger.getLogger(FileRecorder.class.getName());

    private String filePath;
    private String recordDevice;
    private final int profile;
    private LineSource source;
    private final OpusFileSink sink;
    private final List<Filter> filters;
    private final double easeIn;
    private final double skip;
    private final float gain;

    public FileRecorder() {
        this(null, null, OpusCodec.PROFILE_AUDIO_MAX, 0.0f, 0.125, 0.075,
             Collections.singletonList(new BandPassFilter(25, 24000)));
    }

    public FileRecorder(String path) {
        this(path, null, OpusCodec.PROFILE_AUDIO_MAX, 0.0f, 0.125, 0.075,
             Collections.singletonList(new BandPassFilter(25, 24000)));
    }

    public FileRecorder(String path, String device, int profile, float gain,
                        double easeIn, double skip, List<Filter> filters) {
        this.filePath     = path;
        this.recordDevice = device;
        this.profile      = profile;
        this.gain         = gain;
        this.easeIn       = easeIn;
        this.skip         = skip;
        this.filters      = filters;

        this.sink = new OpusFileSink(path, profile);
        setSource(device);
    }

    public boolean isRunning()   { return source != null && source.isShouldRun(); }
    public boolean isRecording() { return isRunning(); }

    public void setSource(String device) {
        this.recordDevice = device;
        this.source = new LineSource(recordDevice, 20, new NullCodec(), sink,
                                     filters, gain, easeIn, skip);
    }

    public void setOutputPath(String path) {
        this.filePath = path;
        sink.setOutputPath(path);
    }

    public void start() {
        if (source != null) source.start();
    }

    public void stop() {
        if (source != null) {
            source.stop();
            while (sink.getFramesWaiting() > 0) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            sink.stop();
        }
    }

    public void record() { start(); }
}
