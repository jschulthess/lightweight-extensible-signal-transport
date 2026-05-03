package io.lxst;

import io.lxst.codec.Codec;
import io.lxst.codec.NullCodec;
import io.lxst.network.Packetizer;
import io.lxst.sink.LineSink;
import io.lxst.sink.OpusFileSink;
import io.lxst.sink.Sink;
import io.lxst.source.Loopback;
import io.lxst.source.Source;

/**
 * Connects a Source to a Codec and then to a Sink.
 *
 * Mirrors Python LXST Pipeline.Pipeline.
 */
public class Pipeline {

    public static class PipelineError extends RuntimeException {
        public PipelineError(String msg) { super(msg); }
    }

    public final Source source;
    private Codec _codec;

    public Pipeline(Source source, Codec codec, Sink sink) {
        if (source == null) throw new PipelineError("Audio pipeline initialised with null source");
        if (sink   == null) throw new PipelineError("Audio pipeline initialised with null sink");
        if (codec  == null) throw new PipelineError("Audio pipeline initialised with null codec");

        this.source = source;
        source.setSink(sink);

        if (sink instanceof Loopback)       ((Loopback) sink).setSampleRate(source.getSampleRate());
        if (source instanceof Loopback)     ((Loopback) source).setSource(source);
        if (sink instanceof Packetizer)     ((Packetizer) sink).setSource(source);
        if (sink instanceof OpusFileSink)   { /* source reference set inside sink */ }

        setCodec(codec);
    }

    public Codec getCodec() {
        return source != null ? source.getCodec() : null;
    }

    public void setCodec(Codec codec) {
        // NullCodec signals "no encoding" — treat it as null so sources use decoded paths
        Codec effective = (codec instanceof NullCodec) ? null : codec;
        if (_codec != effective) {
            _codec = effective;
            source.setCodec(effective);
            if (effective != null) {
                effective.sink   = source.getSink();
                effective.source = source;
            }
        }
    }

    public Sink getSink() {
        return source != null ? source.getSink() : null;
    }

    public boolean isRunning() {
        return source.isShouldRun();
    }

    public void start() {
        if (!isRunning()) source.start();
    }

    public void stop() {
        if (isRunning()) source.stop();
    }
}
