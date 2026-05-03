package io.lxst.filter;

/** Base interface for audio filters. */
public interface Filter {
    /**
     * Apply the filter to one frame of audio.
     * @param frame float[samples][channels] in [-1.0, 1.0]
     * @param sampleRate sample rate in Hz
     * @return filtered frame (may be the same array modified in place)
     */
    float[][] handleFrame(float[][] frame, int sampleRate);
}
