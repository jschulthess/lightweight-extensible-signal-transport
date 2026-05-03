package io.lxst.sink;

import io.lxst.source.Source;

/** Base interface for audio sinks. */
public interface Sink {
    /** Handle an incoming encoded or decoded audio frame. */
    void handleFrame(byte[] frame, Source source);

    /** Returns true when this sink is ready to accept another frame. */
    default boolean canReceive(Source fromSource) {
        return true;
    }

    /** Audio channel count of this sink (0 = unknown). */
    default int getChannels() {
        return 0;
    }

    /** Sample rate this sink expects (0 = unknown). */
    default int getSampleRate() {
        return 0;
    }
}
