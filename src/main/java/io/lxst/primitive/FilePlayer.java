package io.lxst.primitive;

import io.lxst.Pipeline;
import io.lxst.codec.RawCodec;
import io.lxst.sink.LineSink;
import io.lxst.source.Loopback;
import io.lxst.source.OpusFileSource;

import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Plays an Opus audio file through the speaker.
 * Mirrors Python LXST Primitives.Players.FilePlayer.
 */
public class FilePlayer {

    private static final Logger LOG = Logger.getLogger(FilePlayer.class.getName());

    private String filePath;
    private boolean loop;

    private OpusFileSource source;
    private final LineSink sink;
    private final RawCodec raw;
    private final Loopback loopback;
    private final Pipeline outputPipeline;
    private Pipeline inputPipeline;

    private Consumer<FilePlayer> finishedCallback;

    public FilePlayer()                          { this(null, null, false); }
    public FilePlayer(String path)               { this(path, null, false); }
    public FilePlayer(String path, boolean loop) { this(path, null, loop); }

    public FilePlayer(String path, String device, boolean loop) {
        this.filePath = path;
        this.loop     = loop;
        this.sink     = new LineSink(device);
        this.raw      = new RawCodec();
        this.loopback = new Loopback();
        this.outputPipeline = new Pipeline(loopback, raw, sink);
        if (path != null) setSource(path);
    }

    public boolean isRunning() { return source != null && source.isShouldRun(); }
    public boolean isPlaying() { return isRunning(); }

    public Consumer<FilePlayer> getFinishedCallback()              { return finishedCallback; }
    public void setFinishedCallback(Consumer<FilePlayer> callback) { this.finishedCallback = callback; }

    public void setSource(String path) {
        if (path == null) return;
        if (!new File(path).isFile()) throw new RuntimeException("File not found: " + path);
        try {
            this.filePath   = path;
            this.source     = new OpusFileSource(path, loop);
            this.inputPipeline = new Pipeline(source, new RawCodec(), loopback);
        } catch (Exception e) {
            throw new RuntimeException("Could not load audio file: " + path, e);
        }
    }

    public void loop(boolean loop) {
        this.loop = loop;
        // loop is final in OpusFileSource; call setSource(filePath) to recreate with new setting
        if (source != null && filePath != null) setSource(filePath);
    }

    public void start() {
        if (!isRunning() && source != null) {
            inputPipeline.start();
            outputPipeline.start();
            if (finishedCallback != null) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    while (isRunning()) { try { Thread.sleep(100); } catch (InterruptedException e) { return; } }
                    finishedCallback.accept(this);
                }, "lxst-file-player-cb");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    public void stop() {
        if (isRunning() && source != null) {
            inputPipeline.stop();
            outputPipeline.stop();
        }
    }

    public void play() { start(); }
}
