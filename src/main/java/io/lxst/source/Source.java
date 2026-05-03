package io.lxst.source;

import io.lxst.codec.Codec;
import io.lxst.sink.Sink;

/** Base interface for all audio sources. */
public interface Source {

    void start();
    void stop();

    boolean isShouldRun();

    /** Sample rate in Hz. */
    int getSampleRate();

    /** Channel count. */
    int getChannels();

    /** Bit depth of raw samples (16, 32, …). */
    int getBitDepth();

    Codec getCodec();
    void setCodec(Codec codec);

    Sink getSink();
    void setSink(Sink sink);
}
